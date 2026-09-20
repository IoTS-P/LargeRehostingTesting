package org.iotsplab.akiba.process

import ghidra.app.plugin.core.analysis.AutoAnalysisManager
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.FailOnCancelled
import org.iotsplab.akiba.utils.assembly.DisasmHelper
import org.iotsplab.akiba.utils.function.allInstructions
import org.iotsplab.akiba.utils.memory.MemoryUtil

@WithTableColumn("has_svc", "BOOLEAN")
@WithTableColumn("has_pendsv", "BOOLEAN")
@FailOnCancelled
class HasRTOS (
    configPath: String? = null,
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

    override suspend fun startProcess() {
        try {
            when {
                Regex("ARM:(LE|BE):32:.+").matches(prog.languageID.idAsString) -> ARMHasRTOS()
                else -> logger.error("Unsupported language")
            }
        } catch (e: Exception) {
            logger.error("Exception occurred: ${e.message}")
            failureSign = FAILED
        }
    }

    @Throws(Exception::class)
    private suspend fun ARMHasRTOS() {
        val api = FlatProgramAPI(prog)

        val baseAddress = api.toAddr(getTaskData("arm_base_finder_results.base_address") as Long)
        val ivtStart = api.toAddr(getTaskData("arm_base_finder_results.ivt_start") as Long)

        MemoryUtil.moveAllBlocksInOffset(prog.memory, baseAddress.offset)

        // SVC interrupt vector
        val svcInt = prog.memory.getInt(ivtStart.add(4 * 11))
        logger.debug("SVC: ${svcInt.toString(16)}")
        // PendSV interrupt vector
        val pendSVInt = prog.memory.getInt(ivtStart.add(4 * 14))
        logger.debug("PendSV: ${pendSVInt.toString(16)}")

        val svcIntFunc: Function =
            prog.listing.getFunctionAt(api.toAddr(svcInt - 1)) ?: run {
                prog.listing.getInstructionAt(api.toAddr(svcInt - 1)) ?. let {
                    val aam = AutoAnalysisManager.getAnalysisManager(program)
                    aam.createFunction(it.address, true)
                    aam.startAnalysis(taskGlobalMonitor)
                    prog.listing.getFunctionAt(it.address)
                } ?: run {
                    DisasmHelper(prog).disasmFunction(
                        api.toAddr(svcInt - 1), monitor = taskGlobalMonitor) ?: run {
                        logger.error("Failed to create function at SVC interrupt vector, the ISR table may be wrong.")
                        return
                    }
                }
            }
        val pendSVIntFunc: Function =
            prog.listing.getFunctionAt(api.toAddr(pendSVInt - 1)) ?: run {
                prog.listing.getInstructionAt(api.toAddr(svcInt - 1)) ?. let {
                    val aam = AutoAnalysisManager.getAnalysisManager(program)
                    aam.createFunction(it.address, true)
                    aam.startAnalysis(taskGlobalMonitor)
                    prog.listing.getFunctionAt(it.address)
                } ?: run {
                    DisasmHelper(prog).disasmFunction(
                        api.toAddr(pendSVInt - 1), monitor = taskGlobalMonitor) ?: run {
                        logger.error("Failed to create function at PendSV interrupt vector, the ISR table may be wrong.")
                        return
                    }
                }
        }

        logger.info("has SVC: ${svcIntFunc.allInstructions().size > 3}")
        logger.info("has PendSV: ${pendSVIntFunc.allInstructions().size > 3}")

        updateData(mapOf(
            "has_svc" to (svcIntFunc.allInstructions().size <= 3),
            "has_pendsv" to (pendSVIntFunc.allInstructions().size <= 3))
        )
    }
}