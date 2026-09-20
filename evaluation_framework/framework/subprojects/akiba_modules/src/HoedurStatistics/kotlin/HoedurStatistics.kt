package org.iotsplab.akiba.process

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.luben.zstd.ZstdInputStream
import ghidra.program.model.listing.Program
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.HoedurStatistics.Companion.HOEDUR_REPLAY_VIEW
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.WithView
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.function.allBasicBlockStarts
import org.iotsplab.akiba.utils.memory.MemoryUtil
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.notExists
import kotlin.io.path.readLines
import kotlin.jvm.Throws

@WithTableColumn("crash_count", "INTEGER")
@WithTableColumn("timeout_count", "INTEGER")
@WithTableColumn("exit_count", "INTEGER")
@WithTableColumn("fuzzware_err_count", "INTEGER")
@WithTableColumn("unique_bugcomb_count", "INTEGER")
@WithTableColumn("unique_crash_count", "INTEGER")
@WithTableColumn("unique_nonexec_count", "INTEGER")
@WithTableColumn("unique_writeprot_count", "INTEGER")
@WithTableColumn("full_crash_info", "JSONB")
@WithTableColumn("coverage_info", "TEXT")
@WithTableColumn("fuzz_cov", "DOUBLE PRECISION")
@WithTableColumn("total_execs_done", "BIGINT")
@WithTableColumn("total_execs_per_second", "DOUBLE PRECISION")
@WithView("hoedur_replay_data", HOEDUR_REPLAY_VIEW)
@WithConfigClass(HoedurStatisticsConfig::class)
@IgnoreRuntimeTimeout
class HoedurStatistics (
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    program = program,
    configPath = configPath,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    private val prog: Program
        get() = program!!
    private val conf: HoedurStatisticsConfig
        get() = config as HoedurStatisticsConfig
    private val hoedurOutputRoot: Path
        = Path.of(mainConf.binariesRoot, conf.crashArchiveRoot, id.toString())
    private val corpusFile: File
    private val reportFile: File?
    private val inputIds: MutableList<Long> = mutableListOf()
    private val coverage: MutableMap<Long, Double> = mutableMapOf()   // input id, coverage

    init {
        if (!hoedurOutputRoot.isDirectory()) {
            updateErr("Output path not a directory")
            throw IllegalArgumentException("Hoedur output path is not a directory")
        }

        corpusFile = hoedurOutputRoot.toFile().listFiles {
            it.name.startsWith("TARGET") && it.name.endsWith(".corpus.tar.zst")
        } ?.let {
            if (it.size > 1) {
                updateErr("More than 1 corpus file found")
                throw IllegalStateException("There exists more than 1 corpus file in root directory")
            } else if (it.isEmpty()) {
                updateErr("No corpus file found")
                throw IllegalStateException("No corpus file found")
            }
            else it.first()
        } ?: run {
            updateErr("Read data directory failed")
            throw IllegalStateException("Read data directory failed")
        }

        reportFile = hoedurOutputRoot.toFile().listFiles {
            it.name.startsWith("TARGET") && it.name.endsWith(".report.bin.zst")
        } ?.let {
            if (it.size > 1) {
                updateErr("More than 1 report file found")
                throw IllegalStateException("There exists more than 1 report file in root directory")
            } else if (it.isEmpty()) {
                updateErr("No report file found")
                null
            }
            else it.first()
        }
    }

    @Throws(IllegalStateException::class)
    override suspend fun startProcess() {
        updateFuzzwareErrCount()
        updateErrCount()
        updateUniqueErr()
        updateCoverage()
        updateFuzzerPerformance()
    }

    private fun updateFuzzwareErrCount() {
        val fuzzwareErrCount =
            hoedurOutputRoot.toFile().listFiles { it.isFile && it.name.startsWith("fuzzware-error") }
                ?.size?.toLong() ?:0L
        updateData(mapOf("fuzzware_err_count" to fuzzwareErrCount))
    }

    private fun updateErrCount() {
        var crashCount = 0L
        var timeoutCount = 0L
        var exitCount = 0L

        try {
            corpusFile.let {
                logger.info("Got corpus file: $it")
                TarArchiveInputStream(ZstdInputStream(it.inputStream()))
            } .let { tis ->
                while (true) {
                    val entry = tis.nextEntry ?: break
                    when {
                        entry.name.startsWith("crash/") -> crashCount++
                        entry.name.startsWith("timeout/") -> timeoutCount++
                        entry.name.startsWith("exit/") -> exitCount++
                    }
                }
            }
        } catch (_: IOException) {
            logger.error("Broken ZST file")
            updateErr("broken zst file")
        }

        updateData(mapOf(
            "crash_count" to crashCount, "timeout_count" to timeoutCount, "exit_count" to exitCount))
    }

    @Throws(IllegalStateException::class)
    private fun updateUniqueErr() {
        // We use scripts/eval-bug-combinations.py to get unique crash lists in yaml
        // The crash info yaml will be in <hoedurOutputRoot>/bug-combinations-run-01.yml
        // We discard the stderr output because there are some compilation warnings
        //
        // TODO (?) : Add checks for env
        val cmd = "${conf.cmdPredo} && workon fuzzware && cd ${conf.hoedurRoot} && " +
                "${conf.pythonPath} scripts/eval-bug-combinations.py " +
                "${hoedurOutputRoot.absolutePathString()} " +           // corpus
                "--output ${hoedurOutputRoot.absolutePathString()} " +  // output
                "--targets ${hoedurOutputRoot.absolutePathString()}"
        logger.info(cmd)
        val builder = ProcessBuilder(*conf.cmdPrefix.toTypedArray(), cmd)
        val process: Process = builder.start()
        process.waitFor()

        val outputPath: Path = hoedurOutputRoot.resolve("bug-combinations-run-01.yml")
        if (outputPath.notExists()) {
            logger.error("Output file not found")
            updateErr("Output file not found")
            return
        }
        val yamlText = outputPath.toFile().readText().removePrefix("---\n")

        // Parse YAML text into a general structure
        val yaml = Yaml()
        val result: List<List<Map<String, String>>> = yaml.load(yamlText)
        logger.debug("Crash result: ")
        result.forEach { crash ->
            logger.debug(crash.toString())
        }
        val json = jacksonObjectMapper()
        val jsonResult = json.writeValueAsString(result)
        // inputIds.addAll(result.map { it[1]["time"]!!.toLong() })

        updateData(mapOf(
            "unique_bugcomb_count" to result.count { res -> res.any { it.keys.contains("BugCombination") } }.toLong(),
            "unique_crash_count" to result.count { res -> res.any { it.keys.contains("Crash") } }.toLong(),
            "unique_nonexec_count" to result.count { res -> res.any { it.keys.contains("NonExecutable") } }.toLong(),
            "unique_writeprot_count" to result.count { res -> res.any { it.keys.contains("RomWrite") } }.toLong(),
            "full_crash_info" to jsonResult
        ))
    }

    @Throws(IllegalStateException::class)
    private suspend fun updateCoverage() {
        reportFile ?: return
        rebase()

        val cmd = "${conf.cmdPredo} && workon fuzzware && cd ${conf.hoedurRoot} && " +
                "cd ${conf.hoedurRoot} && " +
                "${conf.cargoPath} run --bin hoedur-coverage-list -- " +
                "--output ${hoedurOutputRoot.resolve("coverage.tar.zst")} ${reportFile.absolutePath} --no-filter"
        logger.info(cmd)
        val builder = ProcessBuilder(*conf.cmdPrefix.toTypedArray(), cmd)
        val process: Process = builder.start()
        process.waitFor()

        val covZst = hoedurOutputRoot.resolve("coverage.tar.zst").toFile()
        if (!covZst.exists()) {
            logger.error("Coverage zstd file not generated")
            return
        }

        val allBasicBlocks = prog.functionManager.getFunctions(false).map {
            func -> func.allBasicBlockStarts().map { it.offset }
        } .flatMap { it } .toMutableList()
        logger.info("Got ${allBasicBlocks.size} basic block")

        prog.memory.blocks.filter {
            it.isLoaded && it.isExecute
        }.forEach { println("${it.start.offset.toString(16)} - ${it.end.offset.toString(16)}") }

        covZst.let {
            logger.info("Get coverage file $it")
            TarArchiveInputStream(ZstdInputStream(it.inputStream()))
        } .let { tis ->
            while (true) {
                val entry = tis.nextEntry ?: break
                if (entry.name == "coverage-superset.txt") {
                    logger.info("Found coverage-superset.txt")
                    val fileContent = tis.readBytes().decodeToString()
                    val superset = fileContent.split("\n").let {
                        it.subList(0, it.size - 1)
                    } .map {
                        it.removePrefix("0x").toLong(16)
                    }
                    val validSupersetCount = superset.count {
                        addressInMapped(it)
                    }
                    allBasicBlocks.addAll(superset.filter {
                        !allBasicBlocks.contains(it) && addressInMapped(it)
                    })
                    logger.info("Valid superset count: $validSupersetCount")
                    logger.info("Fuzz coverage: ${validSupersetCount.toDouble() / allBasicBlocks.size}")
                    updateData(mapOf("fuzz_cov" to validSupersetCount.toDouble() / allBasicBlocks.size))
                }
            }
        }

        logger.info("Got ${allBasicBlocks.size} basic block (augmented)")

        TarArchiveInputStream(ZstdInputStream(covZst.inputStream())).let { tis ->
            val fileNameRegex = Regex("^coverage/input-([0-9]+)\\.txt$")
            while (true) {
                val entry = tis.nextEntry ?: break
                if (fileNameRegex.matches(entry.name)) {
                    val inputId = fileNameRegex.find(entry.name)!!.groupValues[1].toLong()
                    val fileContent = tis.readBytes().decodeToString()
                    val basicBlocks = fileContent.split("\n").let {
                        it.subList(0, it.size - 1)
                    } .map {
                        it.removePrefix("0x").toLong(16)
                    } .filter {
                        addressInMapped(it)
                    }
                    coverage[inputId] = basicBlocks.size.toDouble() / allBasicBlocks.size
                }
            }
        }

        updateData(mapOf("coverage_info" to Json.encodeToString(coverage)))
        unrebase()
    }

    private fun updateFuzzerPerformance() {
        val cmd = "${conf.cmdPredo} && workon fuzzware && cd ${conf.hoedurRoot} && " +
                "cd ${conf.hoedurRoot} && " +
                "${conf.cargoPath} run --bin hoedur-eval-executions -- " +
                "${hoedurOutputRoot.resolve("perf.txt")} ${corpusFile.absolutePath}"
        logger.info(cmd)
        val builder = ProcessBuilder(*conf.cmdPrefix.toTypedArray(), cmd)
        val process: Process = builder.start()
        process.waitFor()

        val perfPath = hoedurOutputRoot.resolve("perf.txt")
        if (!perfPath.exists())
            return

        val data = perfPath.readLines()[1].split(Regex("[\t ]+"))
        updateData(mapOf(
            "total_execs_done" to data[1].toLong(),
            "total_execs_per_second" to data[2].toDouble(),
        ))
    }

    private suspend fun rebase() {
        conf.rebaseSource ?: return

        val baseAddress = getTaskData(conf.rebaseSource) as Long
            - prog.memory.blocks.filter { it.isLoaded }.minBy { it.start }.start.offset
        MemoryUtil.moveAllBlocksInOffset(prog.memory, baseAddress)
    }

    private suspend fun unrebase() {
        conf.rebaseSource ?: return

        val baseAddress = getTaskData(conf.rebaseSource) as Long
            - prog.memory.blocks.filter { it.isLoaded }.minBy { it.start }.start.offset
        MemoryUtil.moveAllBlocksInOffset(prog.memory, -baseAddress)
    }

    private fun addressInMapped(addr: Long): Boolean {
        return prog.memory.blocks.filter {
            it.isLoaded && it.isExecute
        } .any {
            addr >= it.start.offset &&
            addr < it.end.offset
        }
    }

    companion object {
        const val HOEDUR_REPLAY_VIEW = """
            SELECT
                hsr.id,
                (fci.elem -> 1 -> 'source' -> 'input')::jsonb      AS input_id,
                err.key::text                                      AS err_type,
                err.value::jsonb                                   AS err_data,
                (fci.elem -> 1 -> 'time')::jsonb                   AS err_timestamp_from_fuzz_start
            FROM hoedur_statistics_results AS hsr
            JOIN jsonb_array_elements(hsr.full_crash_info) AS fci(elem) ON true
            JOIN jsonb_each_text((fci.elem -> 0)::jsonb) AS err(key, value) ON true
            ORDER BY hsr.id, input_id;
        """
    }
}