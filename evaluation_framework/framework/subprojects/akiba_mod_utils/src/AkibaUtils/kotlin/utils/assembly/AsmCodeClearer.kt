package org.iotsplab.akiba.utils.assembly

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program

/**
 * 汇编代码清除器。
 * 用于清除指定地址范围内的反汇编代码单元。
 *
 * @param program Ghidra 程序对象。
 */
class AsmCodeClearer(private val program: Program) {
    /**
     * 清除从起始地址开始的汇编代码，直到 mostEnd 地址或指令结束。
     *
     * @param start 开始清除的地址。
     * @param mostEnd 可选参数，清除的上限地址，默认为程序的最大地址。
     */
    @JvmOverloads
    fun clearCodeStartsWith(start: Address, mostEnd: Address = program.maxAddress) {
        var ptr = start
        while (ptr < mostEnd) {
            val inst = program.listing.getInstructionAt(ptr) ?: break
            ptr = ptr.add(inst.length.toLong())
        }

        if (ptr != start)
            program.listing.clearCodeUnits(start, ptr.subtract(1), true)
    }
}
