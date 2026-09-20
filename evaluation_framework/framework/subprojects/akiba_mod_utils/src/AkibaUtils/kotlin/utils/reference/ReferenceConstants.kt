package org.iotsplab.akiba.utils.reference

import ghidra.program.model.symbol.RefType

/**
 * 引用类型常量集合。
 * 提供 Ghidra 引用类型的分类集合，用于识别不同类型的代码引用。
 */
object ReferenceConstants {
    /**
     * 调用引用类型集合。
     * 包含所有类型的函数调用引用（无条件、条件、计算调用等）。
     */
    val REFERENCE_CALL = setOf(
        RefType.UNCONDITIONAL_CALL, RefType.CONDITIONAL_CALL, RefType.CONDITIONAL_COMPUTED_CALL,
        RefType.COMPUTED_CALL)

    /**
     * 跳转引用类型集合。
     * 包含所有类型的跳转引用（无条件、条件、计算跳转等）。
     */
    val REFERENCE_JUMP = setOf(
        RefType.UNCONDITIONAL_JUMP, RefType.CONDITIONAL_JUMP, RefType.CONDITIONAL_COMPUTED_JUMP,
        RefType.COMPUTED_JUMP
    )

    /**
     * 读引用类型集合。
     * 包含所有类型的内存读引用（直接读、间接读、读写等）。
     */
    val REFERENCE_READ = setOf(
        RefType.READ, RefType.READ_WRITE, RefType.READ_IND, RefType.READ_WRITE_IND
    )

    /**
     * 写引用类型集合。
     * 包含所有类型的内存写引用（直接写、间接写、读写等）。
     */
    val REFERENCE_WRITE = setOf(
        RefType.WRITE, RefType.READ_WRITE, RefType.WRITE_IND, RefType.READ_WRITE_IND
    )
}