package org.iotsplab.akiba.process

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.HoedurFuzz.Companion.parseHoedurTimeout
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.function.allBasicBlockStarts
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.Collections.synchronizedList
import java.util.concurrent.ForkJoinPool
import kotlin.coroutines.coroutineContext
import kotlin.io.path.absolutePathString
import kotlin.io.path.appendLines
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.notExists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.math.roundToLong
import kotlin.system.measureTimeMillis

/**
 * A module to run multifuzz all in one (fuzz, replay, statistic arrangements)
 */
@WithTableColumn("set_fuzz_time", "INTEGER")
@WithTableColumn("actual_fuzz_time", "INTEGER")
@WithTableColumn("basic_blocks_visited", "INTEGER")
@WithTableColumn("coverage", "DOUBLE PRECISION")
@WithTableColumn("crash_count", "INTEGER")
@WithTableColumn("hang_count", "INTEGER")
@WithTableColumn("replay_data", "JSONB")
@WithConfigClass(MultiFuzzConfig::class)
@IgnoreRuntimeTimeout
class MultiFuzz (
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    configPath = configPath,
    defaultConfig = MultiFuzzConfig(),
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!
    val conf: MultiFuzzConfig
        get() = config as MultiFuzzConfig
    private lateinit var runConfig: Path
    private lateinit var multifuzzWorkdir: Path
    private lateinit var multifuzzOutputDir: Path
    private lateinit var basicConf: FuzzwareGatewayConfig
    private var allBasicBlocks: MutableList<Address> = prog.listing.getFunctions(true).flatMap { func ->
        func.allBasicBlockStarts()
    }.toMutableList()
    private lateinit var visitedBlocks: List<Long>

    @Throws(IllegalArgumentException::class)
    override suspend fun startProcess() {
        conf.multifuzzRoot ?.let {
            if (!Path.of(it).isDirectory())
                throw IllegalArgumentException("Hoedur root directory not found")
        } ?: throw IllegalArgumentException("Multifuzz root is not set")

        basicConf = getTaskData("fuzzware_basic_conf") as? FuzzwareGatewayConfig
            ?: throw IllegalArgumentException("Fuzzware basic config not found, need to run 'FuzzwareGateway' first")

        multifuzzWorkdir = Path.of(mainConf.binariesRoot, basicConf.projectRoot, id.toString())
        multifuzzOutputDir = multifuzzWorkdir.resolve("output")
        if (conf.doFuzz) {
            if (multifuzzWorkdir.exists())
                multifuzzWorkdir.toFile().deleteRecursively()
            multifuzzWorkdir.createDirectories()
            if (multifuzzOutputDir.exists())
                multifuzzOutputDir.toFile().deleteRecursively()
            multifuzzOutputDir.createDirectories()
        }

        try {
            runConfig =
                if (prog.executableFormat == "Executable and Linking Format (ELF)")
                    callTaskAPI(FuzzwareGateway::generateFuzzwareConfigForELF, true) as Path
                else {
                    callTaskAPI(FuzzwareGateway::generateFuzzwareConfig,
                        getTaskData(conf.baseAddressSource) as Long,
                        conf.ivtStartSource ?.let { getTaskData(it) as Long }
                                            ?: prog.memory.blocks.first().start.offset,
                        false, taskGlobalMonitor
                    ) as Path
                }
        } catch (e: Exception) {
            logger.error("Failed to generate fuzzware config: ${e.message}")
            failureSign = FAILED
            return
        }

        updateData(mapOf("set_fuzz_time" to parseHoedurTimeout(conf.runFor)))
        try {
            if (conf.doFuzz) {
                if (runMultifuzz()) {
                    updateCrashAndHangCount()
                    updateBasicBlockSet()
                    updateTotalBBCoverage()
                }
            }
            if (conf.doReplay) {
                replayCrashesAndHangs()
            }
        } catch (e: Exception) {
            logger.error("Failed to run multifuzz: ${e.message}")
            e.printStackTrace()
            failureSign = FAILED
            return
        }
    }

    private suspend fun runMultifuzz(): Boolean = coroutineScope {
        val fuzzLog = multifuzzWorkdir.resolve("fuzz.log")
        if (fuzzLog.exists())
            fuzzLog.deleteExisting()
        fuzzLog.writeText("")

        if (prog.executableFormat != "Raw Binary") {
            logger.error("Multifuzz currently only supports raw binaries")
            return@coroutineScope false
        }

        logger.info("Ready to run Multifuzz to fuzz...")
        val multifuzzCmd = """
            ${basicConf.cmdPredo} && cd ${Path.of(conf.multifuzzRoot!!).absolutePathString()} && \
            ${conf.cargoPath} run --release -- ${multifuzzWorkdir.absolutePathString()}
        """.trimIndent()

        val builder = ProcessBuilder(*basicConf.cmdPrefix.toTypedArray(), multifuzzCmd)
            .redirectErrorStream(true)
        builder.environment()["WORKDIR"] = multifuzzOutputDir.absolutePathString()
        builder.environment()["COVERAGE_MODE"] = "blocks"
        builder.environment()["RUN_FOR"] = conf.runFor
        val process: Process = builder.redirectErrorStream(true).start()

        val millis = measureTimeMillis {
            val outReader = launch {
                var lines = 0
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line ?: continue
                            if (!statusOutputRegex.matches(line) && !line.isEmpty()) {
                                logger.trace(line)
                                fuzzLog.appendLines(listOf(line))   // TODO: Write multiple lines in one shot?
                                lines++
                                // A simple way to clear the log file to avoid it being too large
                                if (lines >= MAXIMUM_LOG_LINES)
                                    fuzzLog.writeText("")
                            }
                        }
                    }
                } catch (_: IOException) {} // Stream closed
            }
            outReader.start()
            outReader.join()
        }

        // If multifuzz encounters an initial emulation error, it will exit abnormally
        process.waitFor()
        updateData(mapOf("actual_fuzz_time" to (millis.toDouble() / 1000.0).roundToLong()))
        if (process.exitValue() != 0) {
            logger.error("Multifuzz failed, see log file for more information")
            updateErr("Multifuzz failed")
            return@coroutineScope false
        }

        return@coroutineScope true
    }

    private data class CovData(
        val idx: Int,
        val addr: Long,
        val count: Long,
    )

    private fun updateCrashAndHangCount() {
        val crashDir = multifuzzOutputDir.resolve("crashes")
        val hangDir = multifuzzOutputDir.resolve("hangs")
        val crashCount = if (crashDir.isDirectory()) crashDir.toFile().listFiles()?.size ?: 0 else 0
        val hangCount = if (hangDir.isDirectory()) hangDir.toFile().listFiles()?.size ?: 0 else 0
        updateData(mapOf("crash_count" to crashCount, "hang_count" to hangCount))
    }

    @Throws(IllegalStateException::class)
    private fun updateBasicBlockSet() {
        val covFile = multifuzzOutputDir.resolve("coverage")
        if (covFile.notExists()) {
            logger.warn("Coverage file not found, maybe Multifuzz exited early due to bad configs")
            return
        }
        val data = jacksonObjectMapper().readValue<List<CovData>>(covFile.toFile())
        visitedBlocks = data.map { it.addr }
        logger.info("Total basic blocks visited: ${data.size}")

        // Add basic blocks that is not existed in `allBasicBlocks` but in `covInfo.foundBasicBlocks`
        allBasicBlocks.addAll(visitedBlocks.map { FlatProgramAPI(prog).toAddr(it) })
        allBasicBlocks = allBasicBlocks.distinctBy { it.offset }.toMutableList()
        check(allBasicBlocks.isNotEmpty()) { "No basic blocks found in program #$id" }
    }

    @Throws(IllegalStateException::class)
    private fun updateTotalBBCoverage() {
        val coverage = visitedBlocks.size.toDouble() / allBasicBlocks.size
        logger.info("Coverage of firmware #$id: $coverage")

        updateData(mapOf("basic_blocks_visited" to visitedBlocks.size, "coverage" to coverage))
    }

    data class ReplayData (
        val path: String,
        val errMsg: String?,
        val coverage: Double,
        val pc: Long,
        val lr: Long,
        val stackTrace: List<String>
    )

    /**
     * Replay crash inputs and hang inputs
     *
     * an output example:
     * ```text
     * [icicle] exited with: UnhandledException(code=ReadUnmapped, value=0x3bc00) (icount = 24444), active_irq = 0
     * [icicle] callstack:
     * 0x000001d856: FUN_0001d810+0x46
     * 0x000001d84b: FUN_0001d810+0x3a
     * 0x000001b15a: FUN_0001b01c+0x13d
     *
     * [icicle] last blocks:
     * 0x1d852: FUN_0001d810+0x42
     * 0x1d84a: FUN_0001d810+0x3a
     * 0x20470: FUN_00020464+0xc
     * 0x20464: FUN_00020464
     * 0x1d840: FUN_0001d810+0x30
     * 0x1d838: FUN_0001d810+0x28
     * 0x20b60: FUN_00020b40+0x20
     * 0x20b92: FUN_00020b40+0x52
     * 0x20b80: FUN_00020b40+0x40
     * 0x20b80: FUN_00020b40+0x40
     *
     * registers:
     * r0   = 0x00000000 r1   = 0x0000000a r2   = 0x20002f52 r3   = 0x0003bc00
     * r4   = 0x461cdec4 r5   = 0x00000000 r6   = 0x00000000 r7   = 0x00000000
     * r8   = 0x00000000 r9   = 0x00000000 r10  = 0x00000000 r11  = 0x00000000
     * r12  = 0x20004000 sp   = 0x20003fe0 lr   = 0x0001d84b pc   = 0x0001d856
     * cpsr = 0x00000000
     * CY   = 0x00 ZR   = 0x00 NG   = 0x00 OV   = 0x00
     * ```
     *
     * an example of trace file:
     * ```csv
     * pc,sp,icount,fuzz_offset,last_read,last_value
     * 0x1b200,0x20004000,0,0,0x0,0x0
     * 0x21f28,0x20004000,6,0,0x0,0x0
     * 0x21f54,0x20003ff8,12,0,0x0,0x0
     * 0x21f82,0x20003ff8,16,0,0x0,0x0
     * 0x21fa6,0x20003ff8,20,0,0x0,0x0
     * ......
     * ```
     */
    private suspend fun replayCrashesAndHangs() {
        concurrentSemaphore ?:run { concurrentSemaphore = Semaphore(conf.replayThread) }
        dispatcher = ForkJoinPool(conf.replayThread).asCoroutineDispatcher()
        val data = synchronizedList(mutableListOf<ReplayData>())

        // Added basic blocks not got by Ghidra but visited
        if (!conf.doFuzz)
            updateBasicBlockSet()

        // Replay crash inputs
        val crashTraceDir = multifuzzOutputDir.resolve("crash_traces")
        crashTraceDir.createDirectories()
        coroutineScope {
            multifuzzOutputDir.resolve("crashes").toFile().listFiles()?.sorted()?.map { it ->
                async(dispatcher!!) {
                    val traceFile = crashTraceDir.resolve(it.name + ".trace.txt")
                    data.add(replayOne(it, traceFile))
                }
            }?.awaitAll()
        }

        val hangTraceDir = multifuzzOutputDir.resolve("hang_traces")
        hangTraceDir.createDirectories()
        coroutineScope {
            multifuzzOutputDir.resolve("hangs").toFile().listFiles()?.sorted()?.map { it ->
                async(dispatcher!!) {
                    val traceFile = hangTraceDir.resolve(it.name + ".trace.txt")
                    data.add(replayOne(it, traceFile))
                }
            }?.awaitAll()
        }

        updateData(mapOf("replay_data" to jacksonObjectMapper().writeValueAsString(data)))
    }

    @Throws(IllegalStateException::class)
    private fun replayOne(inputPath: File, outTracePath: Path): ReplayData {
        val path = multifuzzOutputDir.relativize(inputPath.toPath())

        logger.debug("Replaying {}", path)
        val cmd = """
                ${basicConf.cmdPredo} && cd ${Path.of(conf.multifuzzRoot!!).absolutePathString()} && \
                ${conf.cargoPath} run --release -- ${multifuzzWorkdir.absolutePathString()}
            """.trimIndent()
        val builder = ProcessBuilder(*basicConf.cmdPrefix.toTypedArray(), cmd)
        builder.environment()["TRACE_PATH"] =
            outTracePath.absolutePathString()
        builder.environment()["REPLAY"] = inputPath.absolutePath
        val proc = builder.redirectErrorStream(true).start()

        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        proc.destroyForcibly()
        try {
            val errMsg = output.substringAfter("[icicle] exited with: ", "\n")
                .substringBefore("\n")
            val stackTrace = output.substringAfter("[icicle] callstack:\n", "\n")
                .substringBefore("\n\n").split("\n")
            val pc = output.substringAfter("pc   = 0x", "-1\n")
                .substringBefore("\n").toLong(16)
            val lr = output.substringAfter("lr   = 0x", "-1\n")
                .substringBefore(" ").toLong(16)

            val visited = outTracePath.readLines().let { it.subList(1, it.size) }.map { line ->
                line.split(",")[0].substringAfter("0x").toLong(16)
            }.distinct()
            val coverage = visited.size.toDouble() / allBasicBlocks.size

            logger.debug("Finished {}: {}", path, errMsg)
            return ReplayData(path.toString(), errMsg, coverage, pc, lr, stackTrace)
        } catch (_: Exception) {
            logger.error("Output format error, output:\n$output")
            throw IllegalStateException("Output format error")
        }
    }

    companion object {
        private val statusOutputRegex = Regex(
            "^(.+rate=.+crash=.+hang=.+cov=.+in=.+cycle=.+)|(===.+)|(.+: 0x[0-9a-f]+)|(0x[0-9a-f]+: [0-9a-f]+.*)|" +
                    "(ASSERTION ERROR: 'Expected SYSCTL_AIRCR write key to be correct, but it is not equal to VECTKEY_HIWORD_MAGIC_WRITE')$")
        const val MAXIMUM_LOG_LINES = 1_000_000

        var concurrentSemaphore: Semaphore? = null
        var dispatcher: ExecutorCoroutineDispatcher? = null
    }
}