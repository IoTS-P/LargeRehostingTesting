package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import ghidra.util.task.TaskMonitor
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.FirmlineOnFuzzwareReplay.Companion.CRASH_VIEW_SQL
import org.iotsplab.akiba.process.FuzzwareGateway.Companion.configTemplate
import org.iotsplab.akiba.process.fuzzware.FuzzwareOutputMonitor
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.WithView
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.TaskInterface
import org.iotsplab.akiba.utils.function.allBasicBlockStarts
import org.iotsplab.akiba.utils.function.allFunctionStarts
import java.nio.file.Path
import kotlin.collections.forEach
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

@WithTableColumn("crash_replay_results", "TEXT")
@WithView("firmline_fuzzware_replay_crashes", CRASH_VIEW_SQL)
@IgnoreRuntimeTimeout
class FirmlineOnFuzzwareReplay (
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    lateinit var projectRoot: Path

    @Serializable
    data class CrashReplayResult(
        val path: String,
        val errMsg: List<String>,
        val errInInterrupt: Boolean,
        val errRegContext: Map<String, Long>,
        val functionCoverage: Double,
        val basicBlockCoverage: Double,
    )

    override suspend fun startProcess() {
        val results = mutableListOf<CrashReplayResult>()
        try {
            if (!(callTaskAPI(FuzzwareGateway::activateEnv) as Boolean)) {
                logger.error("Failed to activate Fuzzware environment, exited")
                return
            }
            val emuConf = generateFuzzwareConfig(false)
            projectRoot = emuConf.parent.resolve("pipeline")
            val allCrashesPath = getAllCrashesPath(projectRoot)
            if (allCrashesPath.isEmpty()) {
                logger.info("No crash found")
                return
            }

            allCrashesPath.forEach { results.add(startReplay(emuConf, it)) }
            updateData(mapOf("crash_replay_results" to Json.encodeToString(results)))
        } catch (e: Exception) {
            logger.error("Failed to run fuzzware: ${e.message}")
            e.printStackTrace()
            failureSign = FAILED
            return
        }
    }

    @TaskInterface
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    suspend fun generateFuzzwareConfig(forHoedur: Boolean): Path {
//        val elfPath = Path.of(mainConf.binariesRoot).resolve(
//            getTaskData("CONVERTFIRMTOELF_RESULTS.ELF_PATH") as? String
//                ?: throw IllegalArgumentException("ELF path not found"))
        val fuzzwareConf: FuzzwareGatewayConfig = getTaskData("fuzzware_basic_conf") as FuzzwareGatewayConfig
        val rootDir = Path.of(mainConf.binariesRoot, fuzzwareConf.projectRoot, id.toString())

        val configPath = rootDir.resolve("config.yml")

        if (configPath.isRegularFile() && !fuzzwareConf.regenerateConfig)
            return configPath
        else
            configPath.writeText("")    // Clear previous config that may exist

//        if (!elfPath.isRegularFile()) {
//            logger.error("ELF file not found")
//            dbInterface.updateErr("ELF not found")
//            throw IllegalStateException()
//        }

        val baseAddress = getTaskData("firmline_base_checker_results.base_address") as Long
        prog.memory.blocks.forEach {
            prog.memory.moveBlock(it, it.start.add(baseAddress), taskGlobalMonitor)
        }
        val allFunctions: Map<Long, String> = prog.allFunctionStarts().associate { addr ->
            addr.offset to prog.listing.getFunctionAt(addr).name
        }
        if (allFunctions.isEmpty()) {
            logger.error("We cannot emulate a file that cannot get any function, not a valid firmware bin?")
            throw IllegalStateException()
        }

        logger.info("Generating fuzzware config...")

        // Directly use original binary file (sometimes trimmed)
        val originalPath = Path.of(getMetadata().originalPath)
        Path.of(getMetadata().originalPath).copyTo(
            rootDir.resolve(getMetadata().originalPath.split("/").last()), overwrite = true)
        configPath.writeText(String.format(
            configTemplate,
            baseAddress,
            if(forHoedur) originalPath.fileName else originalPath.absolutePathString(),
            0L,
            originalPath.fileSize(),
            allFunctions.map {"  0x${it.key.toString(16)}: ${it.value}" }.joinToString("\n")
        ))

        return configPath
    }

    private suspend fun startReplay(config: Path, crashInput: Path): CrashReplayResult = coroutineScope {
        logger.info("Replaying input: ${projectRoot.relativize(crashInput)}")

        val basicConf = getTaskData("fuzzware_basic_conf") as? FuzzwareGatewayConfig
            ?: throw IllegalArgumentException("Fuzzware basic config not found, need to run 'FuzzwareGateway' first")

        val cmd = "${basicConf.cmdPredo} && workon ${basicConf.venv} && " +
                "fuzzware replay -dtMv \"${crashInput.absolutePathString()}\" -p ${projectRoot.absolutePathString()}"

        val builder = ProcessBuilder(*basicConf.cmdPrefix.toTypedArray(), cmd)
        val process: Process = builder.start()

        val monitor = FuzzwareOutputMonitor(process, true, logger)
        monitor.monitorTillExit()

        return@coroutineScope CrashReplayResult(
            projectRoot.relativize(crashInput).toString(),
            monitor.getErrMsgList(),
            monitor.getInterruptStack().isNotEmpty(),
            monitor.errRegContext,
            monitor.getFunctionCoverage(prog.allFunctionStarts().map { it.offset }),
            monitor.getBasicBlockCoverage(
                prog.functionManager.getFunctions(false).toList().map { func ->
                    func.allBasicBlockStarts().map { it.offset }
                }.flatMap { it }.distinct()
            )
        )
    }

    companion object {
        const val CRASH_VIEW_SQL = """
            SELECT
                frr.id,
                (elem.value -> 'path')::text AS path,
                (elem.value -> 'errMsg')::text AS err_msg,
                (elem.value -> 'errInInterrupt')::bool AS err_in_interrupt,
                (elem.value -> 'functionCoverage')::double precision AS func_cov,
                (elem.value -> 'basicBlockCoverage')::double precision AS basic_block_cov,
                (elem.value -> 'errRegContext' -> 'pc')::bigint AS pc,
                (elem.value -> 'errRegContext' -> 'lr')::bigint AS lr
            FROM firmline_on_fuzzware_replay_results AS frr
            JOIN jsonb_array_elements(frr.crash_replay_results) AS elem(value) ON true
            ORDER BY frr.id;
        """

        fun getAllCrashesPath(projectRoot: Path): List<Path> {
            if (!projectRoot.isDirectory())
                return listOf()
            return projectRoot.listDirectoryEntries("main*").flatMap { main ->
                val fuzzersDir = main.resolve("fuzzers")
                if (!fuzzersDir.isDirectory())
                    return@flatMap listOf()
                val crashesList = mutableListOf<Path>()
                fuzzersDir.listDirectoryEntries("fuzzer*").forEach { fuzzer ->
                    crashesList.addAll(
                        fuzzer.resolve("crashes").toFile().listFiles()!!.map { it.toPath() })
                }
                crashesList.removeIf { it.fileName.toString().contains("README") }
                return@flatMap crashesList
            }
        }
    }
}