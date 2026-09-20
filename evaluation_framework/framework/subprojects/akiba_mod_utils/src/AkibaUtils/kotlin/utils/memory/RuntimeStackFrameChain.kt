package org.iotsplab.akiba.utils.memory

import ghidra.app.emulator.EmulatorHelper
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Instruction
import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.module.Log
import org.iotsplab.akiba.utils.assembly.isReturn
import org.iotsplab.akiba.utils.emulator.foreseeNextInstruction
import org.iotsplab.akiba.utils.emulator.pc
import org.iotsplab.akiba.utils.emulator.sp

/**
 * 运行时栈帧链类。
 * 管理和跟踪模拟器执行过程中的函数调用栈帧链。
 *
 * @param emu 模拟器助手对象。
 * @param stackTop 初始栈顶地址。
 */
class RuntimeStackFrameChain(
    private val emu: EmulatorHelper,
    private val stackTop: Address
) {
    /**
     * 栈帧链。
     */
    val frames: MutableList<RuntimeStackFrame> = mutableListOf(RuntimeStackFrame(emu, stackTop))

    private val program: Program
        get() = emu.program
    private val api: FlatProgramAPI
        get() = FlatProgramAPI(program)

    val returnAddress: Address?
        get() = run {
            val callerInst = program.listing.getInstructionAt(frames.last().callerAddress) ?: return null
            program.listing.getInstructionAt(callerInst.address.add(callerInst.bytes.size.toLong()))?.address
        }

    /**
     * 通过调用目标地址更新栈帧链。
     * 创建新的栈帧并保存当前寄存器状态。
     *
     * @param callTarget 调用目标地址。
     * @throws IllegalArgumentException 如果目标地址处没有指令。
     */
    @Throws(IllegalArgumentException::class)
    fun update(callTarget: Address) {
        check(api.getInstructionContaining(callTarget) != null) {
            "No instruction found at target address"
        }
        frames.last().let { 
            it.size = (it.address.offset - emu.sp.offset).toInt()
            it.childFrame = RuntimeStackFrame(emu, api.toAddr(emu.sp.offset)).let { child ->
                child.parentFrame = it
                // 将 pc 视为调用者地址，如果被误用，这可能是错误的，send instruction 更推荐
                child.callerAddress = emu.pc
                child
            }
            // 保存调用前的所有寄存器值
            it.registerSnapshot = program.language.registers.associateWith { r -> emu.readRegister(r) }.toMutableMap()
        }
    }

    /**
     * 通过下一条要执行的指令更新栈帧链。
     * 注意：必须在每条指令执行前调用！
     *
     * @param inst 下一条要执行的指令。
     * @throws IllegalArgumentException 如果目标地址处没有指令。
     */
    @Throws(IllegalArgumentException::class)
    fun update(inst: Instruction) {
        frames.last().instructionExecuted++
        val func = api.getFunctionContaining(inst.address)
        val nextInst = emu.foreseeNextInstruction()
        nextInst ?: return
        api.getFunctionContaining(nextInst.address).let { targetFunc ->
            // 仍在此函数中，无需创建新栈帧
            if (targetFunc == func) {
                frames.last().size = (frames.last().address.offset - emu.sp.offset).toInt()
                return
            }
            if (targetFunc == null) {
                Log.current.warn("Next instruction doesn't belong to any function!")
                return
            }
            if (isReturn.test(nextInst)) {
                frames.removeLast()
                return
            }
            update(nextInst.address)
        }
    }
}