package org.iotsplab.akiba.dbDaemon

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.ConsoleAppender
import org.apache.logging.log4j.core.appender.RollingFileAppender
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy
import org.apache.logging.log4j.core.filter.ThresholdFilter
import org.apache.logging.log4j.core.layout.PatternLayout
import org.iotsplab.akiba.dbDaemon.operations.PGInstances
import picocli.CommandLine
import sun.misc.Signal
import java.io.File
import java.io.OutputStreamWriter
import java.nio.file.Path
import java.util.TimeZone
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.system.exitProcess

class DatabaseDaemon : Runnable {
    @CommandLine.Option(
        names = ["-c"],
        description = ["Path to config file"],
        defaultValue = "resources/config.json"
    )
    var configFile: String? = null

    @CommandLine.Option(
        names = ["--skip-init", "--no-skip-init"],
        description = ["Skip initialization of local database (initialize_pg_local.sh)"]
    )
    var skipInit: Boolean = false

    override fun run() {
        // Set timezone
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))

        config = parseConfig()

        initLogger()

        globalLogger.info("Max runtime memory: ${Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0}MB")

        listOf("INT", "TERM").forEach { signalName ->
            Signal.handle(Signal(signalName)) {
                interruptHandler()
            }
        }

        PGInstances.initialize()
        DbDaemonServer.startServer()

        // hang
        while (true) {}
    }

    private fun parseConfig(): Configs {
        configFile ?: return Configs()

        return try {
            val module = SimpleModule()
            val mapper = jacksonObjectMapper().registerModule(module)
            val conf: Configs = mapper.readValue(File(configFile!!), Configs::class.java) ?: run {
                globalLogger.error("Failed to load configs.json")
                exitProcess(1)
            }
            conf
        } catch (e: Exception) {
            globalLogger.error("Exception occurred while loading configs.json: ${e.message}")
            exitProcess(1)
        }
    }

    companion object {
        @JvmStatic
        val globalLogger: Logger = LogManager.getRootLogger()

        lateinit var config: Configs

        @JvmStatic
        fun main(args: Array<String>): Unit {
            CommandLine(DatabaseDaemon()).execute(*args)
        }

        @JvmStatic
        fun interruptHandler() {
            globalLogger.info("Interrupted by user, stopping server in no more than 3 seconds...")

            DbDaemonServer.stopServer()

            globalLogger.info("Server stopped")

            // Shut down all instances and write instance data into file
            PGInstances.shutdownAllInstances()

            val instanceFile = Path.of("${System.getProperty("user.home")}/.akiba/instances.json")
            if (!instanceFile.exists()) {
                instanceFile.parent.createDirectories()
                instanceFile.createFile()
            }
            instanceFile.toFile().writeText(jacksonObjectMapper().writeValueAsString(PGInstances.instances))

            exitProcess(0)
        }

        @JvmStatic
        fun initLogger(
            consoleLevel: Level = Level.getLevel(config.consoleLogLevel),
            fileLevel: Level = Level.getLevel(config.fileLogLevel),
            logFile: String = "~/.akiba/daemon.log") {
            val context = LogManager.getContext(false) as LoggerContext
            val config = context.configuration

            // Clear old RootLogger
            val rootLogger = config.rootLogger
            rootLogger.appenders.map { it.key }.forEach {
                rootLogger.removeAppender(it)
            }

            val fileLayout = PatternLayout.newBuilder()
                .withPattern(
                    "%d %-5level [%t] %c{1.} - %msg%n"
                )
                .withConfiguration(config)
                .build()
            val consoleLayout = PatternLayout.newBuilder()
                .withPattern(
                    "%d " +
                    "%highlight{%-5level}" +
                    "{ERROR=Bright RED,WARN=Bright Yellow,INFO=Bright Green,DEBUG=Bright Cyan,TRACE=Bright White} " +
                    "%style{[%t]}{bright,magenta} %style{%c{1.}.%M(%L)}{cyan}: %msg%n"
                )
                .withConfiguration(config)
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
                    .setConfiguration(config)
                    .build()

                consoleAppender.start()
                config.addAppender(consoleAppender)
                rootLogger.addAppender(consoleAppender, null, null)
            }

            // File Appender
            if (fileLevel != Level.OFF) {
                val resolvedPath = expandHome(logFile)
                File(resolvedPath).parentFile?.mkdirs()

                val fileFilter = ThresholdFilter.createFilter(
                    fileLevel,
                    null,
                    null
                )

                val rolloverStrategy = DefaultRolloverStrategy.newBuilder()
                    .withMax("3")
                    .withConfig(config)
                    .build()

                val fileAppender = RollingFileAppender.newBuilder()
                    .setName("File")
                    .withFileName(resolvedPath)
                    .withFilePattern("$resolvedPath.%i")
                    .withAppend(true)
                    .withStrategy(rolloverStrategy)
                    .setLayout(fileLayout)
                    .withPolicy(SizeBasedTriggeringPolicy.createPolicy("100KB"))
                    .setFilter(fileFilter)
                    .setConfiguration(config)
                    .build()

                fileAppender.start()
                config.addAppender(fileAppender)
                rootLogger.addAppender(fileAppender, null, null)
            }

            // Root logger level
            rootLogger.level = Level.ALL

            // apply changes
            context.updateLoggers()
        }


        private fun expandHome(path: String): String {
            return if (path.startsWith("~")) {
                System.getProperty("user.home") + path.substring(1)
            } else {
                path
            }
        }
    }
}