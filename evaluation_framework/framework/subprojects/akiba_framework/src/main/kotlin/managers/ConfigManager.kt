package org.iotsplab.akiba.managers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.jsonMapper
import org.iotsplab.akiba.Main
import org.iotsplab.akiba.Main.Companion.mainConfigPath
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.utils.General
import org.iotsplab.akiba.utils.ProcedureArguments
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer.loadAllModules
import org.iotsplab.akiba.utils.ProcedureArgumentsSerializer
import org.iotsplab.akiba.utils.SqlSource
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.WithGhidraProject
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.reflect.full.findAnnotation
import kotlin.system.exitProcess

object ConfigManager {
    lateinit var config: Configs

    val sqlSource: SqlSource
        get() = config.sqlSource
    val mainConf: General
        get() = config.general!!
    val projectConf: WithGhidraProject
        get() = config.withGhidraProject!!

    lateinit var mainConfigFile: File
    var mainConfigJsonPath: String = "."    // . means all content of the YAML document

    const val KEY_SEPARATOR: String = "@"

    @Throws(IllegalArgumentException::class, IOException::class)
    fun loadGlobalConfig(path: String = mainConfigPath): Configs {

        parseJsonPath(path).let {
            mainConfigFile = File(it.first)
            mainConfigJsonPath = it.second
        }

        require(mainConfigFile.toPath().isRegularFile()) { "$mainConfigFile not found or is not a file" }

        return try {
            val mainConfNode: JsonNode = jsonMapper().readTree(mainConfigFile).at(mainConfigJsonPath)

            val module = SimpleModule().addDeserializer(
                ProcedureArguments::class.java, ProcedureArgumentsDeserializer)
            val mapper = jacksonObjectMapper().registerModule(module)
            val conf: Configs = mapper.treeToValue(mainConfNode, Configs::class.java) ?: run {
                globalLogger.error("Failed to load configs.json")
                exitProcess(1)
            }

            loadAllModules(conf.tasks)

            conf
        } catch (e: Exception) {
            globalLogger.error("Exception(${e.javaClass.simpleName}) occurred while loading main config: ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }
    }

    @Throws(IllegalArgumentException::class)
    fun parseJsonPath(pathAndKey: String): Pair<String, String> {
        if (pathAndKey.startsWith(KEY_SEPARATOR.repeat(2)))
            return mainConfigFile.absolutePath to pathAndKey.substring(2)
        val firstDelimiterIdx = pathAndKey.indexOf(KEY_SEPARATOR)
        if (firstDelimiterIdx == -1)
            return pathAndKey to ""

        return pathAndKey.substring(0, firstDelimiterIdx) to pathAndKey.substring(firstDelimiterIdx + 1)
    }

    @Throws(IllegalArgumentException::class, Exception::class)
    fun <T> parseModuleConfig(pathAndKey: String, objectMapper: ObjectMapper = jacksonObjectMapper(), clazz: Class<T>): T {
        val (filePath, key) = parseJsonPath(pathAndKey)

        require(Path.of(filePath).isRegularFile()) { "$filePath not found or is not a file" }

        val configNode: JsonNode = jsonMapper().readTree(File(filePath)).at(key)

        return try {
            // Json in Kotlinx.serialization only support reified types, so we use Gson here instead
            objectMapper.treeToValue(configNode, clazz)
        } catch (e: Exception) {
            globalLogger.error("Failed to parse $pathAndKey: ${e.message}")
            globalLogger.error("Data failed: $configNode")
            throw e
        }
    }

    @Throws(IllegalArgumentException::class)
    fun mergeConfigs(config: Configs = this.config, outputPath: File) {
        val builder = jacksonObjectMapper()
        val root: ObjectNode = builder.createObjectNode()

        val mainConfigNode: JsonNode = jacksonObjectMapper().registerModule(
            SimpleModule().addSerializer(
                ProcedureArguments::class.java, ProcedureArgumentsSerializer)
        ).valueToTree(config)

        config.tasks.mapIndexedNotNull { idx, task ->
            task.configKey ?. let { idx to (Path.of(it) to task.mainClassName) }
        } .forEach { (idx, second) ->
            val moduleConf = second.first
            val classPath = second.second
            val (filePath, key) = parseJsonPath(moduleConf.toString())
            require(Path.of(filePath).isRegularFile()) { "$moduleConf not found or is not a file" }

            config.tasks.find { it.mainClassName == classPath } ?. let {
                @Suppress("UNCHECKED_CAST")
                val moduleClass = it.mainClass!! as Class<out AkibaModule>

                (mainConfigNode.at("/tasks/$idx") as ObjectNode).set<TextNode>(
                    "configKey", TextNode(KEY_SEPARATOR.repeat(2) + "/" + it.mainClassName)
                )

                moduleClass.kotlin.findAnnotation<WithConfigClass>() ?.clazz ?.let { _ ->
                    val mapper = AkibaModule.getDeserializerMapper(moduleClass.kotlin)
                    root.putIfAbsent(
                        it.mainClassName, mapper.readTree(File(filePath)).at(key))
                }
            }
        }

        root.putIfAbsent("main", mainConfigNode)

        // Write merged content to output file
        try {
            if (!outputPath.exists())
                outputPath.parentFile.mkdirs()
            builder.writerWithDefaultPrettyPrinter().writeValue(outputPath, root)
        } catch (e: Exception) {
            globalLogger.error("Failed to write merged configs to $outputPath: ${e.message}")
            throw e
        }
    }
}