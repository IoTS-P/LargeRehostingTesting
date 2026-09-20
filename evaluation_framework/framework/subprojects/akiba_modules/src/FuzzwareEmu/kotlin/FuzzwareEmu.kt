package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.DataConsumer
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.FailOnCancelled
import org.iotsplab.akiba.utils.WithConfigClass
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.isRegularFile

@WithTableColumn("distinct_func_called", "INTEGER")
@DataConsumer<FuzzwareGatewayConfig>("fuzzware_basic_conf")
@WithConfigClass(FuzzwareEmuConfig::class)
@FailOnCancelled
class FuzzwareEmu (
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    configPath = configPath,
    defaultConfig = FuzzwareEmuConfig(),
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    val conf: FuzzwareEmuConfig
        get() = config as FuzzwareEmuConfig
    val distinctFunctionCalled: MutableSet<Long> = mutableSetOf()

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    override suspend fun startProcess() {
        try {
            if (!(callTaskAPI(FuzzwareGateway::activateEnv) as Boolean)) {
                logger.error("Failed to activate Fuzzware environment, exited")
                return
            }

            val emuConf = callTaskAPI(FuzzwareGateway::generateFuzzwareConfig,
                0L,
                prog.memory.blocks.minBy { it.start }.start.offset
                , false, taskGlobalMonitor) as Path
            startEmu(emuConf)
        } catch (e: Exception) {
            logger.error("Failed to run fuzzware: ${e.message}")
            failureSign = FAILED
            return
        }

        if (conf.traceFunc)
            updateData(mapOf("distinct_func_called" to distinctFunctionCalled.size.toLong()))
    }

    @Throws(IllegalStateException::class)
    private suspend fun startEmu(config: Path) = coroutineScope {
        val elfPath = Path.of(mainConf.binariesRoot).resolve(
            getTaskData("convert_firm_to_elf_results.elf_path") as? String
                ?: throw IllegalArgumentException("ELF path not found"))

        val basicConf = getTaskData("fuzzware_basic_conf") as? FuzzwareGatewayConfig
            ?: throw IllegalArgumentException("Fuzzware basic config not found, need to run 'FuzzwareGateway' first")

        var cmd = "${basicConf.cmdPredo} && workon ${basicConf.venv} && " +
                "fuzzware emu \"${usingFile.absolutePath}\" -c \"${config.absolutePathString()}\" -dv " +
                "--fuzz-consumption-timeout 0 -l ${conf.maxBlockExecuted}"

        if (conf.traceFunc)
            cmd += " -t"
        if (conf.traceMemory)
            cmd += " -M"

        val outFile = logDir.resolve("fuzzware_emu_output.txt").toFile()
        val errFile = logDir.resolve("fuzzware_emu_error.txt").toFile()

        logger.info("Shell command: {}", cmd)

        val builder = ProcessBuilder(*basicConf.cmdPrefix.toTypedArray(), cmd)
            .redirectError(errFile)
        val process: Process = builder.start()

        val outReader = launch {
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (conf.traceFunc && line?.startsWith("Calling function: ") ?: false) {
                        val pc = (line.substringAfter("PC=0x").substringBefore(",")).toLong(16)
                        distinctFunctionCalled.add(pc)
                    } else
                        line ?.let { outFile.appendText(it + "\n") }
                }
            }
            if (conf.traceFunc)
                logger.info("Called ${distinctFunctionCalled.size} distinct functions")
        }

        outReader.start()

        val timeout = if (conf.timeout <= 0) null else conf.timeout.toLong()
        withContext(Dispatchers.IO) {
            if (timeout == null) {
                process.waitFor()
            } else {
                process.waitFor(timeout, TimeUnit.SECONDS)
            }
        }

        outReader.join()

        if (outFile.toPath().isRegularFile()) {
            if (outFile.readLines().any {
                it.contains("Ran into instruction limit of ${conf.maxBlockExecuted}") 
            }) {
                logger.info("Congratulations! Fuzzware exited with no error")
                return@coroutineScope
            }

            // There could be several errors
            logger.warn("Fuzzware exited with some error: ")
            val lines = outFile.readLines().toMutableList()
            lines.addAll(errFile.readLines())
            lines.forEach {
                when {
                    it.contains("INVALID READ") ||
                    it.contains("INVALID Write") ||
                    it.contains("Invalid memory read") ||
                    it.contains("Invalid memory write") ||
                    it.contains("INVALID FETCH") ||
                    it.contains("Execution failed with error code") ||
                    it.contains("Emulation stopped using just the prefix input") ||
                    it.contains("ERROR:emulator:") -> {
                        logger.warn(it)
                        updateErr(it.trimIndent())
                        return@coroutineScope
                    }
                }
            }
            updateErr("Other error")
        }
    }
}