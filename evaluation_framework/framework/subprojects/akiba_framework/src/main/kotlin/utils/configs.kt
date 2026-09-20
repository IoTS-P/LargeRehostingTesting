package org.iotsplab.akiba.utils

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.AkibaModule.Companion.DEFAULT_TIMEOUT
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.isRegularFile

data class Configs (
    var username: String? = null,
    var password: String? = null,
    var usingInstance: String? = null,
    var globalConsoleLogLevel: String = "INFO",
    var globalFileLogLevel: String = "DEBUG",

    var general: General? = null,
    var withGhidraProject: WithGhidraProject? = null,
    var sqlSource: SqlSource = SqlSource(),
    var globalPreTasks: List<ProcedureArguments> = listOf(),
    var packages: List<String>? = null,
    // Database data generated in previous tasks to import
    // Format: "table_name.column_name"
    // If all columns of a table are needed, use "table_name.*"
    var dbImports: List<String>? = null,
    var tasks: List<ProcedureArguments> = listOf()
)

data class General(
    // Root directory saving the binaries. When importing binaries, Akiba will copy them into a directory and rename
    // them with ids, this refers to the directory
    var binariesRoot: String = ".",
    // Root directory of files to be imported. When importing binaries, Akiba will resolve relative paths based on this
    // path. This will not be used if the task is not an import task
    var importRoot: String? = null,
    var processor: String = "n/a",
    var autoAnalysisTimeout: Int = 180,
    var threads: Int = 1
)

data class WithGhidraProject (
    // Root of Ghidra projects
    var projectRoot: String = "ghidra_projects",
    // could be a path or a label that could be found in ghidra_projects/, if null, will create a new project
    var name: String? = null,
    // fork/base/new: fork means creating a copy of this project, base means continuing to work on this project,
    //                new means creating a new project
    var mode: String = "new",
    // If "fork" specified in `mode`, it means the copied project name, could be an absolute path
    var forkTo: String? = null,
    // If "fork" specified in `mode` and this specified in true, will only copy programs just before their tasks start.
    // After all tasks finished, the forked project will only contain programs handled by tasks.
    var forkOnTask: Boolean = false,
    // If "base" specified in `mode`, it means the position of logs. If null, it will overwrite previous log.
    // could be an absolute path
    var continueLog: String? = null,
    // Automatically overwrite Ghidra project of the same name (YOUR DATA MAY BE LOST!!!)
    var overwriteProject: Boolean = false,
    // Automatically delete previous program and use a newly created program (YOUR DATA MAY BE LOST!!!)
    var deletePreviousProgram: Boolean = false,
    // Automatically overwrite logs of the same name, only works on fork mode (YOUR DATA MAY BE LOST!!!)
    var overwriteLog: Boolean = false,
    // save Ghidra project after akiba finished all procedures
    var saveProject: Boolean = false,
    // Do not create program before starting procedures
    var noCreateProgram: Boolean = false
)

data class SqlSource(
    // If URL is set to null, all data will not be saved but to print out at the console
    var serverIP: String = "127.0.0.1",
    // Database username
    var serverPort: Int = 31777,
    // Database snapshot used, default `current` is the latest snapshot used in the last time
    var useSnapshot: String = "current",
    // SELECT constraint which determines ids to be processed
    var constraint: String = "",
    // If set true, the database will become read-only and all update requests will be discarded
    var disableUpdate: Boolean = false,
    // If set, will use a local cache database saving data that failed to send to server
    var useLocalCache: String? = null
)

data class ProcedureArguments(
    // Main class name (Full class path) of the task
    var mainClassName: String,
    // Main class object of the task (No need to fill in config, will be found automatically)
    var mainClass: Class<*>? = null,
    // Config key of the task (File Path + Json Path)
    var configKey: String? = null,
    // Timeout of the task
    var timeout: Int = DEFAULT_TIMEOUT,
    // Console log level
    var consoleLogLevel: Level = Level.INFO,
    // File log level
    var fileLogLevel: Level = Level.INFO,
    // Table name to update data to, has a default value specified according to task class name
    // e.g. task class name = "GetFunctions", default table name = "get_functions_results"
    var tableName: String? = null
)

object ProcedureArgumentsDeserializer: JsonDeserializer<ProcedureArguments>() {
    // Key: class full path, Value: module file path
    val allModules: MutableMap<String, Path> = mutableMapOf()
    val jarLoaded: MutableSet<URL> = mutableSetOf()
    lateinit var loader: URLClassLoader

    init {
        peekAllModules()
    }

    @Throws(IllegalArgumentException::class)
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext
    ): ProcedureArguments? {
        val node = parser.codec.readTree<JsonNode>( parser)

        val mainClassName = node["mainClassName"] ?.textValue()
            ?: throw IllegalArgumentException("mainClassName required")
        val configKey = node["configKey"] ?.textValue()
        val timeout = node["timeout"] ?.intValue() ?: DEFAULT_TIMEOUT
        val consoleLogLevel = node["consoleLogLevel"] ?.textValue()?.let { Level.valueOf(it) } ?: Level.INFO
        val fileLogLevel = node["fileLogLevel"] ?.textValue()?.let { Level.valueOf(it) } ?: Level.INFO
        val tableName = node["tableName"] ?.textValue()

        resolveModule(mainClassName)

        return ProcedureArguments(
            mainClassName,
            null,
            configKey,
            timeout,
            consoleLogLevel,
            fileLogLevel,
            tableName
        )
    }

    /**
     * peekAllModules: Get main classes of all modules in /modules, but doesn't load them
     */
    @Throws(IllegalStateException::class)
    fun peekAllModules() {
        File("modules").listFiles { _, name ->
            name.endsWith(".jar")
        } ?.forEach { filename ->
            val jarFile = JarFile(filename)
            val mainClassAttr: String = (jarFile.manifest.mainAttributes.getValue("Main-Class")) ?: run {
                globalLogger.warn("Jar file $filename don't have attribute `Main-Class`, skipped")
                return@forEach
            }
            allModules.keys.firstOrNull {
                it.split(".").last() == mainClassAttr.split(".").last() } ?.let {
                throw IllegalStateException("Conflicted module main class: $it, " +
                        "in ${allModules[mainClassAttr]} and $filename")
            }
            globalLogger.info("Found: $mainClassAttr in ${filename.name}")
            allModules[mainClassAttr] = filename.toPath()
        }
    }

    @Throws(ClassNotFoundException::class)
    fun addJar(className: String, jarNeeded: MutableSet<URL> = mutableSetOf()) {
        try {
            Class.forName(className)
            return
        } catch (_: ClassNotFoundException) {}

        // Find in /modules to see if there is a jar file
        val modulePath = allModules[className]?.let {
            if (it.isRegularFile()) it.toFile()
            else throw ClassNotFoundException("Module not found: $className")
        } ?: throw ClassNotFoundException("Module not found: $className")

        if (jarNeeded.contains(modulePath.toURI().toURL()))
            return

        val tempLoader = URLClassLoader(arrayOf(modulePath.toURI().toURL()))
        val mainClass = tempLoader.loadClass(className)

        if (AkibaModule::class.java.isAssignableFrom(mainClass)) {
            globalLogger.info("Got: ${modulePath.name}")
            // If there are dependencies, load them
            getRequireModules(mainClass).forEach { dep ->
                try { Class.forName(dep) }
                catch (_: ClassNotFoundException) { addJar(dep, jarNeeded) }
            }
            globalLogger.info("Found Jar file needed to be loaded: ${modulePath.name}")
            jarNeeded.add(modulePath.toURI().toURL())
        } else
            throw ClassNotFoundException(
                "Module invalid: $className is not a subclass of `AkibaModule`")
    }

    private fun getRequireModules(clazz: Class<*>): List<String> {
        return clazz.classLoader.getResourceAsStream("META-INF/module-deps")
            ?.bufferedReader() ?.readLines() ?: listOf()
    }

    @Throws(ClassNotFoundException::class)
    fun resolveModule(className: String) {
        val jarNeeded: MutableSet<URL> = mutableSetOf()

        try { Class.forName(className) }
        catch (_: ClassNotFoundException) {
            // Find all Jars needed to be loaded, including dependencies
            addJar(className, jarNeeded)
        }

        jarLoaded.addAll(jarNeeded)
        globalLogger.info("Loading modules: ${jarNeeded.map { it.file }}")
    }

    fun loadAllModules(tasks: List<ProcedureArguments>) {
        loader = URLClassLoader(jarLoaded.toTypedArray())

        jarLoaded.forEach { url ->
            JarFile(url.file).use { jar ->
                jar.entries().toList().filter { it.name.endsWith(".class") && !it.name.contains("$")
                        && !it.name.startsWith("META-INF") } .forEach {
                    try {
                        // Load all classes in jars
                        val className = it.name.replace('/', '.').dropLast(6)
                        val clazz = loader.loadClass(className)
                        globalLogger.info("Loaded ${clazz.name} from ${jar.name}")
                        tasks.firstOrNull { it.mainClassName == className } ?.let { task ->
                            println(clazz)
                            task.mainClass = clazz
                        }
                    } catch (_: NoClassDefFoundError) {}
                }
            }
        }
    }
}

object ProcedureArgumentsSerializer: JsonSerializer<ProcedureArguments>() {
    override fun serialize(
        pa: ProcedureArguments,
        generator: JsonGenerator,
        provider: SerializerProvider
    ) {
        generator.writeStartObject()
        generator.writeStringField("mainClassName", pa.mainClassName)
        pa.configKey ?. let { generator.writeStringField("configKey", it) }
        generator.writeNumberField("timeout", pa.timeout)
        generator.writeStringField("consoleLogLevel", pa.consoleLogLevel.name())
        generator.writeStringField("fileLogLevel", pa.fileLogLevel.name())
        pa.tableName ?. let { generator.writeStringField("tableName", it) }
        generator.writeEndObject()
    }
}