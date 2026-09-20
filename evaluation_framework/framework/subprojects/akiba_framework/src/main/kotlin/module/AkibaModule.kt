package org.iotsplab.akiba.module

import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ghidra.program.model.listing.Program
import ghidra.util.exception.CancelledException
import ghidra.util.task.TaskMonitorAdapter
import ghidra.util.task.TimeoutTaskMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.ConsoleAppender
import org.apache.logging.log4j.core.appender.FileAppender
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.layout.PatternLayout
import org.iotsplab.akiba.client.database.DatabaseClient
import org.iotsplab.akiba.managers.BinaryMetadata
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.ConfigManager.KEY_SEPARATOR
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.managers.ConfigManager.parseModuleConfig
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.utils.*
import org.iotsplab.akiba.utils.CoroutineTaskMonitor
import org.iotsplab.akiba.utils.CoroutineTaskMonitor.Companion.asCoroutineAware
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.coroutines.Continuation
import kotlin.coroutines.coroutineContext
import kotlin.io.path.absolutePathString
import kotlin.io.path.notExists
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

/**
 * Abstract class for auto-processing tasks, providing basic functionality for loading configurations and logging.
 * It also defines a template for starting processes and handling timeouts.
 *
 * @param configPath Path to the configuration file, optional.
 * @param defaultConfig The default configuration, optional.
 * @param id The unique identifier for the program, required.
 * @param program The Ghidra program, required.
 * @param properties Other properties of the program, optional.
 * @param consoleLogLevel The log level for the console, optional and default INFO.
 * @param fileLogLevel The log level for the file, optional and default INFO.
 */
abstract class AkibaModule (
    private val configPath: String? = null,
    private val defaultConfig: Any? = null,
    val id: Int = -1,
    protected val program: Program? = null,
    protected val properties: Map<String, String?> = mapOf(),
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null,
) : AutoCloseable {
    val logDir: Path = WorkspaceManager.logRootDir.resolve(id.toString())
    var logger: Logger = initLogger(logDir, consoleLogLevel, fileLogLevel)
    private val hasTable: Boolean = this::class.annotations.none { it is DoNotCreateTable }
    protected var config: Any? = null
    private var loggerConfig: LoggerConfig? = null
    // failureSign is used to skip all latter tasks. If it is true, then all latter tasks will be skipped.
    var failureSign: Int = SUCCESS

    protected val dbTableName: String =
        tableName ?: "${pascalToSnake(this.javaClass.simpleName)}_results"
    protected val allDefinedDbColumns: Map<String, String> = this.javaClass.kotlin.annotations
        .filter{ it is WithTableColumn }
        .map{ it as WithTableColumn }
        .associate{ it.name to it.type }

    val configClass: KClass<*>?
        get() = this::class.findAnnotation<WithConfigClass>() ?.clazz

    val originalFile: File = File("${mainConf.binariesRoot}/original/$id.bin")
    val processedFile: File? = File("${mainConf.binariesRoot}/processed/$id.bin").let {
        if (it.exists()) it else null
    }
    val usingFile: File = processedFile ?: originalFile

    lateinit var taskGlobalMonitor: CoroutineTaskMonitor

    /**
     * Initializes the logger and attempts to load the configuration if a configuration path and class are provided.
     */
    init {
        // Initialize config
        try {
            configClass ?. let {
                config = if (configPath != null && configClass != null) {
                    val confPathAndKey = if (configPath.startsWith(KEY_SEPARATOR.repeat(2))) {
                        ConfigManager.mainConfigFile.path + KEY_SEPARATOR + configPath.substring(2)
                    } else configPath

                    parseModuleConfig(confPathAndKey, getDeserializerMapper(this::class), it.java)
                } else
                    defaultConfig
            }
        } catch (e: Exception) {
            logger.error("Failed to load config file $configPath")
            logger.error(e.message)
            e.printStackTrace()
            exitProcess(1)
        }
    }

    protected suspend fun callTaskAPI(function: KFunction<*>, vararg args: Any?): Any? {
        return if (function.isSuspend)
            coroutineContext[ModuleContext]!!.call(function, *args,
                Continuation<Any?>(coroutineContext[ModuleContext.Key]!!) { result ->
                    result.getOrThrow()
                }
            )
        else
            coroutineContext[ModuleContext.Key]!!.call(function, *args)
    }

    @Throws(NoSuchElementException::class)
    suspend fun getTaskData(key: String?): Any? {
        return if (key == null)
            coroutineContext[ModuleContext.Key]!!.data
        else
            coroutineContext[ModuleContext.Key]!!.data.getValue(key)
    }

    suspend fun setTaskData(key: String, value: Any?) {
        coroutineContext[ModuleContext.Key]!!.data[key] = value
    }

    suspend fun getMetadata(): BinaryMetadata {
        return coroutineContext[ModuleContext.Key]!!.metadata
    }

    fun initLogger(logDir: Path, consoleLogLevel: Level = Level.INFO,
                             fileLogLevel: Level = Level.WARN): Logger {
        if (logDir.notExists())
            Files.createDirectories(logDir)

        val context = LoggerContext.getContext()
        val rootConfig = context.configuration.rootLogger
        val consoleConfig = rootConfig.appenders["Console"]!!
        val patternLayout = consoleConfig.layout
        val loggerFileName = this.javaClass.simpleName
        val loggerName = "$loggerFileName--$id"

        // Create logger config
        val newLoggerConfig = LoggerConfig.createLogger(
            false,
            if (consoleLogLevel > fileLogLevel) { consoleLogLevel } else { fileLogLevel },
            UUID.randomUUID().toString(),
            "true", arrayOf(), null,
            context.configuration, null
        )

        // Create console appender
        val individualConsoleAppender = ConsoleAppender.newBuilder()
            .setLayout(patternLayout)
            .setName("Console-${this.javaClass.simpleName}-$id")
            .build()
        individualConsoleAppender.start()
        newLoggerConfig.addAppender(individualConsoleAppender, consoleLogLevel, null)

        loggerConfig = newLoggerConfig

        // Create file appender
        if (fileLogLevel != Level.OFF) {
            val logFile = logDir.resolve("$loggerFileName.log")
            if (!Files.exists(logFile))
                Files.createFile(logFile)
            val fileAppender: FileAppender = FileAppender.newBuilder()
                .setName("File-${this.javaClass.simpleName}-$id")
                .withFileName(logFile.absolutePathString())
                .setLayout(
                    PatternLayout.newBuilder()
                        .withPattern("%d %-5level [%t] %c{1.}.%M(%L): %msg%n")
                        .build()
                )
                .build()
            fileAppender.start()
            loggerConfig!!.addAppender(fileAppender, fileLogLevel, null)
        }

        context.configuration.addLogger(loggerName, loggerConfig)
        return LogManager.getLogger(loggerName)
    }

    @Throws(Exception::class)
    protected fun extractFileInJar(inPath: String, outPath: Path) {
        val moduleJarPath = ProcedureArgumentsDeserializer.allModules[this::class.qualifiedName]!!
        JarFile(moduleJarPath.toFile()).use {
            val entry = it.getEntry(inPath)
                ?: throw IllegalStateException("$inPath not found in $moduleJarPath")
            it.getInputStream(entry).use { stream ->
                val out = FileOutputStream(outPath.toFile())
                stream.copyTo(out)
                logger.debug("Copied {} to {}", inPath, outPath)
                out.close()
            }
        }
    }

    /**
     * Starts the process with an optional timeout. If a timeout is specified, it will run in a separate thread.
     *
     * @param timeout The timeout for the process in seconds. If 0, there is no timeout.
     * @throws Exception
     */
    @Throws(Exception::class)
    open suspend fun startProcess(timeout: Int = 0) {
        if (hasTable)
            DatabaseClient.startTask(dbTableName, id.toLong())

        val job = CoroutineScope(coroutineContext).launch(start = CoroutineStart.LAZY) {
            this@AkibaModule.startProcess()
        }

        // TimeoutTaskMonitor will start the timer on constructing.
        // NOTE: attach the monitor to *this* module's job (job), not to the calling
        // coroutine's job.  asCoroutineAware(coroutineContext.job) makes the monitor's
        // cancel() cancel the *caller*, and the wrapper cancels the monitor when the
        // module finishes — which kills the remaining tasks of the binary (the caller's
        // coroutine swallows it as JobCancellationException in ProcedureManager).
        taskGlobalMonitor =
            if (this.javaClass.annotations.none { it is IgnoreRuntimeTimeout } && timeout > 0)
                TimeoutTaskMonitor.timeoutIn(timeout.toLong(), TimeUnit.SECONDS)
                    .asCoroutineAware(job)
            else
                TaskMonitorAdapter(true).asCoroutineAware(job)

        val executionTime = measureTimeMillis {
            try {
                job.start()
                job.join()
            } catch (_: CancelledException) {
                logger.warn("Process cancelled")
            } catch (e: Exception) {
                logger.error("Process failed: ${e.message}")
                e.printStackTrace()
                failureSign = FAILED
                if (hasTable)
                    updateErr("Process failed: ${e.message}(${e.javaClass.simpleName})")
            } catch (_: OutOfMemoryError) {
                logger.error("Process out of memory")
                failureSign = RUNTIME_ERROR
                if (hasTable)
                    updateErr("Process out of memory")
            } catch (e: Exception) {
                logger.error("Process exception: ${e.message}")
                failureSign = RUNTIME_ERROR
                if (hasTable)
                    updateErr("Process exception: ${e.message}(${e.javaClass.simpleName})")
            } catch (e: Error) {
                logger.error("Process error: ${e.message}")
                failureSign = RUNTIME_ERROR
                if (hasTable)
                    updateErr("Process error: ${e.message}(${e.javaClass.simpleName})")
            }
        }

        logger.debug("execution time: $executionTime ms")

        if (taskGlobalMonitor.isCancelled) {        // Timeout occurred
            if (this.javaClass.annotations.any { it is FailOnCancelled })
                failureSign = FAILED
        } else {
            try { taskGlobalMonitor.cancel() } catch (_: Exception) {}
        }

        if (hasTable)
            DatabaseClient.finishTask(dbTableName, id.toLong())

        if (failureSign == SUCCESS)
            clearErr()
        close()
    }

    /**
     * Abstract method to start the process without a timeout. To be implemented by subclasses.
     * @throws Exception
     */
    @Throws(Exception::class)
    open suspend fun startProcess() {}

    /**
     * Handles timeout situations, to be implemented by subclasses.
     * @throws Exception
     */
    @Throws(Exception::class)
    open suspend fun timeoutHandler() {}

    @Throws(DatabaseClient.DatabaseDaemonException::class)
    protected fun updateData(data: Map<String, Any?>) {
        if (hasTable)
            DatabaseClient.updateData(dbTableName, id.toLong(), data)
        else
            logger.error("Update data not allowed in modules with 'DoNotCreateTable', " +
                "if you want to save data, remove this annotation")
    }

    @Throws(DatabaseClient.DatabaseDaemonException::class)
    protected fun updateErr(msg: String) {
        if (hasTable)
            DatabaseClient.updateData(dbTableName, id.toLong(), mapOf("err_msg" to msg))
    }

    @Throws(DatabaseClient.DatabaseDaemonException::class)
    protected fun clearErr() {
        if (hasTable)
            DatabaseClient.updateData(dbTableName, id.toLong(), mapOf("err_msg" to null))
    }

    var logLevel: Level
        get() = logger.level
        set(logLevel) {
            (LogManager.getContext() as LoggerContext).configuration.getLoggerConfig(javaClass.name).level = logLevel
        }

    override fun close() {
//        loggerConfig ?. let { lc ->
//            LoggerContext.getContext().loggers.removeIf { it.name == logger.name }
//            lc.appenders.values.forEach { it.stop() }
//            lc.appenders.clear()
//            LoggerContext.getContext().configuration.removeLogger(logger.name)
//            lc.stop()
//        }
    }

    companion object {
        const val DEFAULT_TIMEOUT: Int = 180

        const val SUCCESS: Int = 0
        const val FAILED: Int = 1
        const val RUNTIME_ERROR: Int = 2

        fun pascalToSnake(pascal: String): String = buildString {
            var prevLower = false
            for (c in pascal) {
                if (c.isUpperCase()) {
                    if (isNotEmpty() && prevLower) {
                        append('_')
                    } else if (length > 1) {
                        val nextLower = getOrNull(indexOf(c) + 1)?.isLowerCase() == true
                        if (nextLower) append('_')
                    }
                    append(c.lowercaseChar())
                    prevLower = false
                } else {
                    append(c)
                    prevLower = true
                }
            }
        }

        fun getDeserializerMapper(mod: KClass<out AkibaModule>): ObjectMapper {
            val configKClass = mod.findAnnotation<WithConfigClass>() ?.clazz ?: return jacksonObjectMapper()

            val deserializerKClass = mod.findAnnotation<WithConfigDeserializer>() ?.deserializer
                ?: return jacksonObjectMapper()

            @Suppress("UNCHECKED_CAST")
            val deserializer = (deserializerKClass as KClass<JsonDeserializer<Any>>).objectInstance

            val module = SimpleModule().apply {
                addDeserializer(configKClass.java as Class<Any>, deserializer)
            }

            return jacksonObjectMapper().registerModule(module)
        }

        fun getSerializerMapper(mod: KClass<out AkibaModule>): ObjectMapper {
            val configKClass = mod.findAnnotation<WithConfigClass>() ?.clazz ?: return jacksonObjectMapper()

            val serializerKClass = mod.findAnnotation<WithConfigSerializer>() ?.serializer
                ?: return jacksonObjectMapper()

            @Suppress("UNCHECKED_CAST")
            val serializer = (serializerKClass as KClass<JsonSerializer<Any>>).objectInstance

            val module = SimpleModule().apply {
                addSerializer(configKClass.java as Class<Any>, serializer)
            }

            return jacksonObjectMapper().registerModule(module)
        }
    }
}
