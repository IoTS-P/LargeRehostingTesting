package org.iotsplab.akiba.utils.memory

import ghidra.app.emulator.EmulatorHelper
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.lang.Register
import ghidra.program.model.listing.Instruction
import ghidra.program.model.listing.Program
import java.math.BigInteger

/**
 * 运行时栈帧类。
 * 表示函数调用时的栈帧信息，包括调用者地址、寄存器快照等。
 *
 * @param emu 模拟器助手对象。
 * @param ceil 栈帧的起始地址（栈顶）。
 */
class RuntimeStackFrame(private val emu: EmulatorHelper, ceil: Address): MemoryDebris(ceil, 0) {
    /**
     * 调用者地址。
     */
    var callerAddress: Address? = null

    /**
     * 父栈帧。
     */
    var parentFrame: RuntimeStackFrame? = null

    /**
     * 子栈帧。
     */
    var childFrame: RuntimeStackFrame? = null

    /**
     * 寄存器快照。
     */
    var registerSnapshot: MutableMap<Register, BigInteger>? = null

    /**
     * 栈帧中已执行的指令数。
     */
    var instructionExecuted: Int = 0
    private val api: FlatProgramAPI = FlatProgramAPI(program)

    /**
     * Ghidra 程序对象。
     */
    val program: Program
        get() = emu.program
    
    /**
     * 返回地址处的下一条指令。
     * 即调用者函数中调用指令的下一条指令。
     */
    val returnFallThrough: Instruction?
        get() = api.getInstructionAt(callerAddress).next
}