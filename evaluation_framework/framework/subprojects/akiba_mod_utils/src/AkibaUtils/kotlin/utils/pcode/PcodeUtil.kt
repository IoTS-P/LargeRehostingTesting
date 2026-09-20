package org.iotsplab.akiba.utils.pcode

import ghidra.program.model.listing.Program
import ghidra.program.model.pcode.PcodeOp
import ghidra.program.model.pcode.Varnode
import org.iotsplab.akiba.utils.memory.MemoryUtil

/**
 * P-code 工具类。
 * 提供解析和处理 P-code 指令的实用方法。
 */
object PcodeUtil {
    /**
     * 解析 LOAD 指令的数据流源和目标 varnode。
     *
     * @param program Ghidra 程序对象。
     * @param op 要解析的 P-code 操作对象。
     * @return Varnode 对，<源，目标>。
     * @throws IllegalArgumentException 如果 PcodeOp 不是 LOAD 操作或地址空间无效。
     * @throws AssertionError 如果断言检查失败。
     */
    @Throws(IllegalArgumentException::class, AssertionError::class)
    fun parseLoad(program: Program, op: PcodeOp): Pair<Varnode, Varnode> {
        return when (op.opcode) {
            PcodeOp.LOAD -> {
                assert(op.inputs[0].isConstant)
                val addrSpace = MemoryUtil.getAddressSpace(op.inputs[0].offset.toInt(), program)
                if(addrSpace.name != "ram") { throw IllegalArgumentException("Invalid address space") }
                assert(op.output.isRegister)
                op.inputs[1] to op.output
            }
            else -> throw IllegalArgumentException("PcodeOp is not a load")
        }
    }

    /**
     * 解析 STORE 指令的数据流源和目标 varnode。
     *
     * @param program Ghidra 程序对象。
     * @param op 要解析的 P-code 操作对象。
     * @return Varnode 对，<源，目标>。
     * @throws IllegalArgumentException 如果 PcodeOp 不是 STORE 操作或地址空间无效。
     * @throws AssertionError 如果断言检查失败。
     */
    @Throws(IllegalArgumentException::class, AssertionError::class)
    fun parseStore(program: Program, op: PcodeOp): Pair<Varnode, Varnode> {
        return when (op.opcode) {
            PcodeOp.STORE -> {
                assert(op.inputs[0].isConstant)
                val addrSpace = MemoryUtil.getAddressSpace(op.inputs[0].offset.toInt(), program)
                if(addrSpace.name != "ram") { throw IllegalArgumentException("Invalid address space") }
                assert(op.inputs[2].isRegister)
                Pair(op.inputs[2], op.inputs[1])
            }
            else -> throw IllegalArgumentException("PcodeOp is not a store")
        }
    }
}