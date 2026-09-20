package org.iotsplab.akiba.utils

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.ConsoleAppender
import org.apache.logging.log4j.core.appender.FileAppender
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.layout.PatternLayout
import java.util.*

object LoggerFactory {
    fun getLogger(name: String, appender: List<Appender>, logLevels: List<Level>): Logger {
        val context = LoggerContext.getContext()

        require(appender.size == logLevels.size) { "unmatched appender and log level sizes" }
        val newLoggerConfig = LoggerConfig.createLogger(
            false,
            Level.TRACE,
            UUID.randomUUID().toString(),
            "true", arrayOf(), null,
            context.configuration, null
        )

        for (i in appender.indices) {
            if (!appender[i].isStarted) appender[i].start()
            newLoggerConfig.addAppender(appender[i], logLevels[i], null)
        }

        context.configuration.addLogger(name, newLoggerConfig)
        return LogManager.getLogger(name)
    }

    fun getShortConsoleLogger(name: String, level: Level = Level.INFO): Logger {
        return getLogger(name,
            listOf(
                ConsoleAppender.newBuilder()
                    .setLayout(getShortConsoleLoggerLayout())
                    .setName("console")
                    .build()
            ),
            listOf(level))
    }

    fun getShortConsoleFileLogger(name: String, filePath: String,
                                  consoleLevel: Level = Level.INFO, fileLevel: Level = Level.INFO): Logger {
        return getLogger(name,
            listOf(
                ConsoleAppender.newBuilder()
                    .setLayout(getShortConsoleLoggerLayout())
                    .setName("console")
                    .build(),
                FileAppender.newBuilder()
                    .setLayout(getShortFileLoggerLayout())
                    .setName("file")
                    .withFileName(filePath)
                    .build()
            ),
            listOf(consoleLevel, fileLevel))
    }

    fun getConsoleLoggerLayout(): PatternLayout {
        return PatternLayout.newBuilder()
            .withPattern("%d %highlight{%-5level}" +
                    "{ERROR=Bright RED, WARN=Bright Yellow, INFO=Bright Green, DEBUG=Bright Cyan, TRACE=Bright White}" +
                    " %style{[%t]}{bright,magenta} %style{%c{1.}.%M(%L)}{cyan}: %msg%n")
            .build()
    }

    fun getFileLoggerLayout(): PatternLayout {
        return PatternLayout.newBuilder()
            .withPattern("%d %-5level [%t] %c{1.}.%M(%L): %msg%n")
            .build()
    }

    fun getShortConsoleLoggerLayout(): PatternLayout {
        return PatternLayout.newBuilder()
            .withPattern("%d %highlight{%-5level}" +
                    "{ERROR=Bright RED, WARN=Bright Yellow, INFO=Bright Green, DEBUG=Bright Cyan, TRACE=Bright White}" +
                    " : %msg%n")
            .build()
    }

    fun getShortFileLoggerLayout(): PatternLayout {
        return PatternLayout.newBuilder()
            .withPattern("%d %-5level : %msg%n")
            .build()
    }
}
