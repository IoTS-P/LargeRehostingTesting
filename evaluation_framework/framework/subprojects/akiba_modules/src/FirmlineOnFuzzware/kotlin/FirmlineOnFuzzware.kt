package org.iotsplab.akiba.process

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import ghidra.util.task.TaskMonitor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.FuzzwareGateway.Companion.configTemplate
import org.iotsplab.akiba.process.FuzzwarePipeline.Companion.getFuzzwareTimeoutDuration
import org.iotsplab.akiba.process.FuzzwarePipeline.Companion.swipeFuzzwareProcesses
import org.iotsplab.akiba.process.fuzzware.CoverageInfo
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.assembly.DisasmHelper
import org.iotsplab.akiba.utils.function.allBasicBlockStarts
import org.iotsplab.akiba.utils.function.allFunctionStarts
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import kotlin.math.roundToLong

@WithTableColumn("set_fuzz_time", "INTEGER")     // Seconds
@WithTableColumn("actual_fuzz_time", "INTEGER")
@WithTableColumn("basic_blocks_visited", "INTEGER")
@WithTableColumn("basic_blocks_newfound", "INTEGER")
@WithTableColumn("basic_blocks_total", "INTEGER")
@WithTableColumn("coverage", "DOUBLE PRECISION")
@WithConfigClass(FirmlineOnFuzzwareConfig::class)
@IgnoreRuntimeTimeout
class FirmlineOnFuzzware (
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
    private val conf: FirmlineOnFuzzwareConfig
        get() = config as FirmlineOnFuzzwareConfig
    private lateinit var fuzzwareConf: FuzzwareGatewayConfig
    private lateinit var rootDir: Path

    // The change of visited basic blocks is a potential scale factor of the fuzzware pipeline
    var firstCov: Int = -1  // the number of visited blocks reported at the first time
    var totalCov: Int = -1   // the number of visited blocks in total
    override suspend fun startProcess() {
        fuzzwareConf = getTaskData("fuzzware_basic_conf") as FuzzwareGatewayConfig
        rootDir = Path.of(mainConf.binariesRoot, fuzzwareConf.projectRoot, id.toString())

        try {
            if (!(callTaskAPI(FuzzwareGateway::activateEnv) as Boolean)) {
                logger.error("Failed to activate Fuzzware environment, exited")
                return
            }
            val emuConf =
                if (prog.executableFormat != "Raw Binary") {
                    logger.error("FirmXRay on Fuzzware only supports raw binary")
                    return
                }
                else
                    generateFuzzwareConfig(false)
            startPipeline(emuConf)
            updateCoverage()
            updateData(mapOf("set_fuzz_time" to getFuzzwareTimeoutDuration(conf.maxTimeout).seconds))
        } catch (e: Exception) {
            logger.error("Failed to run fuzzware: ${e.message}")
            e.printStackTrace()
            failureSign = FAILED
            return
        }
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    suspend fun generateFuzzwareConfig(forHoedur: Boolean): Path {
//        val elfPath = Path.of(mainConf.binariesRoot).resolve(
//            getTaskData("CONVERTFIRMTOELF_RESULTS.ELF_PATH") as? String
//                ?: throw IllegalArgumentException("ELF path not found"))

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
        /* We choose to consider firmware start as isr_table, which is right in most occasions */
        val ivtStart = prog.memory.blocks.first().start.offset

        prog.memory.blocks.forEach {
            prog.memory.moveBlock(it, it.start.add(baseAddress), TaskMonitor.DUMMY)
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
        val originalPath = originalFile.toPath()
        originalFile.toPath().copyTo(rootDir.resolve("$id.bin"), overwrite = true)
        configPath.writeText(String.format(
            configTemplate,
            baseAddress,
            if(forHoedur) originalPath.fileName else originalPath.absolutePathString(),
            ivtStart - baseAddress,
            originalPath.fileSize(),
            allFunctions.map {"  0x${it.key.toString(16)}: ${it.value}" }.joinToString("\n")
        ))

        return configPath
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
                "--skip-afl-cpufreq" +
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
}