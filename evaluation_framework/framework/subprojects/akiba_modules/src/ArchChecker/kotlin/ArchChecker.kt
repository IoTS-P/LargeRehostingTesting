package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.ProgramInitialization.Companion.VALID_FIRMWARE_FUNCTION_THRESHOLD
import org.iotsplab.akiba.process.ProgramInitialization.Companion.autoAnalyze
import org.iotsplab.akiba.process.ProgramInitialization.Companion.defaultAnalyzeOptions
import org.iotsplab.akiba.utils.FailOnCancelled
import org.iotsplab.akiba.utils.WithTableColumn

@WithTableColumn("arch_suggestion", "text")
@FailOnCancelled
class ArchChecker (
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
): AkibaModule (
    id = id,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    override suspend fun startProcess() {
        try {
            autoAnalyze(prog, defaultAnalyzeOptions(prog), taskGlobalMonitor)

            // If not valid firmware, update the database and skip further processing
            if (prog.listing.getFunctions(true).count() < VALID_FIRMWARE_FUNCTION_THRESHOLD) {
                logger.error("Arch invalid")
                updateData(mapOf("arch_suggestion" to "n/a"))
                failureSign = FAILED
            }
        } catch (_: InterruptedException) {
            logger.warn("Interrupted due to timeout")
        } catch (e: Exception) {
            logger.error("Program init interrupted due to runtime error: ${e.message}")
            failureSign = FAILED
        }
    }
}