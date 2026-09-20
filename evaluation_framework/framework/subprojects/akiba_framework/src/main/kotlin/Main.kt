package org.iotsplab.akiba

import kotlinx.coroutines.*
import org.fusesource.jansi.AnsiConsole
import org.iotsplab.akiba.client.database.DatabaseClient
import org.iotsplab.akiba.managers.*
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.managers.WorkspaceManager.project
import org.iotsplab.akiba.subcommands.BackupInstance
import org.iotsplab.akiba.subcommands.CreateInstance
import org.iotsplab.akiba.subcommands.RestoreInstance
import picocli.CommandLine
import picocli.CommandLine.ArgGroup
import sun.misc.Signal
import java.util.*
import kotlin.system.exitProcess

@CommandLine.Command(
    name = "Akiba",
    mixinStandardHelpOptions = true,
    description = ["A batch workflow framework of Ghidra"],
    subcommands = [
        CreateInstance::class,
        BackupInstance::class,
        RestoreInstance::class
    ]
)
class Main : Runnable {
    override fun run() = runBlocking {
        // Install colorful ANSI console, it seems that without it, the console can really print colors
        AnsiConsole.systemInstall()

        // Initialize workspace
        if (!WorkspaceManager.initWorkspace()) {
            globalLogger.error("Failed to initialize workspace")
            return@runBlocking
        }

        // If it's importing mode, import files and exit
        try {
            importConfig ?.let {
                ImportManager.import()
                return@runBlocking
            }
        } catch (e: Exception) {
            globalLogger.error("Import interrupted")
            e.printStackTrace()
            finally()
            return@runBlocking
        }

        // Initialize programs
        if (!ProgramManager.init()) {
            globalLogger.error("Initialization failed")
            finally()
            return@runBlocking
        }

        // Import all binaries in sequence, when a binary file is analyzed, delete its import.
        ProgramManager.startProcess(project)

        project.close()

        finally()
    }

    companion object {
        class MainModeGroup {
            @CommandLine.Option(
                names = ["-r", "--restore"],
                description = ["Restore mode for a previous task"],
                required = false
            )
            var restore: String? = null

            @CommandLine.Option(
                names = ["-i", "--import"],
                description = ["Import binaries with a config file"],
                required = false
            )
            var importConfig: String? = null
        }

        @ArgGroup(exclusive = true, multiplicity = "0..1")
        var Mode = MainModeGroup()
        val restore: String?
            get() = Mode.restore
        val importConfig: String?
            get() = Mode.importConfig

        @CommandLine.Option(
            names = ["-c", "--main-config"],
            description = ["Main configuration file path"],
            required = false
        )
        @JvmStatic
        var mainConfigPath: String = "configs/config.yaml${ConfigManager.KEY_SEPARATOR}main"

        @CommandLine.Option(
            names = ["--venv"],
            description = ["Global Python venv (virtual environment) root directory"],
            required = false
        )
        @JvmStatic
        var globalVenv: String = "akiba-venv"

        class RestoreModeGroup {
            @CommandLine.Option(
                names = ["-f", "--fail-only"],
                description = ["Only process failed programs，if -r not specified, this option will be ignored"],
                required = false
            )
            var restoreFailedOnly: Boolean = false

            @CommandLine.Option(
                names = ["-e", "--error-only"],
                description = ["Only process programs with error, if -r not specified, this option will be ignored"],
                required = false
            )
            var restoreErrorOnly: Boolean = false
        }

        @ArgGroup(exclusive = true, multiplicity = "0..1")
        var restoreGroup = RestoreModeGroup()
        val restoreFailedOnly: Boolean
            get() = restoreGroup.restoreFailedOnly
        val restoreErrorOnly: Boolean
            get() = restoreGroup.restoreErrorOnly

        @JvmStatic
        fun main(args: Array<String>): Unit = runBlocking {
            // Set timezone
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))

            globalLogger.info("Max runtime memory: ${Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0}MB")

            // Register signal handler
            globalLogger.debug("Registering signal handler")
            listOf("INT", "TERM").forEach { signalName ->
                Signal.handle(Signal(signalName)) {
                    interruptHandler()
                }
            }

            try {
                exitProcess(CommandLine(Main::class.java).execute(*args))
            } catch (_: Exception) {
                finally()
            }
        }

        @JvmStatic
        fun interruptHandler() {
            globalLogger.error("Interrupted by user")
            try {
                finally()

            } catch (_: Exception) {}
            globalLogger.info("Tested ${ProgramManager.successCount + ProgramManager.failureCount} cases")
            globalLogger.info("Success: ${ProgramManager.successCount}")
            globalLogger.info("Failed: ${ProgramManager.failureCount}")
            exitProcess(1)
        }

        fun finally() {
            globalLogger.info("Exiting...")
            try {
                DatabaseClient.logout()
                WorkspaceManager.close()
                AnsiConsole.systemUninstall()
            } catch (_: Exception) {}
        }
    }
}