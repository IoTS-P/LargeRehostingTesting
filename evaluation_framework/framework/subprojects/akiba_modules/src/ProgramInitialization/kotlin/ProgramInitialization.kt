package org.iotsplab.akiba.process

import ghidra.app.plugin.core.analysis.AutoAnalysisManager
import ghidra.framework.options.Options
import ghidra.program.model.listing.Program
import ghidra.program.util.GhidraProgramUtilities
import ghidra.util.task.TaskMonitor
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule

/**
 * ProgramInitialization: Check the file size and auto-analyze the program
 *
 * @author: Hornos3, Hornos3@github.com
 */
class ProgramInitialization(
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!
    lateinit var sqlLine: List<Any?>

    override suspend fun startProcess() {
        try {
            // Check file size, skip if too large
            if (usingFile.length() > 1024 * 1024 * 10) {
                logger.warn("File ${usingFile.name} too large (larger than 10M), skipped.")
                updateErr("File too large")
                failureSign = FAILED
                return
            }

            autoAnalyze(prog, monitor = taskGlobalMonitor)

            // If not valid firmware, update the database and skip further processing
            val functionsGot = prog.listing.getFunctions(true).count()
            if (functionsGot < VALID_FIRMWARE_FUNCTION_THRESHOLD) {
                logger.error("Not a valid firmware (Only got $functionsGot functions)")
                updateErr("Not a valid firmware (Only got $functionsGot functions)")
                failureSign = FAILED
            }
        } catch (e: Exception) {
            logger.error("Program init interrupted due to runtime error: ${e.message}")
            failureSign = FAILED
        }
    }

    companion object {
        const val VALID_FIRMWARE_FUNCTION_THRESHOLD = 10

        // <Option name, Option value>
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

        fun defaultAnalyzeOptions(program: Program): Options {
            return program.getOptions("Analyzers")
        }

        /**
         * Auto analyze the program in timeout (seconds)
         */
        @Throws(InterruptedException::class)
        fun autoAnalyze(program: Program, options: Options = initializeAnalyzeOptions(program), monitor: TaskMonitor) {
            // Auto analysis
            // The managers has a map to store, to avoid ConcurrentModificationException, we cannot let
            // `getAnalysisManager` and `reAnalyzeAll` to run in parallel threads at the same time
            val aam: AutoAnalysisManager = AutoAnalysisManager.getAnalysisManager(program)

            aam.initializeOptions(options)

            aam.reAnalyzeAll(null)
            aam.startAnalysis(monitor)    // When the time is out, the task will be cancelled automatically
            aam.cancelQueuedTasks()
            GhidraProgramUtilities.markProgramAnalyzed(program)     // mark this program as auto-analyzed
        }
    }
}