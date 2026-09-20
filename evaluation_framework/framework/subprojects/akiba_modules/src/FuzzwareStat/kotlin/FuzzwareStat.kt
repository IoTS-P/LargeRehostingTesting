package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.function.allBasicBlockStarts
import org.iotsplab.akiba.utils.memory.MemoryUtil
import java.nio.file.Path

@WithTableColumn("total_cov", "DOUBLE PRECISION")
@WithTableColumn("input_covs", "TEXT")
@WithTableColumn("total_execs_done", "BIGINT")
@WithTableColumn("total_execs_per_second", "DOUBLE PRECISION")
@IgnoreRuntimeTimeout
@WithConfigClass(FuzzwareStatConfig::class)
class FuzzwareStat (
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
    defaultConfig = FuzzwareStatConfig(),
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!
    val conf: FuzzwareStatConfig
        get() = config as FuzzwareStatConfig
    val pipelineRoot: Path = Path.of(mainConf.binariesRoot, conf.projectRoot, id.toString(), "pipeline")
    val allSet: MutableSet<Long> = mutableSetOf()
    val superset: MutableSet<Long> = mutableSetOf()
    val inputCovs: MutableMap<String, Double> = mutableMapOf()

    override suspend fun startProcess() {
        getBasicBlockCoverage()
        getFuzzerPerformance()
    }

    private suspend fun getBasicBlockCoverage() {
        rebase()

        prog.memory.blocks.filter {
            it.isLoaded && it.isExecute
        }.forEach {
            println("${it.start} - ${it.end}")
        }

        allSet.addAll(prog.functionManager.getFunctions(true).map {
                func -> func?.allBasicBlockStarts()?.map { it.offset } ?: listOf() } .flatMap { it })

        pipelineRoot.toFile().listFiles { it.name.startsWith("main") && it.isDirectory }?.forEach { main ->
            main.listFiles { it.name =="fuzzers" && it.isDirectory }?.firstOrNull()
                ?.listFiles { it.name.startsWith("fuzzer") && it.isDirectory } ?.forEach { fuzzer ->
                val tracesDir = fuzzer.listFiles { it.name == "traces" && it.isDirectory }?.firstOrNull() ?:return@forEach
                tracesDir.listFiles { it.name.startsWith("bblset") && it.isFile }
                    ?.forEach { bblset ->
                    logger.trace("Found ${bblset.name}")
                    val lines = bblset.readLines()
                    var basicBlocks: List<Long> = lines.subList(0, lines.size - 1)
                        .map { addr -> addr.toLong(16) }
                        .distinct()
                    logger.trace("Unfiltered basic blocks got: ${basicBlocks.size}")
                    basicBlocks = basicBlocks.filter { addr -> addressInMapped(addr) }
                    logger.trace("Filtered basic blocks got: ${basicBlocks.size}")
                    superset.addAll(basicBlocks)
                    allSet.addAll(basicBlocks)
                    inputCovs["${fuzzer.name}/${bblset.name}"] = basicBlocks.size.toDouble()
                }
            }
        }

        inputCovs.forEach { k, v -> inputCovs[k] = v / allSet.size }

        logger.info("Total coverage: ${superset.size.toDouble() / allSet.size}")

        updateData(mapOf(
            "total_cov" to (superset.size.toDouble() / allSet.size),
            "input_covs" to Json.encodeToString(inputCovs)
        ))

        unrebase()
    }

    private fun getFuzzerPerformance() {
        var totalTime: Double = 0.0
        var totalExec: Long = 0

        pipelineRoot.toFile().listFiles { it.name.startsWith("main") && it.isDirectory }?.forEach { main ->
            main.listFiles { it.name =="fuzzers" && it.isDirectory }?.firstOrNull()
                ?.listFiles { it.name.startsWith("fuzzer") && it.isDirectory } ?.forEach { fuzzer ->
                val statFile = fuzzer.toPath().resolve("fuzzer_stats")
                if (statFile.toFile().exists()) {
                    var exec: Long = 0
                    statFile.toFile().readLines().forEach { line ->
                        val (key, value) = line.split(":").map { it.trim() }
                        when (key) {
                            "execs_done" -> {
                                exec = value.toLong()
                                totalExec += value.toLong()
                            }

                            "execs_per_sec" -> totalTime += (exec / value.toDouble())
                        }
                    }
                }
            }
        }

        updateData(mapOf(
            "total_execs_done" to totalExec,
            "total_execs_per_second" to if (totalTime != 0.0) (totalExec / totalTime) else 0.0
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
            addr >= it.start.offset && addr < it.end.offset
        }
    }
}