package org.iotsplab.akiba.module

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ghidra.app.plugin.core.analysis.AutoAnalysisManager
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Program
import ghidra.program.util.GhidraProgramUtilities
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.utils.FailOnCancelled
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.TaskInterface
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.string.allStrings

@WithTableColumn("strings", "JSONB")
@FailOnCancelled
class AkibaExample(
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = "example_table"
): AkibaModule(
    id = id,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName     // Because we use PostgreSQL to save data, the table name should be small cased
) {
    override suspend fun startProcess() {
        // Auto analysis by Ghidra
        val aam: AutoAnalysisManager = AutoAnalysisManager.getAnalysisManager(program)

        aam.initializeOptions(program!!.getOptions("Analyzers"))

        aam.reAnalyzeAll(null)
        aam.startAnalysis(taskGlobalMonitor)    // When the time is out, the task will be cancelled automatically
        aam.cancelQueuedTasks()
        GhidraProgramUtilities.markProgramAnalyzed(program)     // mark this program as auto-analyzed

        // Find all strings and update it to database
        val result = program!!.allStrings().map { it.value.trim('\u0000') }
        logger.info("Found strings: $result")
        updateData(mapOf(
            "strings" to jacksonObjectMapper().writeValueAsString(result)))
    }

    @TaskInterface
    fun findMainFunction(): Function? {
        return program!!.listing.getFunctions(true).firstOrNull {
            it.name == "main"
        }
    }
}