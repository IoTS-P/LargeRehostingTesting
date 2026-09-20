package org.iotsplab.akiba.process

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.fuzzware.CoverageInfo
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.assembly.DisasmHelper
import org.iotsplab.akiba.utils.function.allBasicBlockStarts
import java.nio.file.Path
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.math.roundToLong

@WithTableColumn("set_fuzz_time", "INTEGER")     // Seconds
@WithTableColumn("actual_fuzz_time", "INTEGER")
@WithTableColumn("basic_blocks_visited", "INTEGER")
@WithTableColumn("basic_blocks_newfound", "INTEGER")
@WithTableColumn("basic_blocks_total", "INTEGER")
@WithTableColumn("coverage", "REAL")
@WithConfigClass(FuzzwarePipelineConfig::class)
@IgnoreRuntimeTimeout
class FuzzwarePipeline (
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    configPath = configPath,
    defaultConfig = FuzzwarePipelineConfig(),
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    val conf: FuzzwarePipelineConfig
        get() = config as FuzzwarePipelineConfig

    // The change of visited basic blocks is a potential scale factor of the fuzzware pipeline
    var firstCov: Int = -1  // the number of visited blocks reported at the first time
    var totalCov: Int = -1   // the number of visited blocks in total

    override suspend fun startProcess() {
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
                        false, taskGlobalMonitor
                    ) as Path
            startPipeline(emuConf)
            updateCoverage()
            updateData(mapOf("set_fuzz_time" to getFuzzwareTimeoutDuration(conf.maxTimeout).seconds,))
        } catch (e: Exception) {
            logger.error("Failed to run fuzzware: ${e.message}")
            e.printStackTrace()
            failureSign = FAILED
            return
        }
    }

    private suspend fun startPipeline(config: Path) = coroutineScope {
        logger.info("Spawning 'fuzzware pipeline' ...")
        val dir = config.parent.resolve("pipeline")

        val basicConf = getTaskData("fuzzware_basic_conf") as? FuzzwareGatewayConfig
            ?: throw IllegalArgumentException("Fuzzware basic config not found, need to run 'FuzzwareGateway' first")

        if (dir.exists())
            dir.toFile().deleteRecursively()

        val cmd = "${basicConf.cmdPredo} && workon ${basicConf.venv} && " +
                "fuzzware pipeline \"${dir.parent.absolutePathString()}\" " +
                "--runtime-config-name \"${config.absolutePathString()}\" " +
                "-p pipeline --aflpp" +
                "--run-for ${conf.maxTimeout} ${conf.otherArguments} " +
                "--skip-afl-cpufreq" +
                if (conf.withGDMA) "--dma" else ""

        val builder = ProcessBuilder(*basicConf.cmdPrefix.toTypedArray(), cmd)
        val timeoutDuration = getFuzzwareTimeoutDuration(conf.maxTimeout)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        logger.info("Timeout is set to ${conf.maxTimeout}, will finish at " +
                "${java.time.LocalDateTime.now().plus(timeoutDuration).format(formatter)}")
        val startTime = System.currentTimeMillis()
        val process: Process = builder.start()

        val outReader = launch {
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line ?: continue

                    logger.trace(line)

                    if (line.contains("Shutdown requested!")) {
                        logger.info("Timeout get, destroyed forcibly")
                        swipeFuzzwareProcesses(logger, process)
                        break
                    }

                    if (line.startsWith("Translation blocks covered")) {
                        val v = line.substringAfter(": ").substringBefore(".").toInt()
                        if (firstCov == -1) {
                            firstCov = v
                            logger.info("First report of basic blocks visited: $v")
                        }
                    }
                }
            }
        }

        outReader.start()
        outReader.join()
        val endTime = System.currentTimeMillis()
        updateData(mapOf("actual_fuzz_time" to ((endTime - startTime).toDouble() / 1000.0).roundToLong()))
    }

    private suspend fun updateCoverage() {
        val basicConf = getTaskData("fuzzware_basic_conf") as? FuzzwareGatewayConfig
            ?: throw IllegalArgumentException("Fuzzware basic config not found, need to run 'FuzzwareGateway' first")

        val dir = Path.of(mainConf.binariesRoot).resolve(basicConf.projectRoot)
            .resolve(id.toString()).resolve("pipeline")
        val covInfo = callTaskAPI(FuzzwareGateway::getCovInfo, dir) as CoverageInfo
        totalCov = covInfo.foundBasicBlocks.size

        // Add functions that is not defined yet
        covInfo.foundBasicBlocks.forEach {
            if (it.second.first == "UNKN") {
                val addr = FlatProgramAPI(prog).toAddr(it.first)
                if (prog.memory.contains(addr) && prog.listing.getFunctionContaining(addr) == null)
                    DisasmHelper(prog).disasmFunction(addr, monitor = taskGlobalMonitor)
            }
        }

        // Get all basic blocks
        var allBasicBlocks: MutableList<Address> = prog.listing.getFunctions(true).flatMap { func ->
            func.allBasicBlockStarts()
        }.toMutableList()

        // Add basic blocks that is not existed in `allBasicBlocks` but in `covInfo.foundBasicBlocks`
        allBasicBlocks.addAll(covInfo.foundBasicBlocks.filter { it.second.first != "UNKN" }.map {
            FlatProgramAPI(prog).toAddr(it.first)
        })
        allBasicBlocks = allBasicBlocks.distinctBy { it.offset }.toMutableList()

        logger.info("Total number of basic blocks visited: $totalCov")

        updateData(mapOf(
            "basic_blocks_visited" to totalCov.toLong(),
            "basic_blocks_newfound" to (totalCov - firstCov).toLong()
        ))

        logger.info("Total basic block number: ${allBasicBlocks.size}")
        logger.info("Coverage: ${totalCov.toDouble() / allBasicBlocks.size}")

        updateData(mapOf(
            "basic_blocks_total" to allBasicBlocks.size.toLong(),
            "coverage" to totalCov.toDouble() / allBasicBlocks.size
        ))
    }

    companion object {
        @Throws(IllegalArgumentException::class)
        fun getFuzzwareTimeoutDuration(timeout: String): Duration {
            val pattern = Pattern.compile("(\\d+):(\\d+):(\\d+):(\\d+)")
            val matcher = pattern.matcher(timeout)

            if (matcher.matches()) {
                val days = matcher.group(1).toInt()
                val hours = matcher.group(2).toInt()
                val minutes = matcher.group(3).toInt()
                val seconds = matcher.group(4).toInt()

                val totalSeconds = (days * 24 * 60 * 60 + hours * 60 * 60 + minutes * 60 + seconds).toLong()

                return Duration.ofSeconds(totalSeconds)
            } else {
                throw IllegalArgumentException("Invalid fuzzware time format. Expected format: DD:HH:mm:ss")
            }
        }

        fun swipeFuzzwareProcesses(logger: Logger, p: Process) {
            p.destroyForcibly()
            p.waitFor()

            logger.info("Clearing orphan fuzzware process...")
            var hasOrphan = true
            var pass = 0
            // A force-killed pipeline leaves its own redis-server reparented to PID 1, where `ps`
            // shows it as a zombie ("[redis-server] <defunct>").  A zombie cannot be killed and
            // holds no port, so counting it as an orphan made this loop spin forever: the fuzz
            // stage never returned and its log filled with "kill: <pid>" lines (observed: 476k
            // lines / 71 MB).  Skip them, and bound the retries as a safety net for a host whose
            // PID 1 does not reap (see the container's `init: true`).
            val maxPasses = 16

            while (hasOrphan && pass < maxPasses) {
                pass++
                // Make sure all subprocesses are killed (kill orphan processes)
                hasOrphan = false
                val cmd = "/bin/ps -ef"
                val process = ProcessBuilder(cmd.split(" ")).start()
                val outLines = process.inputStream.bufferedReader().readLines()
                process.waitFor()

                val format = Regex("^.+\\s+([0-9]+)\\s+([0-9]+)\\s+([0-9]+)\\s+.+$")
                outLines.forEach { line ->
                    // already dead: a zombie holds no ports and kill -9 cannot reap it
                    if (line.contains("<defunct>"))
                        return@forEach
                    // Fuzzware use redis, so we need to kill the redis server process, or they may occupy many ports
                    if (!line.contains("fuzzware") && !line.contains("redis-server"))
                        return@forEach
                    format.matchEntire(line) ?. let {
                        val ppid = it.groupValues[2].toLong()
                        if (ppid == 1L) {
                            hasOrphan = true
                            logger.info("kill: ${it.groupValues[1]}")
                            ProcessBuilder("kill", "-9", it.groupValues[1]).start().waitFor()
                        }
                    }
                }
            }
        }
    }
}