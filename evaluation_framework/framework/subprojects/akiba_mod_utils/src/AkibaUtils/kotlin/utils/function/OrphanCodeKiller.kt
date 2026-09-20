package org.iotsplab.akiba.utils.function

import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.assembly.AsmCodeClearer

/**
 * 孤代码清除器。
 * 用于识别并清除不属于任何函数的代码段。
 * 这些代码可能是误识别的指令或数据。
 */
object OrphanCodeKiller {
    /**
     * 处理程序中的孤代码。
     * 遍历所有指令，如果指令不属于任何函数，则将其清除。
     *
     * @param program 需要处理的程序对象。
     */
    @JvmStatic
    fun process(program: Program) {
        val cc = AsmCodeClearer(program)
        val ii = program.listing.getInstructions(true)
        while (ii.hasNext()) {
            val inst = ii.next()
            if (program.listing.getFunctionContaining(inst.address) == null) {
                // 只清除孤代码，因为这些代码可能不是"真正的代码"
                cc.clearCodeStartsWith(inst.address, inst.address.add(inst.length.toLong()))
            }
        }
    }
}
