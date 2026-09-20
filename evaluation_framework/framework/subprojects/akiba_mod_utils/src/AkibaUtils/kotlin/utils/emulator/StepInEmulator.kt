package org.iotsplab.akiba.utils.emulator

import ghidra.pcode.emu.jit.gen.op.CallOtherMissingOpGen
import ghidra.pcode.emu.jit.gen.op.CallOtherOpGen
import ghidra.program.model.address.Address
import ghidra.program.model.lang.Register
import ghidra.program.model.listing.Program
import ghidra.program.model.pcode.PcodeOp
import ghidra.util.task.TaskMonitor
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.structure.DataflowManager
import org.iotsplab.akiba.utils.assembly.DisasmHelper
import org.iotsplab.akiba.utils.memory.MemoryDebris
import org.iotsplab.akiba.utils.memory.RuntimeStackFrameChain
import java.math.BigInteger

/**
 * 单步模拟器。
 * 在每条指令处中断的基本模拟器。当你需要控制执行的确切指令数，或在模拟期间进行深入分析时非常有用。
 *
 * 由于此模拟器的特性，它可能比其他模拟器慢很多。如果你追求极致速度，建议使用 `JitPcodeEmulator` 来模拟程序。
 *
 * 在此类中，我们提供了一组处理器来控制模拟器的行为，包括在执行指令之前完成的 `beforeStep` 和
 * 在执行指令之后完成的 `afterStep`。`beforeStep` 有一个默认实现。
 */
open class StepInEmulator(
    program: Program,
    entryPoint: Address,
    stackTop: Address,
    logger: Logger? = null,
    private val options: Int = SUPPORT_SKIP_CALLOTHER or SUPPORT_AUTO_DISASSEMBLE_AFTER_JUMP,
    private val maxExecution: Long = Long.MAX_VALUE,
    val monitor: TaskMonitor
) : Emulator(program, entryPoint, stackTop, logger) {
    /**
     * 已执行的指令数。
     */
    protected var instExecuted: Int = 0

    /**
     * 不同的已执行指令，元素为指令地址
     */
    protected var distinctInstExecuted: HashSet<Address> = hashSetOf()

    /**
     * 不同的已执行函数数量，元素为函数的入口点
     */
    protected var distinctFunctionExecuted: HashSet<Address> = hashSetOf()

    /**
     * 数据流记录器。
     */
    protected val dataFlowManager = DataflowManager()

    /**
     * 运行时栈帧链记录器。
     */
    protected lateinit var stackFrameManager: RuntimeStackFrameChain

    /**
     * 组装后的数据流映射。
     * @return 内存碎片之间的数据流关系映射。
     */
    val assembledDataflow: Map<MemoryDebris, MemoryDebris>
        get() = dataFlowManager.assembleFlow()
    
    /**
     * 组装后的零存储列表。
     * @return 被存储零值的内存碎片段列表。
     */
    val assembledZeroStore: List<MemoryDebris>
        get() = dataFlowManager.assembleZeroStore()

    init {
        // 检查选项参数
        if (options and SUPPORT_AUTO_DISASSEMBLE_AFTER_JUMP != 0)
            require(options and SUPPORT_DATA_FLOW_ANALYZER != 0) {
                "Auto runtime disassembling requires data flow analyzer"
            }
    }

    /**
     * 初始化模拟器。初始化时检查入口地址是否可执行，如果入口地址未被识别为代码，则尝试在入口处定义函数，如果反汇编错误，则拒绝模拟。
     */
    override fun initialization() {
        super.initialization()
        instExecuted = 0
        distinctInstExecuted.clear()
        distinctFunctionExecuted.clear()
        dataFlowManager.clear()
        stackFrameManager = RuntimeStackFrameChain(context, stackTop)   // 丢弃之前的帧记录器
        // 如果入口点未被识别为代码，则对其进行反汇编并在那里定义函数
        if (options and SUPPORT_AUTO_DISASSEMBLE_ENTRYPOINT != 0 && api.getInstructionAt(entryPoint) == null) {
            logger?.info("Entry point not recognized as codes, disassemble it.")
            DisasmHelper(program).disasmFunction(entryPoint, monitor = monitor)
        }
    }

    /**
     * 在下一条指令执行之前需要完成的操作。
     *
     * @param addr 即将执行的下一条指令的地址。
     * @param presetDisasmRegs 预设的反汇编寄存器值。
     * @return 此模拟器对下一条指令应该采取的行为：
     *      - NEXT_BEHAVIOR_NORMAL: 正常执行。
     *      - NEXT_BEHAVIOR_SKIP: 跳过。
     *      - NEXT_BEHAVIOR_STOP: 立即停止模拟。
     * @throws IllegalStateException 如果下一条指令未找到。
     */
    @Throws(IllegalStateException::class)
    open fun beforeStep(addr: Address, presetDisasmRegs: Map<Register, BigInteger> = mapOf()): Int {
        if (context.nextInst == null)
            throw IllegalArgumentException(
                "Next instruction not found, address: ${context.pc.offset.toString(16)}")
        else
            logger?.trace("{} {}", context.pc, context.nextInst.toString())

        if (options and SUPPORT_SKIP_CALLOTHER != 0 &&
            context.nextInst!!.pcode.any { it.opcode == PcodeOp.CALLOTHER }) {
            logger?.debug("Detected CALLOTHER, skipped")
            return NEXT_BEHAVIOR_SKIP
        }

        if (options and SUPPORT_DATA_FLOW_ANALYZER != 0)
            context.analyzeNextInstructionDataflow(dataFlowManager)

        if (options and SUPPORT_AUTO_DISASSEMBLE_AFTER_JUMP != 0) {
            // If the jump target is not disassembled yet, do it and define a function starting there.
            if (dataFlowManager.willJump && api.getInstructionAt(dataFlowManager.jumpTarget!!) == null) {
                logger?.info("Detected jump to ${dataFlowManager.jumpTarget} which is not disassembled, " +
                        "disassemble it.")
                DisasmHelper(program).disasmFunction(
                    dataFlowManager.jumpTarget!!, presetDisasmRegs, monitor)
                if (api.getFunctionAt(dataFlowManager.jumpTarget!!) != null)
                    logger?.debug("Successfully created a function at " +
                            dataFlowManager.jumpTarget!!.offset.toString(16))
            }
        }

        if (options and SUPPORT_STACK_MONITOR != 0)
            stackFrameManager.update(context.nextInst!!)

        if (options and SUPPORT_DISTINCT_INSTRUCTION_COUNTER != 0)
            distinctInstExecuted.add(context.pc)

        if (options and SUPPORT_DISTINCT_FUNCTION_COUNTER != 0)
            distinctFunctionExecuted.add(context.currentFunction.entryPoint)

        return NEXT_BEHAVIOR_NORMAL
    }

    /**
     * 开始模拟运行。每一条指令运行前，会调用 `beforeStep` 方法，并检查是否需要跳过或停止模拟。
     */
    override fun startEmulation() {
        try {
            while (instExecuted < maxExecution) {
                if (monitor.isCancelled) {
                    logger?.warn("Stopped due to monitor cancellation")
                    return
                }

                val behavior: Int = beforeStep(context.pc)
                when (behavior) {
                    NEXT_BEHAVIOR_NORMAL -> { /* Do nothing */ }
                    NEXT_BEHAVIOR_SKIP -> {
                        context.skipNext()
                        continue
                    }
                    NEXT_BEHAVIOR_STOP -> {
                        logger?.info("Stopped due to user-defined beforeStep")
                        break
                    }
                }
                if (!context.step(monitor)) {
                    logger?.error("Error detected: ${context.lastError}")
                    break
                }
                instExecuted++
                afterStep(context.pc)
            }
        } catch (e: Exception) {
            logger?.error("Emulation aborted: ${e.message}")
        }
    }

    /**
     * 指令执行后的处理。
     * 可被子类重写以添加自定义行为。
     *
     * @param addr 刚执行完的指令的地址。
     */
    open fun afterStep(addr: Address) {}

    companion object {
        /**
         * 支持栈监视器。
         */
        const val SUPPORT_STACK_MONITOR: Int = 0x01
        
        /**
         * 支持数据流分析器。
         */
        const val SUPPORT_DATA_FLOW_ANALYZER: Int = 0x02
        
        /**
         * 支持跳过 CALLOTHER 指令。
         */
        const val SUPPORT_SKIP_CALLOTHER: Int = 0x04
        
        /**
         * 支持不同指令计数器。
         */
        const val SUPPORT_DISTINCT_INSTRUCTION_COUNTER: Int = 0x08
        
        /**
         * 支持不同函数计数器。
         */
        const val SUPPORT_DISTINCT_FUNCTION_COUNTER: Int = 0x10
        
        /**
         * 支持跳转后自动反汇编。
         */
        const val SUPPORT_AUTO_DISASSEMBLE_AFTER_JUMP: Int = 0x20
        
        /**
         * 支持入口点自动反汇编。
         */
        const val SUPPORT_AUTO_DISASSEMBLE_ENTRYPOINT: Int = 0x40

        /**
         * 正常执行行为。
         */
        const val NEXT_BEHAVIOR_NORMAL: Int = 0
        
        /**
         * 跳过行为。
         */
        const val NEXT_BEHAVIOR_SKIP: Int = 1
        
        /**
         * 停止行为。
         */
        const val NEXT_BEHAVIOR_STOP: Int = 2
    }
}