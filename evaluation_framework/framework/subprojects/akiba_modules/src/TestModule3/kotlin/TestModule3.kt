package org.iotsplab.akiba.process

import ghidra.app.plugin.core.analysis.AutoAnalysisManager
import ghidra.framework.options.Options
import ghidra.program.model.listing.Program
import ghidra.program.util.GhidraProgramUtilities
import ghidra.util.task.TimeoutTaskMonitor
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.memory.MemoryUtil
import java.util.concurrent.TimeUnit
import kotlin.collections.component1
import kotlin.collections.component2

@WithTableColumn("function_count", "integer")
@WithTableColumn("data_1", "jsonb")
@WithTableColumn("data_2", "bytea")
class TestModule3 (
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
): AkibaModule(
    id = id,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    override suspend fun startProcess() {
        autoAnalyzeInTimeout(program!!, 180, initializeAnalyzeOptions(program!!))

        logger.info("Function count: ${program!!.functionManager.functionCount}")
        updateData(mapOf(
            "function_count" to program!!.functionManager.functionCount,
            "data_1" to mapOf(
                "aaa" to 1,
                "bbb" to 2
            ),
            "data_2" to byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        ))

        MemoryUtil.moveAllBlocksInOffset(program!!.memory, 0x1000)
    }

    private val extraAnalyzerOptions: Map<String, Any> = mapOf(
        "Aggressive Instruction Finder" to true
    )

    private fun initializeAnalyzeOptions(program: Program): Options {
        val options = program.getOptions("Analyzers")
        extraAnalyzerOptions.let {
            it.forEach { (optionName, optionValue) ->
                check(options.contains(optionName)) { "Option $optionName not found in analyzer options" }
                check(options.getType(optionName).isCompatible(optionValue)) {
                    "Option $optionName value type $optionValue unmatched"
                }
                options.putObject(optionName, optionValue)
            }
        }

        return options
    }

    @Throws(InterruptedException::class)
    fun autoAnalyzeInTimeout(program: Program, timeout: Int,
                             options: Options = initializeAnalyzeOptions(program),
                             timeoutHandler: (() -> Unit)? = null) {
        // Auto analysis
        // The managers has a map to store, to avoid ConcurrentModificationException, we cannot let
        // `getAnalysisManager` and `reAnalyzeAll` to run in parallel threads at the same time
        val aam: AutoAnalysisManager = AutoAnalysisManager.getAnalysisManager(program)

        aam.initializeOptions(options)

        aam.reAnalyzeAll(null)
        val monitor = TimeoutTaskMonitor.timeoutIn(
            if (timeout > 0) timeout.toLong() else Int.MAX_VALUE.toLong(), TimeUnit.SECONDS)
        aam.startAnalysis(monitor)
        aam.cancelQueuedTasks()
        GhidraProgramUtilities.markProgramAnalyzed(program)     // mark this program as auto-analyzed

        if (monitor.didTimeout()) {
            if (timeoutHandler != null)
                timeoutHandler()
            return
        }
    }
}