package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.notExists

@WithTableColumn("total_string_count", "INTEGER")
@WithTableColumn("best_match_base", "INTEGER")
@WithTableColumn("best_match_ratio", "DOUBLE PRECISION")
@WithTableColumn("all_results", "JSONB")
@WithConfigClass(RBaseFindConfig::class)
@IgnoreRuntimeTimeout
class RBaseFind (
    configPath: String? = null,
    id: Int,
    program: Program?,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    configPath = configPath,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    private val conf: RBaseFindConfig
        get() = config as RBaseFindConfig
    private lateinit var rBaseFindRoot: Path
    private var error: String? = null

    @Serializable
    data class RBaseFindResults (
        var totalStrCnt: Int = 0,
        var bestMatchBase: Long = -1,
        var baseMatchRatio: Double = 0.0,
        var allResults: MutableMap<Long, Int> = mutableMapOf()
    )

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    override suspend fun startProcess() {
        conf.rbasefindRoot ?: throw IllegalArgumentException("Root directory of RBaseFind not specified")

        rBaseFindRoot = Path.of(conf.rbasefindRoot!!)
        // run `cargo build` to build the rust executable
        if (rBaseFindRoot.resolve("target").resolve("release").resolve("rbasefind").notExists())
            if (!buildExecutable())
                throw IllegalStateException("Failed to initialize RBaseFind")

        val results = runRBaseFind()
        updateData(mapOf(
            "total_string_count" to results.totalStrCnt.toLong(),
            "best_match_base" to results.bestMatchBase,
            "best_match_ratio" to results.baseMatchRatio,
            "all_results" to (error ?: Json.encodeToString(results.allResults))
        ))
    }

    private fun buildExecutable(): Boolean {
        val cmd = "${conf.cmdPredo} && cd ${rBaseFindRoot.absolutePathString()} && " +
                "${Path.of(conf.cargoPath).absolutePathString()} build --release"

        val builder = ProcessBuilder(*conf.cmdPrefix.toTypedArray(), cmd)
            .redirectErrorStream(true)
        val process: Process = builder.start()
        process.waitFor()

        if (rBaseFindRoot.resolve("target").resolve("release").resolve("rbasefind").notExists()) {
            logger.error("Failed to build RBaseFind")
            return false
        }
        return true
    }

    private suspend fun runRBaseFind(): RBaseFindResults = coroutineScope {
        val execPath: Path = rBaseFindRoot.resolve("target").resolve("release").resolve("rbasefind")
        val result = RBaseFindResults()

        var cmd = "${conf.cmdPredo} && ${execPath.absolutePathString()} " +
                "${usingFile.absolutePath} " +
                "-m ${conf.minStrlen} -o 0x${conf.baseAlign.toString(16)} -t ${conf.threadNum}"
        if (getMetadata().arch?.contains(":BE:")?:false)
            cmd += " -b"

        val builder = ProcessBuilder(*conf.cmdPrefix.toTypedArray(), cmd)
            .redirectErrorStream(true)
        val process: Process = builder.start()

        val strCntRegex = Regex("Located ([0-9]+) strings")
        val matchResultRegex = Regex("0x([0-9a-f]+): ([0-9]+)")

        val outReader = launch {
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line ?: continue
                    logger.trace(line)

                    if (line.startsWith("Application error: No strings found in target binary"))
                        error = "No strings found"

                    when {
                        strCntRegex.matches(line) -> {
                            val matchResult = strCntRegex.find(line)
                            result.totalStrCnt = matchResult!!.groupValues[1].toInt()
                        }
                        matchResultRegex.matches(line) -> {
                            val matchResult = matchResultRegex.find(line)
                            val addr = matchResult!!.groupValues[1].toLong(16)
                            val count = matchResult.groupValues[2].toInt()
                            if (result.bestMatchBase == -1L) {
                                result.bestMatchBase = addr
                                result.baseMatchRatio = count.toDouble() / result.totalStrCnt
                            }
                            result.allResults[addr] = count
                        }
                    }
                }
            }
        }

        outReader.start()
        outReader.join()

        return@coroutineScope result
    }
}