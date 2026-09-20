package org.iotsplab.akiba.managers

import ghidra.GhidraJarApplicationLayout
import ghidra.app.plugin.processors.sleigh.SleighLanguageProvider
import ghidra.base.project.GhidraProject
import ghidra.framework.Application
import ghidra.framework.ApplicationConfiguration
import ghidra.framework.HeadlessGhidraApplicationConfiguration
import ghidra.program.model.lang.LanguageProvider
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.ConsoleAppender
import org.apache.logging.log4j.core.appender.FileAppender
import org.apache.logging.log4j.core.appender.RollingFileAppender
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy
import org.apache.logging.log4j.core.filter.ThresholdFilter
import org.apache.logging.log4j.core.layout.PatternLayout
import org.iotsplab.akiba.Main
import org.iotsplab.akiba.Main.Companion.mainConfigPath
import org.iotsplab.akiba.client.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager.KEY_SEPARATOR
import org.iotsplab.akiba.managers.ConfigManager.config
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.managers.ConfigManager.mergeConfigs
import org.iotsplab.akiba.managers.ConfigManager.projectConf
import org.iotsplab.akiba.utils.LoggerFactory
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.io.path.writeText

object WorkspaceManager: Closeable {
    lateinit var logRootDir: Path
    lateinit var taskConfigPath: File

    val globalLogger: Logger = LogManager.getRootLogger()

    private var proj: GhidraProject? = null
    val project: GhidraProject
        get() = proj!!
    lateinit var projectName: String
    // Global language provider, we only need one
    lateinit var languageProvider: LanguageProvider

    // Directory saving all binaries in database
    lateinit var binaryPath: Path
    // Directory saving all binaries processed in database
    lateinit var processedBinaryPath: Path

    private val ghidraLogFile: File = File("configs/ghidra_log.xml")

    private fun defaultProjectName(): String {
        return "Analysis-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
    }

    /**
     * Initialize the workspace, including:
     * 1. Confirm project name (need to consider restore mode)
     * 2. Initialize root logger (need to consider restore mode)
     * 3. Find all modules (.jar files in /process) and load dynamically
     * 4. Read and save all MD5 file checksums in database (if specified)
     * 5. If it's not restore mode, create workspace directory for log files and other files
     * 6. Initialize Ghidra application
     * 7. Initialize global language provider (for arch languages)
     * 8. Create Ghidra project if it's not restore mode
     */
    @OptIn(ExperimentalPathApi::class)
    fun initWorkspace(): Boolean {
        if (!initConfigs()) return false

        initRootLogger()

        initBinDirectories()

        if(!initDatabase()) return false

        if(!initGhidraProjectDir()) return false

        if(!initializeGhidra()) return false

        if(!initializeGhidraProject()) return false

        return true
    }

    fun initConfigs(): Boolean {
        Main.Mode.restore ?. let {
            // If we use restore mode, we need to get project name first
            // and then find the config according to the project name
            projectName = it

            logRootDir = Path.of("logs/$it")
            if (Path.of("logs/${it}/config.json").notExists()) {
                globalLogger.error("Failed to find config file")
                return false
            }

            taskConfigPath = logRootDir.toFile().resolve("config.json")

            globalLogger.info("Log directory: ${logRootDir.absolutePathString()}")

            if (taskConfigPath.exists()) {
                globalLogger.info("Main config path: ${taskConfigPath.absolutePath}")
                config = ConfigManager.loadGlobalConfig(taskConfigPath.absolutePath + KEY_SEPARATOR + "/main")
                mainConfigPath = taskConfigPath.absolutePath
            } else {
                globalLogger.info("Main config path: $mainConfigPath")
                config = ConfigManager.loadGlobalConfig()

                if (projectConf.overwriteLog && logRootDir.exists()) {
                    logRootDir.toFile().deleteRecursively()
                    logRootDir.createDirectories()
                }

                mergeConfigs(outputPath = taskConfigPath)
            }
            if (projectConf.mode == "fork" && projectConf.forkTo != projectName) {
                globalLogger.error("Unexpected error: fork target not equal to project name")
                return false
            } else if (projectConf.mode == "base" && projectConf.continueLog != projectName) {
                globalLogger.error("Unexpected error: project name not matched in config")
                return false
            }
            projectConf.mode = "new"
        } ?: run {
            // If we are not in restore mode, we need to initialize the config first
            // and then get the project name in the config
            globalLogger.info("Main config path: $mainConfigPath")
            config = ConfigManager.loadGlobalConfig()
            projectName =
                if (projectConf.mode == "fork") projectConf.forkTo ?: run {
                    globalLogger.error("Fork target not specified while in mode `fork`")
                    return false
                }
                else projectConf.name ?: defaultProjectName()

            if (projectConf.mode == "base") {
                projectConf.continueLog ?: run {
                    globalLogger.error("Continue log not specified while in mode `base`")
                    return false
                }
                logRootDir = Path.of("logs/${projectConf.continueLog}")
            } else
                logRootDir = Path.of("logs/$projectName")

            taskConfigPath = logRootDir.toFile().resolve("config.json")
            globalLogger.info("Log directory: ${logRootDir.absolutePathString()}")
            if (projectConf.overwriteLog && logRootDir.exists()) {
                logRootDir.toFile().deleteRecursively()
                logRootDir.createDirectories()
            }
            mergeConfigs(outputPath = taskConfigPath)
        }

        logRootDir.resolve("main_config.txt").writeText(mainConfigPath)

        return true
    }

    fun initRootLogger() {
        val consoleLevel: Level = Level.getLevel(config.globalConsoleLogLevel)
        val fileLevel: Level = Level.getLevel(config.globalFileLogLevel)
        val logFile: Path = logRootDir.resolve("Root.log")

        val context = LogManager.getContext(false) as LoggerContext
        val conf = context.configuration

        // Clear old RootLogger
        val rootLogger = conf.rootLogger
        rootLogger.appenders.map { it.key }.forEach {
            rootLogger.removeAppender(it)
        }

        val fileLayout = PatternLayout.newBuilder()
            .withPattern(
                "%d %-5level [%t] %c{1.} - %msg%n"
            )
            .withConfiguration(conf)
            .build()
        val consoleLayout = PatternLayout.newBuilder()
            .withPattern(
                "%d " +
                "%highlight{%-5level}" +
                "{ERROR=Bright RED,WARN=Bright Yellow,INFO=Bright Green,DEBUG=Bright Cyan,TRACE=Bright White} " +
                "%style{[%t]}{bright,magenta} %style{%c{1.}.%M(%L)}{cyan}: %msg%n"
            )
            .withConfiguration(conf)
            .build()

        // Console Appender
        if (consoleLevel != Level.OFF) {
            val consoleFilter = ThresholdFilter.createFilter(
                consoleLevel,
                null,
                null
            )

            val consoleAppender = ConsoleAppender.newBuilder()
                .setName("Console")
                .setLayout(consoleLayout)
                .setFilter(consoleFilter)
                .setTarget(ConsoleAppender.Target.SYSTEM_OUT)
                .setConfiguration(conf)
                .build()

            consoleAppender.start()
            conf.addAppender(consoleAppender)
            rootLogger.addAppender(consoleAppender, null, null)
        }

        // File Appender
        if (fileLevel != Level.OFF) {
            logFile.toFile().parentFile?.mkdirs()

            val fileFilter = ThresholdFilter.createFilter(
                fileLevel,
                null,
                null
            )

            val fileAppender = FileAppender.newBuilder()
                .setName("File")
                .withFileName(logFile.absolutePathString())
                .withAppend(true)
                .setLayout(fileLayout)
                .setFilter(fileFilter)
                .setConfiguration(conf)
                .build()

            fileAppender.start()
            conf.addAppender(fileAppender)
            rootLogger.addAppender(fileAppender, null, null)
        }

        // Root logger level
        rootLogger.level = Level.ALL

        // apply changes
        context.updateLoggers()
    }

    fun initBinDirectories() {
        // Initialize directories for saving binaries
        binaryPath = Path.of(mainConf.binariesRoot).resolve("original")
        processedBinaryPath = Path.of(mainConf.binariesRoot).resolve("processed")
        binaryPath.createDirectories()
        processedBinaryPath.createDirectories()
    }

    fun initDatabase(): Boolean {
        if (!DatabaseClient.testConnection()) {
            globalLogger.error("Database error, failed to initialize")
            return false
        }

        return runBlocking {
            config.username ?: run {
                globalLogger.error("User name not specified")
                return@runBlocking false
            }
            config.password ?: run {
                globalLogger.error("Password not specified")
                return@runBlocking false
            }
            config.usingInstance ?: run {
                globalLogger.error("Instance name not specified")
            }
            try {
                DatabaseClient.login(config.username!!, config.password!!)

                DatabaseClient.connectToInstance(config.usingInstance!!)
            } catch (e: Exception) {
                globalLogger.error("Failed to login to database: ${e.message}")
                return@runBlocking false
            }
            return@runBlocking true
        }
    }

    fun initGhidraProjectDir(): Boolean {
        // Create directory to save ghidra project
        if (!Files.exists(Path.of(projectConf.projectRoot))) {
            try {
                Files.createDirectory(Path.of(projectConf.projectRoot))
            } catch (_: IOException) {
                globalLogger.error("Failed to create ghidra project directory")
                return false
            }
        }
        return true
    }

    fun initializeGhidra(): Boolean {
        // Initialize the ghidra application
        if (!Application.isInitialized()) {
            val appConfig: ApplicationConfiguration = HeadlessGhidraApplicationConfiguration()
            appConfig.isInitializeLogging = false
            try {
                Application.initializeApplication(GhidraJarApplicationLayout(), appConfig)
                Application.initializeLogging(ghidraLogFile, ghidraLogFile)
            } catch (_: IOException) {
                globalLogger.error("Failed to initialize Ghidra application")
                return false
            }
        }

        // Initialize language provider
        try {
            languageProvider = SleighLanguageProvider.getSleighLanguageProvider()
        } catch (_: Exception) {
            globalLogger.error("Failed to build ghidra language provider for ${mainConf.processor}")
            return false
        }

        return true
    }

    @OptIn(ExperimentalPathApi::class)
    fun initializeGhidraProject(): Boolean {
        // Create a Ghidra project / Use an existing Ghidra project
        try {
            val grpFile = Path.of(projectConf.projectRoot, "${projectConf.name}.gpr")
            val repFile = grpFile.parent.resolve(grpFile.name.removeSuffix("gpr") + "rep")
            when (projectConf.mode) {
                "fork" -> {
                    // Check source project
                    if (grpFile.notExists() || !grpFile.isRegularFile() || !repFile.isDirectory()) {
                        globalLogger.error("Unable to fork project: source project not found")
                        return false
                    }
                    // Check target project
                    val forkGrpFile = projectConf.forkTo ?. let {
                        if (it.startsWith("/")) Path.of(it)
                        else Path.of(projectConf.projectRoot, "$it.gpr")
                    } ?: Path.of(projectConf.projectRoot, "${defaultProjectName()}.gpr")
                    val forkRepFile = forkGrpFile.parent.resolve(
                        forkGrpFile.name.removeSuffix("gpr") + "rep")
                    if (forkGrpFile.exists()) {
                        globalLogger.error("Unable to fork project: fork target already exists")
                        return false
                    }
                    // Copy project
                    globalLogger.info("Copying project file...")

                    if (projectConf.overwriteProject) {
                        if (forkGrpFile.exists())
                            forkGrpFile.deleteExisting()
                        if (forkRepFile.exists())
                            forkRepFile.deleteRecursively()
                        val lockFile = Path.of(
                            forkGrpFile.absolutePathString().removeSuffix(".gpr") + ".lock")
                        if (lockFile.exists())
                            lockFile.deleteExisting()
                        val lockFile2 = Path.of(lockFile.absolutePathString() + "~")
                        if (lockFile2.exists())
                            lockFile2.deleteExisting()
                    }

                    grpFile.copyTo(forkGrpFile)
                    repFile.copyToRecursively(forkRepFile, followLinks = true, overwrite = true)
                    globalLogger.info("Project file forked.")
                    // Open project
                    proj = GhidraProject.openProject(grpFile.parent.absolutePathString(), projectName)
                }
                "base" -> {
                    // Check source project
                    if (grpFile.notExists() || !grpFile.isRegularFile() || !repFile.isDirectory()) {
                        globalLogger.error("Unable to find base project: not found")
                        return false
                    }
                    // Open project
                    proj = GhidraProject.openProject(grpFile.parent.absolutePathString(), projectName)
                    // Determine log directory
                    projectConf.continueLog ?. let {
                        logRootDir = logRootDir.parent.resolve(it)
                    } ?: run {
                        globalLogger.error("Log directory not specified in base mode")
                        return false
                    }
                }
                "new" -> {
                    proj = if (grpFile.exists()) {
                        try {
                            GhidraProject.openProject(grpFile.parent.absolutePathString(), projectName)
                        } catch (_: Exception) {
                            // This project may be temporary, so we may need to create a new project
                            GhidraProject.createProject(
                                Path.of(projectConf.projectRoot).toAbsolutePath().toString(),
                                projectName, !projectConf.saveProject
                            )
                        }
                    } else {
                        GhidraProject.createProject(
                            Path.of(projectConf.projectRoot).toAbsolutePath().toString(),
                            projectName, !projectConf.saveProject
                        )
                    }
                }
                else -> {
                    globalLogger.error("Invalid ghidra project mode: ${projectConf.mode}," +
                            " it must be one of: fork, base, new")
                    return false
                }
            }
        } catch (e: IOException) {
            globalLogger.error("Failed to init Ghidra project: ${e.message}")
            return false
        }

        return true
    }

    override fun close() {
        proj ?. close()
    }
}