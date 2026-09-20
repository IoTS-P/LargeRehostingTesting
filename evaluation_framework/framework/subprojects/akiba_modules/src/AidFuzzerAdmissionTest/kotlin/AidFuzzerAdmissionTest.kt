package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.managers.ProgramManager
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.WithTableColumn
import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


data class AidFuzzerAdmissionTestConfig(
    val cmdPrefix: List<String> = listOf("/bin/bash", "-c"),
    val aidFuzzerRoot: String? = null,
    val simulatorName: String = "simulator_dbg",
    val projectRoot: String = "aidfuzzer_admission_test",
    val baseAddressSource: String = "firmxray_results.base_address",
    val ivtStartSource: String? = null
)

@WithTableColumn("result", "TEXT")
@WithTableColumn("detail", "TEXT")
@WithConfigClass(AidFuzzerAdmissionTestConfig::class)
@IgnoreRuntimeTimeout
class AidFuzzerAdmissionTest(
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

    private val conf: AidFuzzerAdmissionTestConfig get() = config as AidFuzzerAdmissionTestConfig
    
    private val outputDir: Path by lazy {
        Path.of(mainConf.binariesRoot)
            .resolve(conf.projectRoot)
            .resolve("$id")
    }

    override suspend fun startProcess() {
        if (conf.aidFuzzerRoot == null || !Path.of(conf.aidFuzzerRoot!!).isDirectory()) {
            throw IllegalArgumentException("AidFuzzer root directory not found: ${conf.aidFuzzerRoot}")
        }

        logger.info("Starting AidFuzzer Admission Test for ID: $id")

        ProgramManager.autoAnalyzeInTimeout(program!!, mainConf.autoAnalysisTimeout)

        if (outputDir.exists()) {
            outputDir.toFile().deleteRecursively()
        }
        outputDir.createDirectories()

        var currentProcess: Process? = null

        try {

            val fuzzwareConf = if (program!!.executableFormat == "Executable and Linking Format (ELF)") {
                callTaskAPI(FuzzwareGateway::generateFuzzwareConfigForELF, true) as Path
            } else {
                val baseAddr = getTaskData(conf.baseAddressSource) as Long
                val ivtAddr = conf.ivtStartSource?.let { getTaskData(it) as Long }
                    ?: program!!.memory.blocks.minBy { it.start }.start.offset
                callTaskAPI(FuzzwareGateway::generateFuzzwareConfig, baseAddr, ivtAddr, true, taskGlobalMonitor) as Path
            }

            val corpusDir = outputDir.resolve("corpus")
            corpusDir.createDirectories()

            val (result, detail, proc) = runAidFuzzerAdmission(fuzzwareConf, corpusDir)
            currentProcess = proc

            updateData(mapOf(
                "result" to result,
                "detail" to detail
            ))

            if (result != "PASSED") failureSign = FAILED

        } catch (e: Exception) {
            logger.error("AidFuzzer Admission Test Error: ${e.message}")
            updateErr("RUNTIME_ERROR: ${e.message}")
            failureSign = RUNTIME_ERROR
        } finally {

            currentProcess?.let { swipeAidFuzzerProcesses(logger, it, id, conf.projectRoot) }

            cleanupLargeSnapshots()
            
            logger.info("AidFuzzer post-process cleanup finished for ID: $id")
        }
    }


    private suspend fun runAidFuzzerAdmission(configPath: Path, corpusPath: Path): Triple<String, String, Process> = coroutineScope {
        val basicConf = getTaskData("fuzzware_basic_conf") as FuzzwareGatewayConfig
        
        val binDir = Path.of(conf.aidFuzzerRoot!!).resolve("bin")
        val libPath = binDir.absolutePathString()

        val envSetup = "export LD_LIBRARY_PATH=$libPath:\$LD_LIBRARY_PATH; " +
                "source /usr/share/virtualenvwrapper/virtualenvwrapper.sh; " + 
                "workon ${basicConf.venv}; "

        val mainCmd = "./iofuzz fuzz " +
                "\"${configPath.absolutePathString()}\" " +
                "./${conf.simulatorName} " +
                "-corpus \"${corpusPath.absolutePathString()}\""

        val fullRunCmd = envSetup + "setsid -w " + mainCmd

        val builder = ProcessBuilder(conf.cmdPrefix + fullRunCmd)
            .directory(binDir.toFile()) 
            .redirectErrorStream(true)

        val process = builder.start()
        val admissionDetails = mutableListOf<String>()
        var finalSummaryReceived = false
        var overallStatus = "NOT_RUN"

        val logJob = launch {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line!!
                        logger.info("[AidFuzzer] $currentLine")

                        if (currentLine.contains("[ADMISSION]")) {
                            val detailLine = currentLine.substringAfter("[ADMISSION]").trim()
                            admissionDetails.add(detailLine)
                            if (detailLine.contains("FAILED")) overallStatus = "FAILED_OVERALL"
                        } 
                        else if (currentLine.contains("All Admission Tests PASSED")) {
                            finalSummaryReceived = true
                            if (overallStatus != "FAILED_OVERALL") overallStatus = "PASSED"
                        }
                        else if (currentLine.contains("Admission Test Failed")) {
                            overallStatus = "FAILED_OVERALL"
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Log capture error: ${e.message}")
            }
        }

        val exitCode = process.waitFor()
        logJob.join() 

        val combinedDetail = if (admissionDetails.isEmpty()) {
            "No output captured. ExitCode: $exitCode. Check if venv '${basicConf.venv}' is valid."
        } else {
            admissionDetails.joinToString("\n")
        }

        val finalResult = when {
            overallStatus == "FAILED_OVERALL" -> "FAILED"
            finalSummaryReceived -> "PASSED"
            admissionDetails.any { it.contains("PASSED") } -> "PARTIAL_PASSED"
            else -> "FAILED_INIT"
        }

        Triple(finalResult, combinedDetail, process)
    }

    private fun cleanupLargeSnapshots() {
        val modelDir = outputDir.resolve("aidfuzzer").resolve("model")
        
        if (!modelDir.exists() || !modelDir.isDirectory()) return

        val snapshotPrefixes = listOf("state_irq_", "state_loop_", "state_mmio_")
        
        var deletedCount = 0
        var savedSpace = 0L

        modelDir.toFile().listFiles()?.forEach { file ->
            if (snapshotPrefixes.any { file.name.startsWith(it) }) {
                val fileSize = file.length()
                if (file.delete()) {
                    deletedCount++
                    savedSpace += fileSize
                }
            }
        }

        if (deletedCount > 0) {
            logger.info("Cleanup: Deleted $deletedCount snapshot files, released ${savedSpace / 1024 / 1024} MB.")
        }
    }

    companion object {
        private val cleanupMutex = Mutex()

        suspend fun swipeAidFuzzerProcesses(logger: Logger, p: Process, taskId: Int, projectPath: String) {
            cleanupMutex.withLock {
                logger.info("Performing deep cleanup for Task ID: $taskId")
                
                try {

                    val pattern = "/$taskId/"

                    val pgrepCmd = arrayOf("/bin/sh", "-c", "pgrep -f $pattern")
                    val pg = Runtime.getRuntime().exec(pgrepCmd)
                    val pids = pg.inputStream.bufferedReader().readLines()
                    pg.waitFor()

                    if (pids.isNotEmpty()) {
                        logger.info("Found ${pids.size} residual processes for Task $taskId. Terminating...")
                        pids.forEach { pid ->
                            try {
                                Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.trim())).waitFor()
                            } catch (e: Exception) {
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Cleanup failed: ${e.message}")
                }
            }
        }
    }
}