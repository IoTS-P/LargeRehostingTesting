package org.iotsplab.akiba.process

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import java.lang.IllegalArgumentException
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.notExists

@WithConfigClass(FirmlineConfig::class)
@IgnoreRuntimeTimeout
class Firmline (
    configPath: String? = null,
    id: Int,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    configPath = configPath,
    defaultConfig = FirmlineConfig(),
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    private val conf: FirmlineConfig
        get() = config as FirmlineConfig

    override suspend fun startProcess() {
        // Check if FirmRCA environment is ready
        if (!checkFirmlineEnv()) {
            logger.error("Checks for Firmline environment failed. There may be something wrong in your Firmline")
            failureSign = FAILED
            return
        }

        runFirmline()
    }

    private fun checkFirmlineEnv(): Boolean {
        synchronized(checkLock) {
            if (firmlineEnvCheckDone)
                return firmlineEnvReady

            // Check paths
            conf.firmlineRoot ?: run {
                firmlineEnvCheckDone = true
                throw IllegalArgumentException("Firmline Root Directory not specified")
            }
            conf.ghidraHome ?: {
                firmlineEnvCheckDone = true
                throw IllegalArgumentException("Ghidra Home not specified")
            }

            // Check python version
            val process = ProcessBuilder(
                *conf.cmdPrefix.toTypedArray(), "${conf.pythonRoot} --version")
                .redirectErrorStream(true).start()
            process.waitFor()
            process.inputStream.bufferedReader().readText().let {
                logger.trace(it)
                val lastLine = it.split("\n").last { line -> line.isNotEmpty() }
                if (!(lastLine.startsWith("Python 3.11") || lastLine.startsWith("Python 3.10"))) {
                    logger.error("Python env error, must be python 3.10 or 3.11")
                    firmlineEnvCheckDone = true
                    return false
                }
            }

            // Check database file
            if (Path.of("${conf.firmlineRoot}/fwdb.db").notExists()) {
                val process2 = ProcessBuilder(
                    *conf.cmdPrefix.toTypedArray(),
                    "cd ${conf.firmlineRoot} && sqlite3 fwdb.db < schema.sql")
                    .redirectErrorStream(true).start()
                process2.waitFor()
            }
            if (Path.of("${conf.firmlineRoot}/fwdb.db").notExists()) {
                logger.error("Failed to generate sqlite file, maybe `sqlite3` is not installed?")
                firmlineEnvCheckDone = true
                return false
            }

            // Check bgrep
            if (Path.of("/usr/local/opt/bgrep/usr/bin/bgrep").notExists()) {
                logger.error("bgrep not found")
                firmlineEnvCheckDone = true
                return false
            }

            firmlineEnvCheckDone = true
            firmlineEnvReady = true
            return true
        }
    }

    private suspend fun runFirmline() = coroutineScope {
        // Firmline will move the file into its own directory, so we need to create a copy
        val bin: Path = originalFile.toPath().let {
            val tempFile = it.parent.resolve(it.name + "_tmpforFirmline")
            it.copyTo(tempFile, true)
            tempFile
        }

        // Effective way to run Firmline in timeout, I tested many ways and this works in docker :)
        // But it cannot be stopped by sending Ctrl+C to Akiba :(
        val process = ProcessBuilder(
            *conf.cmdPrefix.toTypedArray(), "${conf.cmdPredo} && " +
                    "export GHIDRA_HOME=${conf.ghidraHome} && " +
                    "cd ${conf.firmlineRoot} && " +
                    "LD_LIBRARY_PATH=/usr/local/lib " +
                    "${conf.pythonRoot} pipeline.py ${bin.absolutePathString()}"
        ).redirectErrorStream(true).start()

        val outReader = launch {
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null)
                    logger.trace(line)
            }
        }
        outReader.join()
        process.waitFor()

        when (process.exitValue()) {
            in listOf(143, 1) -> {
                logger.warn("Firmline terminated by timeout")
                updateErr("radare2 timeout")
                failureSign = FAILED
            }
            0 -> logger.info("Firmline finished successfully")
            2 -> {
                logger.info("Firmline interrupted")
                updateErr("interrupted")
                failureSign = FAILED
            }
            else -> {
                logger.warn("Firmline failed returning ${process.exitValue()}")
                updateErr("unknown error returning ${process.exitValue()}")
                failureSign = FAILED
            }
        }

        bin.deleteIfExists()
    }

    companion object {
        private var firmlineEnvCheckDone: Boolean = false
        private var firmlineEnvReady: Boolean = false
        private val checkLock = Any()
    }
}