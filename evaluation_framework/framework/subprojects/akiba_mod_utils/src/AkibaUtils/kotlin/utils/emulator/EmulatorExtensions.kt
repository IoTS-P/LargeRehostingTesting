package org.iotsplab.akiba.utils.emulator

import ghidra.app.emulator.EmulatorHelper
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.lang.RegisterValue
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Instruction
import ghidra.program.model.pcode.PcodeOp
import ghidra.util.exception.CancelledException
import ghidra.util.task.TaskMonitor
import org.iotsplab.akiba.structure.DataflowManager
import org.iotsplab.akiba.structure.PcodePolynomialTreeset
import org.iotsplab.akiba.utils.assembly.analyzeMemoryDataFlow
import org.iotsplab.akiba.utils.memory.RuntimeStackFrameChain
import org.iotsplab.akiba.utils.pcode.PcodeConstants
import java.util.function.Predicate

var EmulatorHelper.pc: Address
    get() = FlatProgramAPI(program).toAddr(readRegister(pcRegister).toLong())
    set(value) = writeRegister(pcRegister, value.offset)

val EmulatorHelper.sp: Address
    get() = FlatProgramAPI(program).toAddr(readRegister(stackPointerRegister).toLong())

val EmulatorHelper.nextInst: Instruction?
    get() = program.listing.getInstructionAt(pc)

val EmulatorHelper.currentFunction: Function
    get() = program.functionManager.getFunctionContaining(pc)!!

/**
 * 运行模拟器直到满足条件，无需创建断点。
 * 注意：这可能导致巨大的时间开销，因为我们需要单步执行并检查每条指令！
 *
 * @param condition 要满足的条件。
 * @param monitor 任务监视器。
 * @throws CancelledException 当在执行指令期间任务监视器被取消时抛出。
 */
@Throws(CancelledException::class)
fun EmulatorHelper.until(condition: () -> Boolean, monitor: TaskMonitor) {
    while (!condition()) {
        if (!step(monitor))
            return      // 如果模拟器意外退出，我们可以通过 `lastError` 获取错误信息
    }
}

/**
 * 运行模拟器直到下一条指令匹配条件，无需创建断点。
 * 注意：如果没有设置 presetBreakpoints，这可能导致巨大的时间开销，因为我们需要单步执行并检查每条指令！
 *
 * @param condition 要满足的条件。
 * @param presetBreakpoints 是否预先设置断点，默认为 true。
 */
fun<T: Predicate<Instruction>> EmulatorHelper.until(condition: T, presetBreakpoints: Boolean = true) {
    if (presetBreakpoints) {
        setBreakpointsIf(condition)
        run(TaskMonitor.DUMMY)
        return
    } else {
        while (!condition.test(nextInst?: run { return })) {
            if (!step(TaskMonitor.DUMMY))
                return      // If the emulator exited unexpectedly, we can get the error info through `lastError`
        }
    }
}

/**
 * 运行模拟器直到下一条指令匹配条件，无需创建断点。
 * 注意：这可能导致巨大的时间开销，因为我们需要单步执行并检查每条指令！
 *
 * @param addr 要中断的地址列表。
 */
fun EmulatorHelper.until(addr: List<Address>) {
    addr.forEach { setBreakpoint(it) }
    try {
        run(TaskMonitor.DUMMY)
    } finally {
        addr.forEach { clearBreakpoint(it) }
    }
}

/**
 * 为所有满足条件的指令预先创建断点。
 *
 * @param condition 要满足的条件。
 */
fun<T: Predicate<Instruction>> EmulatorHelper.setBreakpointsIf(condition: T) {
    program.listing.getInstructions(true).forEach {
        if (condition.test(it))
            setBreakpoint(it.address)
    }
}

/**
 * 分析下一条指令的数据流，分析结果将保存到管理器中。
 *
 * @param manager 保存分析结果的管理器。
 * @throws IllegalStateException 如果下一条指令为 null。
 */
@Throws(IllegalStateException::class)
fun EmulatorHelper.analyzeNextInstructionDataflow(manager: DataflowManager) {
    nextInst?.analyzeMemoryDataFlow(this, manager) ?: throw IllegalStateException("Next instruction is null")
}

/**
 * 跳过接下来应该执行的指令。
 *
 * @throws IllegalStateException 如果下一条指令为 null。
 */
@Throws(IllegalStateException::class)
fun EmulatorHelper.skipNext() {
    this.emulator.setExecuteAddress(pc.offset + (nextInst?.bytes?.size?.toLong()
        ?: throw IllegalStateException("Next instruction is null")
    ))

    // 在模拟过程中，如果我们跳过包含 CALLOTHER 的指令而前一条指令不是跳转，
    // Thumb 状态可能会丢失。因此每次跳过指令时都需要恢复它。
    if (Regex("ARM:(LE|BE):32:Cortex").matches(this.language.languageID.idAsString)) {
        this.contextRegister = RegisterValue(this.language.getRegister("TMode"), 1.toBigInteger())
    }
}

/**
 * 强制模拟退出最后执行的函数，并返回到调用者函数。
 *
 * @param frameStatus 栈帧状态。
 * @throws IllegalStateException 如果栈帧没有调用者函数，无法返回。
 */
@Throws(IllegalStateException::class)
fun EmulatorHelper.returnDirectly(frameStatus: RuntimeStackFrameChain) {
    check(frameStatus.frames.size >= 2) { "Stack frame has no caller function, unable to return" }
    // Restore register snapshot of caller function
    val snapshot = frameStatus.frames.reversed()[1].registerSnapshot!!
    program.language.registers.forEach {
        writeRegister(it, snapshot[it])
    }
    // Change pc
    pc = frameStatus.frames.last().returnFallThrough!!.address
    // Pop the last frame
    frameStatus.frames.removeLast()
}

/**
 * 根据在此指令执行之前的上下文预测将要执行的下一条指令。
 * 如果该指令没有跳转 PC 的可能性，则返回下一条指令的地址；如果前方没有指令，则返回 null。
 *
 * @return 下一条指令的地址，如果前方没有指令则返回 null。
 */
fun EmulatorHelper.foreseeNextInstruction(): Instruction? {
    nextInst ?: return null
    val ni = nextInst!!
    val trees = PcodePolynomialTreeset()
    ni.pcode.forEachIndexed { _, it ->
        when (it.opcode) {
            PcodeOp.CBRANCH -> {
                val conditionValue = trees.calculate(this, it.inputs[1])
                if (conditionValue.first != 0L)
                    return program.listing.getInstructionAt(it.inputs[0].address)
            }
            in listOf(PcodeOp.CALL, PcodeOp.BRANCH)  -> return program.listing.getInstructionAt(it.inputs[0].address)
            in listOf(PcodeOp.CALLIND, PcodeOp.BRANCHIND, PcodeOp.RETURN) -> {
                val target = trees.calculate(this, it.inputs[0])
                return program.listing.getInstructionAt(FlatProgramAPI(program).toAddr(target.first))
            }
            else -> try { trees.addPcode(it) } catch (_: Exception) {}
        }
    }
    // 没有跳转，就是下一条指令（可能为 null）
    // 我们不考虑像 ARM 中的 `svc` 这样的架构特定指令
    return ni.next
}



/**
 * 预测将在本函数中执行的下一条指令。
 * 此函数与 `foreseeNextInstruction` 的区别在于它会忽略调用。
 * 如果该指令是调用，此函数将跳过调用过程并返回后续地址。
 * 如果该指令是跳转，此函数将检查目标是否在函数内。如果不在，则返回 null。
 */
fun EmulatorHelper.foreseeNextInstructionInFunc(): Instruction? {
    nextInst ?: return null
    val ni = nextInst!!
    return when (ni.pcode.last().opcode) {
        in setOf(PcodeOp.RETURN, PcodeOp.CALLOTHER) -> null
        in setOf(PcodeOp.CALL, PcodeOp.CALLIND) -> ni.next
        in PcodeConstants.JUMP_OPCODES -> {
            val nextInst = foreseeNextInstruction() ?: return null
            return if (currentFunction.body.contains(nextInst.address)) nextInst else null
        }
        else -> ni.next
    }
}