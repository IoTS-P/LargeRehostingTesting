package org.iotsplab.akiba.process

import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Program
import ghidra.program.model.pcode.PcodeOp
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.DataProducer
import org.iotsplab.akiba.utils.FailOnCancelled
import org.iotsplab.akiba.utils.TaskInterface
import org.iotsplab.akiba.utils.function.allInstructions
import org.iotsplab.akiba.utils.highFunction.getDefaultDecompResult
import org.iotsplab.akiba.utils.reference.FuncCallXrefGraph

/**
 * EntryFinder: Finder trying to find entry point through function-level CFG graph
 *
 * Detailed process:
 *  - Get all calling xrefs of functions and build a call graph (excluding recursive calls)
 *  - Filter vertices that have no caller
 *  - Try to filter further through checking if there is any return instruction in the function
 *
 * @author: Hornos3, Hornos3@github.com
 */
@DataProducer<List<Function>>("entry_point_candidates")
@FailOnCancelled
class EntryFinder(
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
        updateResult()
    }

    @TaskInterface
    suspend fun updateResult() {
        val graph = FuncCallXrefGraph.fromProgram(prog)
        var candidates = graph.getFunctionsOfNoCaller()
        // Try to filter through checking if there is any return instruction in the function
        candidates = candidates.filter { f ->
            f.allInstructions().none { inst -> inst.pcode.any { pc -> pc.opcode == PcodeOp.RETURN } }
        }.ifEmpty { candidates }
        // Filter those who don't call others
        val leafFunctions = graph.getLeafFunctions()
        candidates = candidates.filter { f -> !leafFunctions.contains(f) }
        // Filter those who has arguments (Entry point function doesn't have any argument)
        // Note: we need to do some further analysis to get real function prototype
        candidates = candidates.filter {
            it.getDefaultDecompResult().highFunction.functionPrototype.numParams == 0
        }

        if (candidates.isEmpty())
            logger.warn("No entry point candidates found, check your decompiler results")
        else
            logger.debug("Update completed, entry point candidates: {}", candidates)

        setTaskData("Candidate entries", candidates)
    }
}