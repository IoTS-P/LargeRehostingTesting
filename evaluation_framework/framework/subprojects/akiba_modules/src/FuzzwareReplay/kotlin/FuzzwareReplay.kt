package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.FuzzwareReplay.Companion.CRASH_VIEW_SQL
import org.iotsplab.akiba.process.fuzzware.FuzzwareOutputMonitor
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.WithView
import org.iotsplab.akiba.utils.FailOnCancelled
import org.iotsplab.akiba.utils.function.allBasicBlockStarts
import org.iotsplab.akiba.utils.function.allFunctionStarts
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

@WithTableColumn("crash_replay_crashes", "TEXT")
@WithView("fuzzware_replay_crashes", CRASH_VIEW_SQL)
@FailOnCancelled
class FuzzwareReplay (
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
            val emuConf =
                if (prog.executableFormat == "Executable and Linking Format (ELF)")
                    callTaskAPI(FuzzwareGateway::generateFuzzwareConfigForELF, false) as Path
                else
                    callTaskAPI(FuzzwareGateway::generateFuzzwareConfig,
                        getTaskData("arm_base_finder_results.base_address"),
                        getTaskData("arm_base_finder_results.ivt_start"),
                        false,
                        taskGlobalMonitor
                    ) as Path
            projectRoot = emuConf.parent.resolve("pipeline")
            val allCrashesPath = getAllCrashesPath(projectRoot)
            if (allCrashesPath.isEmpty()) {
                logger.info("No crash found")
                return
            }

            allCrashesPath.forEach { results.add(startReplay(emuConf, it)) }
            updateData(mapOf("crash_replay_crashes" to Json.encodeToString(results)))
        } catch (e: Exception) {
            logger.error("Failed to run fuzzware: ${e.message}")
            failureSign = FAILED
            return
        }
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
            FROM fuzzware_replay_results AS frr
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