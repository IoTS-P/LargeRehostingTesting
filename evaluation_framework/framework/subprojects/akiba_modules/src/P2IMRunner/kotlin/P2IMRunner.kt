package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.WithTableColumn
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

data class P2IMRunnerConfig(
    val timeoutSeconds: Long = 3600,
    val pythonPath: String = "python"
)

@WithTableColumn("mcu_used", "TEXT")
@WithTableColumn("crashes_found", "INTEGER")
@WithTableColumn("hangs_found", "INTEGER")
@WithTableColumn("bbl_coverage", "INTEGER")
@WithTableColumn("execution_status", "TEXT")
@WithConfigClass(P2IMRunnerConfig::class)
@IgnoreRuntimeTimeout
class P2IMRunner(
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = "p2im_fuzzing_results"
) : AkibaModule(
    id = id,
    configPath = configPath,
    defaultConfig = P2IMRunnerConfig(),
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val conf: P2IMRunnerConfig
        get() = config as P2IMRunnerConfig

    override suspend fun startProcess() {
        val workingDirStr = getTaskData("p2im_working_dir") as? String
        val gatewayConf = getTaskData("p2im_basic_conf") as? P2IMGatewayConfig
        val mcuUsed = getTaskData("p2im_guessed_mcu") as? String

        if (workingDirStr == null || gatewayConf == null) {
            logger.error("P2IMGateway data not found! Make sure P2IMGateway runs first.")
            failureSign = FAILED
            return
        }
        val workingDir = Path.of(workingDirStr)

        val zeroDir = workingDir.resolve("0")
        if (zeroDir.exists()) {
            zeroDir.toFile().deleteRecursively()
        }

        logger.info("Starting P2IM Fuzzing for ID: $id. Timeout: ${conf.timeoutSeconds}s")
        val fuzzCmd = listOf(conf.pythonPath, "${gatewayConf.p2imRoot}/model_instantiation/fuzz.py", "-c", "fuzz.cfg")
        val processBuilder = ProcessBuilder(fuzzCmd)
        processBuilder.directory(workingDir.toFile())

        val pythonBinDir = java.io.File(conf.pythonPath).parent
        val currentPath = processBuilder.environment()["PATH"] ?: ""
        processBuilder.environment()["PATH"] = "$pythonBinDir:$currentPath"

        processBuilder.environment()["AFL_SKIP_CPUFREQ"] = "1"
        val process = processBuilder.start()

        kotlin.concurrent.thread(isDaemon = true) {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line?.contains("PROGRAM ABORT") == true || line?.contains("crash") == true) {
                            logger.warn("AFL Output: $line")
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        val finishedNormally = process.waitFor(conf.timeoutSeconds, TimeUnit.SECONDS)
        var execStatus = "UNKNOWN"

        if (!finishedNormally) {
            logger.info("Timeout reached (Normal behavior for fuzzing). Destroying P2IM and AFL process tree...")
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor()
            execStatus = "SUCCESS_TIMEOUT"
        } else {
            logger.warn("P2IM aborted early. This usually indicates an unsupported board/MCU configuration.")
            execStatus = "FAILED_EARLY_ABORT"
            failureSign = FAILED
            updateErr("AFL aborted early. Suspected QEMU crash due to memory/MMIO misalignment with real-world firmware.")
        }

        val crashesDir = workingDir.resolve("outputs/crashes").toFile()
        val hangsDir = workingDir.resolve("outputs/hangs").toFile()

        val crashCount = if (crashesDir.exists()) maxOf(0, (crashesDir.list()?.size ?: 1) - 1) else 0
        val hangCount = if (hangsDir.exists()) maxOf(0, (hangsDir.list()?.size ?: 1) - 1) else 0

        var bblCov = -1
        try {
            val lastModelJson = findLastPeripheralModel(workingDir.toFile())
            if (lastModelJson != null) {
                logger.info("Calculating coverage using model: $lastModelJson")
                val covCmd = listOf(
                    conf.pythonPath, "${gatewayConf.p2imRoot}/utilities/coverage/cov.py", 
                    "-c", "fuzz.cfg", "--model-if", lastModelJson
                )
                ProcessBuilder(covCmd).directory(workingDir.toFile()).start().waitFor()

                val bblCntFile = workingDir.resolve("coverage/bbl_cnt").toFile()
                if (bblCntFile.exists()) {
                    bblCov = bblCntFile.readText().trim().toIntOrNull() ?: -1
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to calculate coverage: ${e.message}")
        }

        logger.info("P2IM Runner Finished! Crashes: $crashCount, Hangs: $hangCount, BBLs: $bblCov")
        updateData(mapOf(
            "mcu_used" to mcuUsed,
            "crashes_found" to crashCount,
            "hangs_found" to hangCount,
            "bbl_coverage" to bblCov,
            "execution_status" to execStatus
        ))
    }

    private fun findLastPeripheralModel(workingDir: File): String? {
        val modelFiles = mutableListOf<File>()
        workingDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name != "inputs" && dir.name != "outputs" && dir.name != "coverage") {
                val modelFile = File(dir, "peripheral_model.json")
                if (modelFile.exists()) {
                    modelFiles.add(modelFile)
                }
            }
        }
        return modelFiles.maxByOrNull { it.lastModified() }?.absolutePath
    }
}