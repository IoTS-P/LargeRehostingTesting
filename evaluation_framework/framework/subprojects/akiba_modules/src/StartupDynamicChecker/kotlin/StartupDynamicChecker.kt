package org.iotsplab.akiba.process

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.lang.Register
import ghidra.program.model.listing.Program
import ghidra.util.task.TaskMonitor
import kotlinx.coroutines.coroutineScope
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.iotgs.BasicLoadMetadata
import org.iotsplab.akiba.utils.DataConsumer
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.FailOnCancelled
import org.iotsplab.akiba.utils.emulator.StepInEmulator
import org.iotsplab.akiba.utils.memory.MemoryUtil
import java.math.BigInteger

@DataConsumer<Address>("load_metadata")
@WithTableColumn("entry_valid", "BOOLEAN")
@FailOnCancelled
class StartupDynamicChecker(
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
    private lateinit var startupInfo: BasicLoadMetadata
    private val api: FlatProgramAPI = FlatProgramAPI(program)

    private val baseAddress: Address
        get() = startupInfo.baseAddress
    private val entryPoint: Address
        get() = startupInfo.entryPoint

    private lateinit var emulator: CheckerEmulator

    override suspend fun startProcess() {
        coroutineScope {
            startupInfo = getTaskData("load_metadata") as BasicLoadMetadata
        }

        // We need to init startupInfo and then can init emulator
        emulator = CheckerEmulator(
            program = prog,
            baseAddress = null,
            entryPoint = entryPoint,
            masterStackPointer = startupInfo.initialSP!!,
            logger = logger,
            monitor = taskGlobalMonitor
        )
        emulator.go()

        updateData(mapOf("entry_valid" to emulator.startupInfoValid))
        if (!emulator.startupInfoValid) {
            updateErr("Base and entry invalid due to bad emulation result")
            failureSign = FAILED
        }
    }

    companion object {
        const val TOTAL_EXECUTION_THRESHOLD: Long = 10000
        const val DISTINCT_INSTRUCTION_THRESHOLD: Int = 20
        const val DISTINCT_FUNCTION_THRESHOLD: Int = 2
        const val LOOSEN_THRESHOLD: Int = 10

        class CheckerEmulator(
            program: Program,
            private var baseAddress: Address? = null,
            entryPoint: Address,
            masterStackPointer: Address,
            logger: Logger?,
            private val moveBackMemoryAfterTest: Boolean = false,
            monitor: TaskMonitor
        ) : StepInEmulator(program, entryPoint, masterStackPointer, logger,
            options = SUPPORT_DISTINCT_INSTRUCTION_COUNTER or
                    SUPPORT_DISTINCT_FUNCTION_COUNTER or
                    SUPPORT_SKIP_CALLOTHER or
                    SUPPORT_AUTO_DISASSEMBLE_AFTER_JUMP or
                    SUPPORT_AUTO_DISASSEMBLE_ENTRYPOINT or
                    SUPPORT_DATA_FLOW_ANALYZER,
            maxExecution = TOTAL_EXECUTION_THRESHOLD,
            monitor = monitor
        ) {
            var startupInfoValid: Boolean = false

            override fun initialization() {
                baseAddress ?. let {
                    MemoryUtil.moveAllBlocksInOffset(
                        memory = program.memory,
                        offset = -program.memory.blocks.first().start.offset + it.offset
                    )
                }
                super.initialization()
            }

            override fun beforeStep(addr: Address, presetDisasmRegs: Map<Register, BigInteger>): Int {
                val superBehavior = super.beforeStep(addr, presetDisasmRegs)
                if (superBehavior != NEXT_BEHAVIOR_NORMAL)
                    return superBehavior
                // Need to get the count of functions executed and the count of instructions executed
                if (distinctInstExecuted.size >= DISTINCT_INSTRUCTION_THRESHOLD &&
                    distinctFunctionExecuted.size >= DISTINCT_FUNCTION_THRESHOLD) {
                    return NEXT_BEHAVIOR_STOP
                }
                return NEXT_BEHAVIOR_NORMAL
            }

            override fun finalization() {
                startupInfoValid = (distinctInstExecuted.size >= DISTINCT_INSTRUCTION_THRESHOLD
                        && distinctFunctionExecuted.size >= DISTINCT_FUNCTION_THRESHOLD)
                if (!startupInfoValid && instExecuted >= TOTAL_EXECUTION_THRESHOLD)
                    startupInfoValid = (distinctInstExecuted.size >= LOOSEN_THRESHOLD)

                logger?.info("Dynamic check ${if (startupInfoValid) "passed" else "failed"}")
                logger?.info("Executed ${distinctInstExecuted.size} distinct instructions")
                logger?.info("Executed ${distinctFunctionExecuted.size} distinct functions")

                baseAddress ?. let {
                    if (moveBackMemoryAfterTest)
                        MemoryUtil.moveAllBlocksInOffset(program.memory, -it.offset)
                }
            }
        }
    }
}