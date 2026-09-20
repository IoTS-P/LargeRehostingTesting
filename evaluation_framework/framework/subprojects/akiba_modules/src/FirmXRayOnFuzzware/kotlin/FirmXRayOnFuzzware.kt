package org.iotsplab.akiba.process

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.FuzzwarePipeline.Companion.getFuzzwareTimeoutDuration
import org.iotsplab.akiba.process.FuzzwarePipeline.Companion.swipeFuzzwareProcesses
import org.iotsplab.akiba.process.fuzzware.CoverageInfo
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.assembly.DisasmHelper
import org.iotsplab.akiba.utils.function.allBasicBlockStarts
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.math.roundToLong

@WithTableColumn("set_fuzz_time", "INTEGER")     // Seconds
@WithTableColumn("actual_fuzz_time", "INTEGER")
@WithTableColumn("basic_blocks_visited", "INTEGER")
@WithTableColumn("basic_blocks_newfound", "INTEGER")
@WithTableColumn("basic_blocks_total", "INTEGER")
@WithTableColumn("coverage", "DOUBLE PRECISION")
@WithConfigClass(FirmXRayOnFuzzwareConfig::class)
@IgnoreRuntimeTimeout
class FirmXRayOnFuzzware (
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    configPath = configPath,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    private val prog: Program
        get() = program!!
    private val conf: FirmXRayOnFuzzwareConfig
        get() = config as FirmXRayOnFuzzwareConfig
    private lateinit var fuzzwareConf: FuzzwareGatewayConfig
    private lateinit var rootDir: Path

    // The change of visited basic blocks is a potential scale factor of the fuzzware pipeline
    var firstCov: Int = -1  // the number of visited blocks reported at the first time
    var totalCov: Int = -1   // the number of visited blocks in total
    override suspend fun startProcess() {
        fuzzwareConf = getTaskData("fuzzware_basic_conf") as FuzzwareGatewayConfig
        rootDir = Path.of(mainConf.binariesRoot, fuzzwareConf.projectRoot, id.toString())

        try {

            // logger.info("Waiting for Ghidra auto-analysis to complete before Fuzzware config generation...")
            // org.iotsplab.akiba.managers.ProgramManager.autoAnalyzeInTimeout(prog, mainConf.autoAnalysisTimeout)
            // logger.info("Ghidra analysis finished. Function count: ${prog.functionManager.functionCount}")

            if (!(callTaskAPI(FuzzwareGateway::activateEnv) as Boolean)) {
                logger.error("Failed to activate Fuzzware environment, exited")
                return
            }
            val emuConf =
                if (prog.executableFormat != "Raw Binary") {
                    logger.error("FirmXRay on Fuzzware only supports raw binary")
                    return
                } else {
                    callTaskAPI(FuzzwareGateway::generateFuzzwareConfig,
                        getTaskData("firmxray_results.base_address") as Long,
                        prog.memory.blocks.minBy { it.start }.start.offset,
                        false, taskGlobalMonitor) as Path
                }
            startPipeline(emuConf)
            updateCoverage()
            updateData(mapOf(
                "set_fuzz_time" to getFuzzwareTimeoutDuration(conf.maxTimeout).seconds,
            ))
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
                "-p pipeline " +
                "--run-for ${conf.maxTimeout} ${conf.otherArguments} " +
                "--skip-afl-cpufreq " +
                if (conf.withGDMA) "--dma" else ""

        val builder = ProcessBuilder(*basicConf.cmdPrefix.toTypedArray(), cmd)
        val timeoutDuration = getFuzzwareTimeoutDuration(conf.maxTimeout)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        logger.info(
            "Timeout is set to ${conf.maxTimeout}, will finish at " +
                    "${java.time.LocalDateTime.now().plus(timeoutDuration).format(formatter)}"
        )
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
        updateData(mapOf(
            "actual_fuzz_time" to ((endTime - startTime).toDouble() / 1000.0).roundToLong(),
        ))
    }

    private suspend fun updateCoverage() {
        val dir = rootDir.resolve("pipeline")
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
}