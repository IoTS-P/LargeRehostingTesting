package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.binFormat.ELFStructures
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.appendText
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.math.roundToLong

/**
 * HoedurFuzz: A module for running Hoedur fuzzing processes
 *
 * Note: Before running this module, don't forget to install python requirements in Fuzzware venv.
 */
@WithTableColumn("set_fuzz_time", "INTEGER")
@WithTableColumn("actual_fuzz_time", "INTEGER")
@WithConfigClass(HoedurFuzzConfig::class)
@IgnoreRuntimeTimeout
class HoedurFuzz (
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    program = program,
    configPath = configPath,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    private val conf: HoedurFuzzConfig
        get() = config as HoedurFuzzConfig
    private val hoedurOutputRoot: Path = Path.of(mainConf.binariesRoot)
        .resolve(Path.of(conf.crashArchiveRoot)).resolve("$id")
    private lateinit var fuzzwareConf: Path

    @Throws(IllegalArgumentException::class)
    override suspend fun startProcess() {
        conf.hoedurRoot ?.let {
            if (!Path.of(it).isDirectory())
                throw IllegalArgumentException("Hoedur root directory not found")
        } ?: throw IllegalArgumentException("Hoedur root directory not specified")

        if (hoedurOutputRoot.exists()) {
            hoedurOutputRoot.toFile().deleteRecursively()
            hoedurOutputRoot.createDirectories()
        }

        try {
            fuzzwareConf =
                if (prog.executableFormat == "Executable and Linking Format (ELF)")
                    callTaskAPI(FuzzwareGateway::generateFuzzwareConfigForELF, true) as Path
                else {
                    callTaskAPI(
                        FuzzwareGateway::generateFuzzwareConfig,
                        getTaskData(conf.baseAddressSource) as Long,
                        conf.ivtStartSource ?.let { getTaskData(it) }
                                            ?:prog.memory.blocks.minBy { it.start }.start.offset,
                        true,
                        taskGlobalMonitor
                    ) as Path
                }
        } catch (e: Exception) {
            logger.error("Failed to run fuzzware: ${e.message}")
            failureSign = FAILED
            return
        }

        runHoedur()
        updateData(mapOf("set_fuzz_time" to parseHoedurTimeout(conf.maxTimeoutMinutes)))
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    private suspend fun runHoedur() = coroutineScope {
        val hoedurConfigPath = convertConfig()
        val basicConf = getTaskData("fuzzware_basic_conf") as? FuzzwareGatewayConfig
            ?: throw IllegalArgumentException("Fuzzware basic config not found, need to run 'FuzzwareGateway' first")

        val hoedurLogConfigPath = Path.of(conf.hoedurLogConfigPath!!)
        if (!hoedurLogConfigPath.isRegularFile())
            throw IllegalArgumentException("Hoedur log config file not found")

        // TODO: Optimize it to be an api
        if (prog.executableFormat == "Executable and Linking Format (ELF)") {
            val parsedELFbinPath = fuzzwareConf.parent.resolve("parsedELF.bin")
            if (!parsedELFbinPath.exists())
                throw IllegalStateException("Parsed ELF bin file not found")
            parsedELFbinPath.copyTo(hoedurOutputRoot.resolve("parsedELF.bin"), true)
        }
        else {
            val copiedBinPath = hoedurConfigPath.parent.resolve("$id.bin").toFile()
            if (!copiedBinPath.exists())
                usingFile.copyTo(copiedBinPath, true)
        }

        logger.info("Ready to run hoedur to fuzz...")
        val hoedurFuzzCmd = "${basicConf.cmdPredo} && workon fuzzware && cd ${hoedurOutputRoot.absolutePathString()} && " +
                            "python3 ${conf.hoedurRoot}/scripts/fuzz.py ${hoedurOutputRoot.absolutePathString()} " +
                            "--duration ${conf.maxTimeoutMinutes} " +
                            "--corpus ${hoedurOutputRoot.absolutePathString()} --fuzzware --log"

        val builder = ProcessBuilder(*basicConf.cmdPrefix.toTypedArray(), hoedurFuzzCmd)
            .redirectErrorStream(true)
            .redirectOutput(hoedurOutputRoot.resolve("console.txt").toFile())
        val startTime = System.currentTimeMillis()
        val process: Process = builder.start()

//        try {
//            logger.info("Timeout: ${conf.maxTimeoutMinutes} minutes")
//            withTimeout(conf.maxTimeoutMinutes.toLong() * 60 * 1000) {
//                process.inputStream.bufferedReader().use { reader ->
//                    var line: String?
//                    while (reader.readLine().also { line = it } != null) {
//                        line ?: continue
//                        logger.trace(line)
//                    }
//                }
//            }
//        } catch (_: TimeoutCancellationException) {
//            logger.info("Hoedur fuzzing timeout")
//        } catch (e: Exception) {
//            logger.error("Error occurred while running hoedur: ${e.message}")
//        }

        process.waitFor()
        val endTime = System.currentTimeMillis()
        process.destroyForcibly()
        updateData(mapOf("actual_fuzz_time" to ((endTime - startTime).toDouble() / 1000.0).roundToLong()))
    }

    /**
     * Convert the config file, Fuzzware configs cannot be directly used in hoedur, and need to be converted
     * using hoedur's config converter tool.
     */
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    private suspend fun convertConfig(): Path = coroutineScope {
        logger.info("Converting fuzzware config to hoedur config...")
        val basicConf = getTaskData("fuzzware_basic_conf") as? FuzzwareGatewayConfig
            ?: throw IllegalArgumentException("Fuzzware basic config not found, need to run 'FuzzwareGateway' first")

        if (hoedurOutputRoot.notExists())
            hoedurOutputRoot.toFile().mkdirs()

        val cmd = "${basicConf.cmdPredo} && workon fuzzware && cd ${conf.hoedurRoot} && " +
                  "${conf.cargoPath} run --bin hoedur-convert-fuzzware-config " +
                  "\"${fuzzwareConf.absolutePathString()}\" " +
                  "\"${hoedurOutputRoot.resolve("config.yml").absolutePathString()}\""

        logger.debug(cmd)

        val builder = ProcessBuilder(*basicConf.cmdPrefix.toTypedArray(), cmd)
            .redirectErrorStream(true)
        val process: Process = builder.start()

        val outReader = launch {
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line ?: continue
                    logger.trace(line)
                }
            }
        }

        outReader.start()
        outReader.join()

        if (!hoedurOutputRoot.resolve("config.yml").readText().contains("board:")) {
            if (prog.executableFormat == "Executable and Linking Format (ELF)") {
                val es = ELFStructures(prog, logger)
                hoedurOutputRoot.resolve("config.yml").appendText("""
            
                    board:
                        init_nsvtor: 0x${es.elfHeader.e_entry().toString(16)}
                """.trimIndent())
            } else {
                hoedurOutputRoot.resolve("config.yml").appendText("""
            
                    board:
                        init_nsvtor: 0x${(getTaskData(conf.ivtStartSource) as Long).toString(16)}
                """.trimIndent())
            }
        }

        return@coroutineScope hoedurOutputRoot.resolve("config.yml")
    }

    companion object {
        fun parseHoedurTimeout(timeout: String): Long {
            val regex = Regex("((\\d+)[dhms])+")
            val match = regex.matchEntire(timeout)
                ?: throw IllegalArgumentException("Invalid fuzzware timeout format")
            val partRegex = Regex("(\\d+)([dhms])")
            var total = 0L
            partRegex.findAll(timeout).forEach {
                val (num, unit) = it.destructured
                when (unit) {
                    "d" -> total += num.toInt() * 24 * 60 * 60
                    "h" -> total += num.toInt() * 60 * 60
                    "m" -> total += num.toInt() * 60
                    "s" -> total += num.toInt()
                }
            }
            return total
        }
    }
}