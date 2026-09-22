package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.managers.ProgramManager
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.reflect.full.memberFunctions
import java.nio.file.Files
import java.io.File 


data class FuzzwareAdmissionTestConfig(
    val cmdPrefix: List<String> = listOf("/bin/zsh", "-ic"),
    val cmdPredo: String = "source ~/.zshrc && source /usr/share/virtualenvwrapper/virtualenvwrapper.sh",
    val venv: String = "fuzzware",
    val projectRoot: String = "fuzzware_admission_test",
    val baseInputs: String? = null
)



@WithTableColumn("result", "TEXT") 
@WithTableColumn("detail", "TEXT") 
@WithConfigClass(FuzzwareAdmissionTestConfig::class)
@IgnoreRuntimeTimeout
class FuzzwareAdmissionTest (
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
    private val conf: FuzzwareAdmissionTestConfig
        get() = config as FuzzwareAdmissionTestConfig
    
    private lateinit var workDir: Path
    
    override suspend fun startProcess() {
        try {
            logger.info("Starting Fuzzware Admission Test for ID: $id")

            logger.info("Waiting for Ghidra auto-analysis to complete...")
            ProgramManager.autoAnalyzeInTimeout(program!!, mainConf.autoAnalysisTimeout)
            logger.info("Ghidra auto-analysis finished. Found ${program!!.functionManager.functionCount} functions.")

            val baseAddress = getTaskData("firmxray_results.base_address") as? Long
                ?: run {
                    logger.error("Base address from 'firmxray_results.base_address' not found. Check dbImports config.")
                    updateErr("base_address_not_found")
                    failureSign = FAILED
                    return
                }

            val gatewayClass = org.iotsplab.akiba.process.FuzzwareGateway::class
            val generateConfigFunc = gatewayClass.memberFunctions.firstOrNull { it.name == "generateFuzzwareConfig" }
                ?: run {
                    logger.error("Could not find TaskInterface function 'generateFuzzwareConfig' in FuzzwareGateway module.")
                    updateErr("gateway_api_not_found")
                    failureSign = FAILED
                    return
                }

            val ivtStart = program!!.memory.minAddress.offset
            val format = program!!.executableFormat

            logger.info("FIRMWARE_FORMAT_DETECTED: ID=$id, Format='$format'")

            val baseConfigPath: Path?

            if (format == "Executable and Linking Format (ELF)") {
                logger.info("Calling FuzzwareGateway.generateFuzzwareConfigForELF for ID=$id")
                val generateElfConfigFunc = gatewayClass.memberFunctions.firstOrNull { it.name == "generateFuzzwareConfigForELF" }
                    ?: run {
                        logger.error("Could not find TaskInterface function 'generateFuzzwareConfigForELF' in FuzzwareGateway module.")
                        updateErr("gateway_api_not_found")
                        failureSign = FAILED
                        return
                    }

                baseConfigPath = callTaskAPI(
                    generateElfConfigFunc,
                    false,
                    taskGlobalMonitor
                ) as? Path

            } else {
                logger.info("Calling FuzzwareGateway.generateFuzzwareConfig for ID=$id with baseAddress=0x${baseAddress.toString(16)}, ivtStart=0x${ivtStart.toString(16)}") 
                val generateConfigFunc = gatewayClass.memberFunctions.firstOrNull { it.name == "generateFuzzwareConfig" }
                    ?: run {
                        logger.error("Could not find TaskInterface function 'generateFuzzwareConfig' in FuzzwareGateway module.")
                        updateErr("gateway_api_not_found")
                        failureSign = FAILED
                        return
                    }

                baseConfigPath = callTaskAPI(
                    generateConfigFunc,
                    baseAddress,
                    ivtStart,
                    false,
                    taskGlobalMonitor
                ) as? Path
            }

            if (baseConfigPath == null) {
                logger.error("FuzzwareGateway did not return a valid Path.")
                updateErr("config_generation_failed")
                failureSign = FAILED
                return
            }
            
            workDir = baseConfigPath.parent

            val (admissionResult, admissionDetail) = runAdmissionTest(baseConfigPath)

            logger.info("Admission Test Result for ID: $id -> $admissionResult ($admissionDetail)")
            updateData(mapOf(
                "result" to admissionResult,
                "detail" to admissionDetail
            ))

            if (admissionResult.contains("FAILED")) {
                failureSign = FAILED
            }

        } catch (e: Exception) {
            logger.error("Failed to run Fuzzware Admission Test: ${e.message}")
            e.printStackTrace()
            updateErr("RUNTIME_ERROR: ${e.message}")
            failureSign = RUNTIME_ERROR
        }
    }

    private suspend fun runAdmissionTest(baseConfigPath: Path): Pair<String, String?> = coroutineScope {

        val admissionDetails = mutableListOf<String>()
        var overallStatus = "NOT_RUN"
        var finalSummaryReceived = false

        try {
            val cmd = "${conf.cmdPredo} && workon ${conf.venv} && " +
                    "fuzzware pipeline --runtime-config-name \"${baseConfigPath.absolutePathString()}\" -p pipeline"

            logger.info("Executing Fuzzware with original config: $cmd")

            val builder = ProcessBuilder(*conf.cmdPrefix.toTypedArray(), cmd)
                .directory(workDir.toFile()) 
                .redirectErrorStream(true)

            val process: Process = builder.start()

            val outReader = launch {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        logger.trace(line)

                        if (line.contains("[ADMISSION]")) {
                            val detailLine = line.substringAfter("[ADMISSION]").trim()
                            admissionDetails.add(detailLine)
                            logger.info("Detected Seed Result: $detailLine")
                        } 

                        else if ((line.contains("PASSED") || line.contains("FAILED")) &&
                                 (line.contains("Seed ") || line.contains("Zeroes") || line.contains("Ones") ||
                                  line.contains("Shifting") || line.contains("base_input"))) {
                            val detailLine = line.trim()
                            admissionDetails.add(detailLine)
                            if (detailLine.contains("FAILED")) overallStatus = "FAILED_OVERALL"
                            logger.info("Detected Seed Result: $detailLine")
                        }
                        else if (line.contains("All initial seeds passed admission test")) {
                            finalSummaryReceived = true
                            if (overallStatus != "FAILED_OVERALL") overallStatus = "PASSED"
                        } 
                        else if (line.contains("Firmware admission test failed")) {
                            overallStatus = "FAILED_OVERALL"
                        }
                    }
                }
            }

            outReader.join()
            val exitCode = process.waitFor()
            
            val combinedDetail = if (admissionDetails.isEmpty()) {
                "No individual seed results captured."
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

            return@coroutineScope Pair(finalResult, combinedDetail)

        } catch (e: Exception) {
            logger.error("Error during Fuzzware admission run: ${e.message}", e)
            return@coroutineScope Pair("FRAMEWORK_ERROR", e.message)
        }
    }
}