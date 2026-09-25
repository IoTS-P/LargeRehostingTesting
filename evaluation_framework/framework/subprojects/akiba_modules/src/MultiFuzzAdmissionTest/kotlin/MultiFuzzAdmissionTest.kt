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
import java.nio.file.Path
import kotlin.io.path.*

data class MultiFuzzAdmissionTestConfig(
    val cmdPrefix: List<String> = listOf("/bin/zsh", "-ic"),
    val cmdPredo: String = "source ~/.zshrc",
    val projectRoot: String = "multifuzz_admission_test",
    val multiFuzzRoot: String? = null,
    val cargoPath: String = "cargo",
    val baseAddressSource: String = "firmxray_results.base_address",
    val ivtStartSource: String? = null
)

@WithTableColumn("result", "TEXT")
@WithTableColumn("detail", "TEXT")
@WithConfigClass(MultiFuzzAdmissionTestConfig::class)
@IgnoreRuntimeTimeout
class MultiFuzzAdmissionTest(
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

    private val conf: MultiFuzzAdmissionTestConfig get() = config as MultiFuzzAdmissionTestConfig
    
    private val outputDir: Path by lazy {
        Path.of(mainConf.binariesRoot)
            .resolve(conf.projectRoot)
            .resolve("$id")
    }
        
    private lateinit var fuzzwareConf: Path

    override suspend fun startProcess() {
        if (conf.multiFuzzRoot == null || !Path.of(conf.multiFuzzRoot!!).isDirectory()) {
            throw IllegalArgumentException("MultiFuzz root directory not found: ${conf.multiFuzzRoot}")
        }

        logger.info("Starting MultiFuzz Admission Test for ID: $id")

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
                    ?: program!!.memory.blocks.minByOrNull { it.start }?.start?.offset
                    ?: program!!.memory.minAddress.offset
                callTaskAPI(FuzzwareGateway::generateFuzzwareConfig, baseAddr, ivtAddr, true, taskGlobalMonitor) as Path
            }

            if (!fuzzwareConf.exists()) {
                throw IllegalStateException("Generated Fuzzware config not found at ${fuzzwareConf.absolutePathString()}")
            }

            val (result, detail) = runMultiFuzzAdmission(fuzzwareConf)

            updateData(mapOf(
                "result" to result,
                "detail" to detail
            ))

            if (result != "PASSED") failureSign = FAILED

        } catch (e: Exception) {
            logger.error("MultiFuzz Admission Test Error: ${e.message}", e)
            updateErr("RUNTIME_ERROR: ${e.message}")
            failureSign = RUNTIME_ERROR
        }
    }

    private suspend fun runMultiFuzzAdmission(configPath: Path): Pair<String, String> = coroutineScope {
        val multiFuzzRootDir = Path.of(conf.multiFuzzRoot!!)
        val multiFuzzReleaseDir = multiFuzzRootDir.resolve("target/release")
        val multiFuzzBin = multiFuzzReleaseDir.resolve("hail-fuzz")

        if (!multiFuzzBin.exists()) {
            throw IllegalStateException("MultiFuzz binary not found at ${multiFuzzBin.absolutePathString()}")
        }

        val runCmd = "${conf.cmdPredo} && " +
                "export WORKDIR=\"${outputDir.absolutePathString()}\" && " +
                "export LD_LIBRARY_PATH=\"${multiFuzzReleaseDir.absolutePathString()}:\$LD_LIBRARY_PATH\" && " +
                "export GHIDRA_SRC=\"${multiFuzzRootDir.resolve("ghidra").absolutePathString()}\" && " +
                "${multiFuzzBin.absolutePathString()} " +
                "\"${configPath.absolutePathString()}\""

        logger.info("Executing MultiFuzz Admission Test (Strict Env): $runCmd")
        
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
                    logger.info("[MultiFuzz Trace] $line")

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
                    else if (line.contains("All 3 admission seeds passed!")) {
                        finalSummaryReceived = true
                        if (overallStatus != "FAILED_OVERALL") overallStatus = "PASSED"
                    }
                    else if (line.contains("Firmware Admission Failed")) {
                        overallStatus = "FAILED_OVERALL"
                    }
                }
            }
        }

        logJob.join()
        val exitCode = process.waitFor()

        val combinedDetail = if (admissionDetails.isEmpty()) {
            "No seed results. Exit Code: $exitCode. Check trace logs for 'error while loading' or 'Ghidra' errors."
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