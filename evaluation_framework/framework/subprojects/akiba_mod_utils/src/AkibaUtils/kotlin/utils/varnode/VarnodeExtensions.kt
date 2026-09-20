package org.iotsplab.akiba.utils.varnode

import ghidra.program.model.listing.Program
import ghidra.program.model.pcode.Varnode

/**
 * 将 Varnode 转换为美观的字符串表示。
 * 根据 Varnode 的类型（地址、寄存器、唯一、常量）生成格式化的描述。
 *
 * @param program Ghidra 程序对象，用于获取寄存器名称。
 * @return 格式化的字符串表示。
 * @throws UnsupportedOperationException 如果是不支持的类型或大小。
 */
@Throws(UnsupportedOperationException::class)
fun Varnode.toPrettyString(program: Program? = null): String {
    return when {
        isAddress -> "A_${address.addressSpace.name}" +
                ":${address.offset.toString(16)}_${size}"
        isRegister -> "R_${program?.getRegister(this)?:offset.toString(16)}" +
                "_${size}"
        isUnique -> "U_${offset.toString(16)}_${size}"
        isConstant -> "${offset.toString(16)}(${
            when (size) {
                1 -> "8"
                2 -> "16"
                4 -> "32"
                8 -> "64"
                16 -> "128"
                else -> throw UnsupportedOperationException("Unexpected size: $size")
            }
        })"
        else -> throw UnsupportedOperationException("Unexpected node: $this")
    }
}