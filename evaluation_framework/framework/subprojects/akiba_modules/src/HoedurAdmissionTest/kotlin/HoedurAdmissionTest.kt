package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.managers.ProgramManager
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.binFormat.ELFStructures
import java.nio.file.Path
import kotlin.io.path.*
import java.io.File

data class HoedurAdmissionTestConfig(
    val cmdPrefix: List<String> = listOf("/bin/zsh", "-ic"),
    val cmdPredo: String = "source ~/.zshrc && source /usr/share/virtualenvwrapper/virtualenvwrapper.sh",
    val venv: String = "fuzzware",
    val projectRoot: String = "hoedur_admission_test",
    val hoedurRoot: String? = null,
    val cargoPath: String = "cargo",
    val baseAddressSource: String = "firmxray_results.base_address",
    val ivtStartSource: String? = null
)

@WithTableColumn("result", "TEXT")
@WithTableColumn("detail", "TEXT")
@WithConfigClass(HoedurAdmissionTestConfig::class)
@IgnoreRuntimeTimeout
class HoedurAdmissionTest(
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule(
    id = id,
    program = program,
    configPath = configPath,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {

    private val conf: HoedurAdmissionTestConfig get() = config as HoedurAdmissionTestConfig
    
    private val outputDir: Path by lazy {
        Path.of(mainConf.binariesRoot)
            .resolve(conf.projectRoot)
            .resolve("$id")
    }
        
    private lateinit var fuzzwareConf: Path

    override suspend fun startProcess() {
        if (conf.hoedurRoot == null || !Path.of(conf.hoedurRoot!!).isDirectory()) {
            throw IllegalArgumentException("Hoedur root directory not found: ${conf.hoedurRoot}")
        }

        logger.info("Starting Hoedur Admission Test for ID: $id")

        ProgramManager.autoAnalyzeInTimeout(program!!, mainConf.autoAnalysisTimeout)

        if (outputDir.exists()) {
            outputDir.toFile().deleteRecursively()
        }
        outputDir.createDirectories()

        try {

            fuzzwareConf = if (program!!.executableFormat == "Executable and Linking Format (ELF)") {
                callTaskAPI(FuzzwareGateway::generateFuzzwareConfigForELF, true) as Path
            } else {
                val baseAddr = getTaskData(conf.baseAddressSource) as Long
                val ivtAddr = conf.ivtStartSource?.let { getTaskData(it) as Long }
                    ?: program!!.memory.blocks.minBy { it.start }.start.offset
                callTaskAPI(FuzzwareGateway::generateFuzzwareConfig, baseAddr, ivtAddr, true, taskGlobalMonitor) as Path
            }

            val hoedurConfigPath = convertConfig()

            val (result, detail) = runHoedurAdmission(hoedurConfigPath)

            updateData(mapOf(
                "result" to result,
                "detail" to detail
            ))

            if (result != "PASSED") failureSign = FAILED

        } catch (e: Exception) {
            logger.error("Hoedur Admission Test Error: ${e.message}")
            updateErr("RUNTIME_ERROR: ${e.message}")
            failureSign = RUNTIME_ERROR
        }
    }

    private suspend fun convertConfig(): Path = coroutineScope {
        logger.info("Converting Fuzzware config to Hoedur format...")
        val basicConf = getTaskData("fuzzware_basic_conf") as FuzzwareGatewayConfig
        val targetConfig = outputDir.resolve("config.yml")

        val converterBin = Path.of(conf.hoedurRoot!!).resolve("target/debug/hoedur-convert-fuzzware-config")
        
        if (!converterBin.exists()) {
            throw IllegalStateException("Converter binary not found at ${converterBin.absolutePathString()}. Please run 'cargo build' manually first.")
        }

        val cmd = "${basicConf.cmdPredo} && workon fuzzware && " +
                "${converterBin.absolutePathString()} " +
                "\"${fuzzwareConf.absolutePathString()}\" \"${targetConfig.absolutePathString()}\""

        logger.debug("Conversion Command: $cmd")

        val builder = ProcessBuilder(*basicConf.cmdPrefix.toTypedArray(), cmd).redirectErrorStream(true)
        val process = builder.start()

        launch {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { logger.trace("[Converter] $it") }
            }
        }

        if (process.waitFor() != 0) throw IllegalStateException("Config conversion failed.")

        val configText = targetConfig.readText()
        if (!configText.contains("board:")) {
            val entryAddr = if (program!!.executableFormat == "Executable and Linking Format (ELF)") {
                ELFStructures(program!!, logger).elfHeader.e_entry().toString(16)
            } else {
                val ivt = conf.ivtStartSource?.let { getTaskData(it) as? Long }
                    ?: program!!.memory.blocks.minByOrNull { it.start }?.start?.offset
                    ?: program!!.memory.minAddress.offset
                ivt.toString(16)
            }

            targetConfig.appendText("""
                
                board:
                  init_nsvtor: 0x$entryAddr
            """.trimIndent())
            logger.info("Injected board.init_nsvtor: 0x$entryAddr")
        }
        
        targetConfig
    }

    private suspend fun runHoedurAdmission(configPath: Path): Pair<String, String> = coroutineScope {
        val basicConf = getTaskData("fuzzware_basic_conf") as FuzzwareGatewayConfig

        val hoedurDebugDir = Path.of(conf.hoedurRoot!!).resolve("target/debug")
        val hoedurBin = hoedurDebugDir.resolve("hoedur-arm")

        val originalBinPath = if (program!!.executableFormat == "Executable and Linking Format (ELF)") {
            fuzzwareConf.parent.resolve("parsedELF.bin")
        } else {
            usingFile.toPath()
        }

        val binTarget = outputDir.resolve(originalBinPath.fileName)
        originalBinPath.copyTo(binTarget, true)

        // The generated hoedur config refers to the firmware by the name it carries in the
        // fuzzware project (its original file name, e.g. 3349.bin), while the copy above uses
        // the name akiba imported it under (the database id, e.g. 5.bin).  Put the firmware
        // next to it under every name the config's memory map references, so the lookup cannot
        // miss.  (Fixtures whose imports kept the original names never hit this.)
        runCatching {
            val referenced = Regex("path:\\s*(\\S+)")
                .findAll(configPath.toFile().readText())
                .map { it.groupValues[1].trim().substringAfterLast('/') }
                .filter { it.isNotEmpty() && it != binTarget.fileName.toString() }
                .toSet()
            referenced.forEach { ref ->
                val dst = outputDir.resolve(ref)
                if (!dst.toFile().exists()) {
                    originalBinPath.copyTo(dst, true)
                    logger.info("Also copied the firmware as ${dst.fileName} (referenced by the config)")
                }
            }
        }.onFailure { logger.warn("Could not mirror the config-referenced firmware names: ${it.message}") }

        val runCmd = "export LD_LIBRARY_PATH=${hoedurDebugDir.absolutePathString()}:\$LD_LIBRARY_PATH && " +
                "${hoedurBin.absolutePathString()} " +
                "--config ${configPath.absolutePathString()} " +
                "run " + 
                "${binTarget.absolutePathString()}"

        logger.info("Executing Hoedur Admission Test (with LD_LIBRARY_PATH): $runCmd")
        
        val builder = ProcessBuilder(*conf.cmdPrefix.toTypedArray(), runCmd)
            .directory(outputDir.toFile())
            .redirectErrorStream(true)

        val process = builder.start()
        val admissionDetails = mutableListOf<String>()
        var finalSummaryReceived = false
        var overallStatus = "NOT_RUN"

        val logJob = launch {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    logger.trace("[Hoedur] $line")

                    if (line.contains("[ADMISSION]")) {
                        val detailLine = line.substringAfter("[ADMISSION]").trim()
                        admissionDetails.add(detailLine)
                        if (detailLine.contains("FAILED")) overallStatus = "FAILED_OVERALL"
                    } 
                    else if ((line.contains("PASSED") || line.contains("FAILED")) &&
                             (line.contains("Seed ") || line.contains("Zeroes") || line.contains("Ones") ||
                              line.contains("Shifting") || line.contains("base_input"))) {
                        val detailLine = line.trim()
                        admissionDetails.add(detailLine)
                        if (detailLine.contains("FAILED")) overallStatus = "FAILED_OVERALL"
                    }
                    else if (line.contains("All Admission Tests PASSED")) {
                        finalSummaryReceived = true
                        if (overallStatus != "FAILED_OVERALL") overallStatus = "PASSED"
                    }
                    else if (line.contains("Admission Test Failed")) {
                        overallStatus = "FAILED_OVERALL"
                    }
                }
            }
        }

        logJob.join()
        val exitCode = process.waitFor()

        val combinedDetail = if (admissionDetails.isEmpty()) {
            "No output captured. Exit: $exitCode"
        } else {
            admissionDetails.joinToString("\n")
        }

        val finalResult = when {
            overallStatus == "FAILED_OVERALL" -> "FAILED"
            finalSummaryReceived -> "PASSED"
            admissionDetails.any { it.contains("PASSED") } -> "PARTIAL_PASSED"
            exitCode != 0 -> "FAILED_EXIT_$exitCode"
            else -> "UNKNOWN"
        }

        Pair(finalResult, combinedDetail)
    }
}