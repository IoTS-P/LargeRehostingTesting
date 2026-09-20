package org.iotsplab.akiba.utils.reference

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import ghidra.program.model.symbol.RefType
import ghidra.program.model.symbol.Reference

/**
 * 检查引用是否为有效的代码目标。
 * 判断引用类型是否为调用或跳转类型。
 *
 * @return 如果是有效的代码目标则返回 true，否则返回 false。
 */
fun Reference.isValidCodeTarget(): Boolean {
    return referenceType in listOf(
        RefType.UNCONDITIONAL_CALL, RefType.CONDITIONAL_CALL, RefType.CONDITIONAL_COMPUTED_CALL,
        RefType.COMPUTED_CALL, RefType.CONDITIONAL_JUMP, RefType.UNCONDITIONAL_JUMP,
        RefType.COMPUTED_JUMP
    )
}

/**
 * 检查引用是否可能是函数起始位置。
 * 判断引用类型是否为调用类型。
 *
 * @return 如果是可能的函数起始位置则返回 true，否则返回 false。
 */
fun Reference.isPossibleFunctionStart(): Boolean {
    return referenceType in ReferenceConstants.REFERENCE_CALL
}

/**
 * 检查地址是否为有效的代码目标。
 * 判断是否有指向该地址的引用是调用或跳转类型。
 *
 * @param program Ghidra 程序对象。
 * @return 如果是有效的代码目标则返回 true，否则返回 false。
 */
fun Address.isValidCodeTarget(program: Program): Boolean {
    return program.referenceManager.getReferencesTo(this).any { it.isValidCodeTarget() }
}