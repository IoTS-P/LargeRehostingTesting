package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.client.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.managers.DefaultDatabaseOperator
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.FirmRCA.Companion.CLASSIFY_VIEW_SQL
import org.iotsplab.akiba.process.FirmRCA.Companion.CREATE_VIEW_SQL
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.WithView
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer.allModules
import org.iotsplab.akiba.utils.WithConfigClass
import java.nio.file.Files
import java.nio.file.Path
import java.sql.ResultSet
import java.util.jar.JarFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists

@WithTableColumn("input_id_map", "JSONB")
@WithTableColumn("firmrca_results", "JSONB")
@WithView("firmrca_classified_results", CLASSIFY_VIEW_SQL)
@WithView("firmrca_parsed_results", CREATE_VIEW_SQL)
@WithConfigClass(FirmRCAConfig::class)
@IgnoreRuntimeTimeout
class FirmRCA (
    configPath: String? = null,
    id: Int,
    program: Program,
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
    val prog: Program
        get() = program!!

    private lateinit var fuzzwareConf: FuzzwareGatewayConfig
    private val conf: FirmRCAConfig
        get() = config as FirmRCAConfig
    private lateinit var fuzzwareProjectRoot: Path
    private lateinit var firmRCAProjectRoot: Path
    private lateinit var inputFiles: List<Path>
    private lateinit var firmwareInProject: Path

    // In the root of FirmRCA project, we will create a map file to map each input file with an integer,
    // and create many subdirectories named by these integers to save output files of each input file.
    private lateinit var mapFile: Path
    private val inputFileMap: MutableMap<Int, String>
        = (DatabaseClient.getModuleData(id.toLong(), dbTableName, listOf("input_id_map")) as String?)
            ?.let { Json.decodeFromString(it) }
            ?: mutableMapOf()

    private val inputFileReversedMap: MutableMap<String, Int>
        = inputFileMap.entries.associate { (k, v) -> v to k }.toMutableMap()

    @Serializable
    data class FirmRCAResults (
        val results: List<Long>,
        val scores: List<Int>,
        val failed: Boolean,
        val errMsg: String?
    )

    @OptIn(ExperimentalPathApi::class)
    @Throws(IllegalStateException::class)
    override suspend fun startProcess() {

        fuzzwareConf = getTaskData("fuzzware_basic_conf") as? FuzzwareGatewayConfig
            ?: throw IllegalArgumentException("Fuzzware basic config not found, need to run 'FuzzwareGateway' first")

        // Get Fuzzware project root directory and check if it exists
        fuzzwareProjectRoot = Path.of(mainConf.binariesRoot, fuzzwareConf.projectRoot, id.toString(), "pipeline")
        if (fuzzwareProjectRoot.notExists() || fuzzwareProjectRoot.isRegularFile()) {
            logger.error("Fuzzware project root directory not found, you may need to run module " +
                    "FuzzwarePipeline and FuzzwareReplay first")
            failureSign = FAILED
            return
        }

        // If the firmware is not copied into the project, copy it
        if (prog.executableFormat != "Executable and Linking Format (ELF)") {
            val firmwareName = Path.of(getMetadata().originalPath).fileName.toString()
            firmwareInProject = fuzzwareProjectRoot.parent.resolve(firmwareName)
            if (firmwareInProject.notExists())
                Path.of(getMetadata().originalPath).copyTo(firmwareInProject)
        } else
            firmwareInProject = fuzzwareProjectRoot.parent.resolve("parsedELF.bin")

        // Get FirmRCA project root directory and clear it
        firmRCAProjectRoot = fuzzwareProjectRoot.parent.resolve("firmrca")

        if (firmRCAProjectRoot.isRegularFile())
            firmRCAProjectRoot.deleteIfExists()
        else
            firmRCAProjectRoot.deleteRecursively()
        firmRCAProjectRoot.toFile().mkdirs()

        // Check if FirmRCA environment is ready
        if (!checkFirmRCAEnv()) {
            logger.error("Checks for FirmRCA environment failed. There may be something wrong in your FirmRCA")
            failureSign = FAILED
            return
        }

        // Get all input files
        inputFiles =
            if (conf.specifiedTestCases != null) conf.specifiedTestCases!!.map { fuzzwareProjectRoot.resolve(it) }
            else if (conf.classifiedMode) filterInputs()
            else FuzzwareReplay.getAllCrashesPath(fuzzwareProjectRoot)
        if (inputFiles.isEmpty()) {
            logger.info("No input file found")
            return
        }

        mapFile = firmRCAProjectRoot.resolve("id_map.json")

        // Generate datasets for each input
        val allInputResults = inputFiles.mapIndexed { idx, i ->
            val relativePath = Path.of(mainConf.binariesRoot).relativize(i).toString()
            val fuzzGroup: Int = relativePath.split("/")
                .last { it.startsWith("main") }.substringAfter("main").toInt()
            val fuzzConfig: Path = fuzzwareProjectRoot.resolve(
                Path.of("main%03d/config.yml".format(fuzzGroup)))
            val id = if (!inputFileMap.values.contains(relativePath)) {
                inputFileMap[inputFileMap.size + 1] = relativePath
                inputFileReversedMap[relativePath] = inputFileMap.size
                inputFileReversedMap.size
            } else inputFileReversedMap[relativePath]!!
            generateDataset(fuzzConfig, i, id)
            id to runFirmRCA(id)
        }.toMap()

        // Update the database
        updateData(mapOf(
            "input_id_map" to Json.encodeToString(inputFileMap),
            "firmrca_results" to Json.encodeToString(allInputResults)
        ))
    }

    private fun filterInputs(): List<Path> {
        val data = DatabaseClient.getModuleData(
            id.toLong(), "firmrca_classified_results", listOf("path"))

        @Suppress("UNCHECKED_CAST")
        val list = (data["path"] as List<String>).map { Path.of(it) } // TODO: Need tests

        return list
    }

    private fun checkFirmRCAEnv(): Boolean {
        synchronized(checkLock) {
            if (firmRCAEnvCheckDone)
                return firmRCAEnvReady

            // Need to specify FirmRCA root path
            conf.firmRCARoot ?: run {
                logger.error("FirmwareRCA root path not found, you need to specify it.")
                firmRCAEnvCheckDone = true
                return false
            }

            // Need to specify FirmRCA python venv root
            conf.firmRCAPythonVenvRoot ?: run {
                logger.error("FirmRCA python venv not specified. Since FirmRCA uses a changed Fuzzware, we strongly " +
                        "recommend you to create a python venv for it.")
                firmRCAEnvCheckDone = true
                return false
            }

            // Check venv root path
            val venvRoot = Path.of(conf.firmRCAPythonVenvRoot!!)
            if (venvRoot.notExists()) {
                logger.error("FirmwareRCA venv root path not found.")
                firmRCAEnvCheckDone = true
                return false
            }

            // Check `source` command
            val process = ProcessBuilder(
                *fuzzwareConf.cmdPrefix.toTypedArray(), "${fuzzwareConf.cmdPredo} && type source")
                .redirectErrorStream(true).start()
            process.waitFor()
            process.inputStream.bufferedReader().readText().let {
                if (it.contains("not found")) {
                    logger.error("Command 'source' not found")
                    firmRCAEnvCheckDone = true
                    return false
                }
            }

            // Check if 'bin/activate' is existent in venv directory
            if (venvRoot.resolve("bin").resolve("activate").notExists()) {
                logger.error("bin/activate not found in venv directory. A broken venv?")
                firmRCAEnvCheckDone = true
                return false
            }

            // Check if the venv can be activated normally
            val process2 = ProcessBuilder(
                *fuzzwareConf.cmdPrefix.toTypedArray(),
                "${fuzzwareConf.cmdPredo} && " +
                        "source ${venvRoot.resolve("bin").resolve("activate").absolutePathString()}"
            ).redirectErrorStream(true).start()
            process2.waitFor()
            if (process2.exitValue() != 0) {
                logger.error("Failed to activate venv, returned ${process2.exitValue()}")
                firmRCAEnvCheckDone = true
                return false
            }

            // Check if FirmRCA's Fuzzware can run smoothly
            val process3 = ProcessBuilder(
                *fuzzwareConf.cmdPrefix.toTypedArray(),
                "${fuzzwareConf.cmdPredo} && " +
                        "source ${venvRoot.resolve("bin").resolve("activate").absolutePathString()} && " +
                        "fuzzware_harness --help"
            ).redirectErrorStream(true).start()
            process3.waitFor()
            process3.inputStream.bufferedReader().readText().let {
                if (!it.contains("usage: fuzzware_harness")) {
                    logger.error("Failed to run fuzzware, check your FirmRCA environment.")
                    logger.error("fuzzware_harness output: $it")
                    firmRCAEnvCheckDone = true
                    return false
                }
            }

            firmRCAEnvCheckDone = true
            firmRCAEnvReady = true
            return true
        }
    }

    private fun generateDataset(fuzzConfig: Path, fuzzInput: Path, id: Int) {
        val moduleJarPath = allModules["org.iotsplab.akiba.process.FirmRCA"]!!
        val code = JarFile(moduleJarPath.toFile()).use { jar ->
            jar.getEntry("generateDataset.py")?.let { entry ->
                jar.getInputStream(entry).use { stream ->
                    stream.bufferedReader().readText()
                }
            } ?: throw Exception("Failed to read generateDataset.py")
        }.replace("\"", "\\\"")

        logger.info("Ready to generate files required by FirmRCA ...")
        val dirForInput = firmRCAProjectRoot.resolve(id.toString())
        if (dirForInput.notExists())
            dirForInput.toFile().mkdirs()

        val fuzzGroup = fuzzInput.absolutePathString()

        val cmd = "${fuzzwareConf.cmdPredo} && source ${conf.firmRCAPythonVenvRoot!!}/bin/activate && " +
                "python3 -c \"$code\" ${dirForInput.absolutePathString()} " +
                "${fuzzConfig.absolutePathString()} ${fuzzInput.absolutePathString()}"

        val builder = ProcessBuilder(*fuzzwareConf.cmdPrefix.toTypedArray(), cmd)
        logger.info("Ready to generate dataset for firmware ${super.id} input $id ...")
        val process: Process = builder.start()
        process.waitFor()
        logger.debug(process.inputStream.bufferedReader().readText())
        if (process.exitValue() != 0)
            logger.error("Failed to generate dataset for firmware ${super.id} input $id/${inputFiles.size}")
        else
            logger.info("Successfully generated dataset for firmware ${super.id} input $id/${inputFiles.size}")
    }

    private suspend fun runFirmRCA(id: Int): FirmRCAResults = coroutineScope {
        val dirForInput = firmRCAProjectRoot.resolve(id.toString())
        val baseAddress =
            if (prog.executableFormat != "Executable and Linking Format (ELF)")
                getTaskData(conf.baseAddressSource) as Long
            else
                prog.addressFactory.defaultAddressSpace.minAddress.offset
        val instlistLines = Files.lines(dirForInput.resolve("instlist.reverse")).use { it.count() }
        val reverseLines =
            if (conf.maximumInsn > 0 && conf.maximumInsn < instlistLines)
                conf.maximumInsn
            else
                instlistLines

        // FirmRCA executable arguments:
        // 1. state-out.txt (generated from fuzzware argument `--state-out=...`)
        // 2. firmware path
        // 3. instlist.reverse (generated from FirmRCA's Fuzzware and script `generateDataset.py`)
        // 4. memac.bin (generated from fuzzware argument `--trace-out=...`)
        // 5. base address of the firmware
        // 6. max number of instructions got reversed (Got by counting the lines of `instlist.reverse`)
        // 7. max number of instructions got to find root causes (Got by counting the lines of `instlist.reverse`)
        // The output could be very long, so we redirect it to a file.
        val cmd = "${fuzzwareConf.cmdPredo} && source ${conf.firmRCAPythonVenvRoot!!}/bin/activate && " +
                "export LD_LIBRARY_PATH=${conf.firmRCARoot!!}/src/lib && " +
                "timeout ${conf.timeoutForEach} " +
                "${conf.firmRCARoot!!}/src/src/reversenolog " +
                "${dirForInput.resolve("state-out.txt").absolutePathString()} " +
                "${firmwareInProject.absolutePathString()} " +
                "${dirForInput.resolve("instlist.reverse").absolutePathString()} " +
                "${dirForInput.resolve("memac.bin").absolutePathString()} " +
                "0x${baseAddress.toString(16)} " +
                "$reverseLines $reverseLines"

        val builder = ProcessBuilder(*fuzzwareConf.cmdPrefix.toTypedArray(), cmd).redirectErrorStream(true)
        logger.info("Ready to run FirmRCA for firmware ${super.id} input $id/${inputFiles.size} ...")
        val process: Process = builder.start()

        var analysisFinished = false
        val resultLines: MutableList<Pair<Long, Int>> = mutableListOf()     // Address, Score
        val outReader = launch {
            val resRegex = Regex("^Current Instruction at [0-9]+ with score ([0-9]+) is 0x([0-9a-f]+) :.+$")
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line ?: continue
                    logger.trace(line)
                    if (line.startsWith("Finish reverse execution"))
                        analysisFinished = true
                    resRegex.find(line) ?. let {
                        resultLines.add(Pair(it.groupValues[2].toLong(16), it.groupValues[1].toInt()))
                    }
                }
            }
        }
        outReader.join()
        logger.info("Successfully ran FirmRCA for firmware ${super.id} input $id/${inputFiles.size}")

        if (resultLines.isEmpty()) {
            logger.warn("Failed to run FirmRCA for firmware ${super.id} input $id/${inputFiles.size}")
            return@coroutineScope FirmRCAResults(emptyList(), emptyList(), true,
                if (analysisFinished) "analysis failed" else "timeout")
        }

        process.destroyForcibly()
        process.waitFor()

        resultLines.sortBy { -it.second }
        val results = FirmRCAResults(resultLines.map { it.first }, resultLines.map { it.second }, false, null)

        if (conf.deleteAllFilesOnFinish)
            dirForInput.toFile().deleteRecursively()

        return@coroutineScope results
    }

    companion object {
        private var firmRCAEnvCheckDone: Boolean = false
        private var firmRCAEnvReady: Boolean = false
        private val checkLock = Any()

        const val CLASSIFY_VIEW_SQL = """
            SELECT jsonb_agg(path) FROM (
                SELECT *, row_number() OVER (
                    PARTITION BY id, pc, lr ORDER BY random()
                ) AS rn
                FROM fuzzware_replay_crashes WHERE basic_block_cov >= 0.1
            ) WHERE rn = 1
        """

        const val CREATE_VIEW_SQL = """
            SELECT
                fr.id                              AS id,
                im.key                             AS input_id,
                im.value                           AS input_path,
                fr.firmrca_results -> im.key       AS input_results,
                '0x' || to_hex((fr.firmrca_results -> im.key -> 'results' -> 0)::bigint) AS best_match,
                (fr.firmrca_results -> im.key -> 'scores' -> 0)::numeric AS best_score,
                fr.firmrca_results -> im.key ->> 'errMsg' AS err_msg
            FROM firmrca_results fr,
                 json_each(fr.firmrca_results -> 'input_id_map') AS im(key, value);
        """
    }
}