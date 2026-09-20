package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.FirmXRayOnFuzzwareReplay.Companion.CRASH_VIEW_SQL
import org.iotsplab.akiba.process.fuzzware.FuzzwareOutputMonitor
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.WithView
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.function.allBasicBlockStarts
import org.iotsplab.akiba.utils.function.allFunctionStarts
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.forEach
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries


/**
 * WARNING: Using `trace` as file log level is highly unrecommended, which may cause the log file to be too large!
 */
@WithTableColumn("crash_replay_results", "JSONB")
@WithView("firmxray_fuzzware_replay_crashes", CRASH_VIEW_SQL)
@WithConfigClass(FirmXRayOnFuzzwareReplayConfig::class)
@IgnoreRuntimeTimeout
class FirmXRayOnFuzzwareReplay (
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    configPath = configPath,
    id = id,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!
    val conf: FirmXRayOnFuzzwareReplayConfig
        get() = config as FirmXRayOnFuzzwareReplayConfig

    private lateinit var fuzzwareConf: FuzzwareGatewayConfig
    private lateinit var rootDir: Path
    private var totalReplayCount = -1
    private var finishedReplayCount = 0
    private val updateCountLock: ReentrantLock = ReentrantLock()

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
        concurrentSemaphore ?:run { concurrentSemaphore = Semaphore(conf.maxThreads) }

        val results = mutableListOf<CrashReplayResult>()
        try {
            fuzzwareConf = getTaskData("fuzzware_basic_conf") as FuzzwareGatewayConfig
            rootDir = Path.of(mainConf.binariesRoot, fuzzwareConf.projectRoot, id.toString())

            if (!(callTaskAPI(FuzzwareGateway::activateEnv) as Boolean)) {
                logger.error("Failed to activate Fuzzware environment, exited")
                return
            }
            projectRoot = rootDir.resolve("pipeline")
            val allCrashesPath = getAllCrashesPath(projectRoot)
            if (allCrashesPath.isEmpty()) {
                logger.info("No crash found")
                return
            }
            totalReplayCount = allCrashesPath.size

            val replays = allCrashesPath.map {
                CoroutineScope(coroutineContext).launch {
                    concurrentSemaphore!!.withPermit {
                        results.add(startReplay(it))
                    }
                }
            }
            replays.forEach { it.join() }

            updateData(mapOf("crash_replay_results" to Json.encodeToString(results)))
        } catch (e: Exception) {
            logger.error("Failed to run fuzzware: ${e.message}")
            e.printStackTrace()
            failureSign = FAILED
            return
        }
    }

    private suspend fun startReplay(crashInput: Path): CrashReplayResult = coroutineScope {
        logger.info("Replaying input: ${projectRoot.relativize(crashInput)}")

        val basicConf = getTaskData("fuzzware_basic_conf") as? FuzzwareGatewayConfig
            ?: throw IllegalArgumentException("Fuzzware basic config not found, need to run 'FuzzwareGateway' first")

        val cmd = "${basicConf.cmdPredo} && workon ${basicConf.venv} && " +
                "fuzzware replay -dtMv \"${crashInput.absolutePathString()}\" -p ${projectRoot.absolutePathString()}"

        val builder = ProcessBuilder(*basicConf.cmdPrefix.toTypedArray(), cmd)
        val process: Process = builder.start()

        val monitor = FuzzwareOutputMonitor(process, true, logger)
        monitor.monitorTillExit()

        val result = CrashReplayResult(
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

        updateCountLock.withLock {
            finishedReplayCount += 1
            logger.info("Finished: $finishedReplayCount/$totalReplayCount")
        }

        return@coroutineScope result
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
            FROM firmxray_on_fuzzware_replay_results AS frr
            JOIN jsonb_array_elements(frr.crash_replay_results) AS elem(value) ON true
            ORDER BY frr.id;
        """

        var concurrentSemaphore: Semaphore? = null

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