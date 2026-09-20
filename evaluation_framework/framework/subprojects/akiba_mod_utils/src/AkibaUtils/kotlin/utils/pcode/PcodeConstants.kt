package org.iotsplab.akiba.utils.pcode

import ghidra.program.model.pcode.PcodeOp

/**
 * P-code 操作码常量集合。
 * 提供 P-code 指令的分类和符号映射，用于分析和处理 P-code 表达式。
 */
object PcodeConstants {
    /**
     * P-code 操作码符号映射表。
     * 将操作码整数映射到对应的符号表示，用于反汇编和调试输出。
     */
    val OPCODE_SYMBOLS: Map<Int, String> = mapOf(
        PcodeOp.INT_ADD to "+", PcodeOp.FLOAT_ADD to "f+",
        PcodeOp.INT_SUB to "-", PcodeOp.FLOAT_SUB to "f-",
        PcodeOp.INT_MULT to "*", PcodeOp.FLOAT_MULT to "f*",
        PcodeOp.INT_DIV to "/", PcodeOp.FLOAT_DIV to "f/",
        PcodeOp.INT_REM to "%", PcodeOp.INT_SREM to "s%",
        PcodeOp.INT_AND to "&", PcodeOp.BOOL_AND to "&&",
        PcodeOp.INT_OR to "|", PcodeOp.BOOL_OR to "||",
        PcodeOp.INT_XOR to "^", PcodeOp.BOOL_XOR to "^^",
        PcodeOp.INT_LEFT to "<<", PcodeOp.INT_RIGHT to ">>",
        PcodeOp.INT_SRIGHT to "s>>",
        PcodeOp.INT_EQUAL to "==", PcodeOp.FLOAT_EQUAL to "f==",
        PcodeOp.INT_NOTEQUAL to "!=", PcodeOp.FLOAT_NOTEQUAL to "f!=",
        PcodeOp.INT_LESS to "<", PcodeOp.FLOAT_LESS to "f<",
        PcodeOp.INT_LESSEQUAL to "<=", PcodeOp.FLOAT_LESSEQUAL to "f<=",
        PcodeOp.INT_SLESS to "s<",
        PcodeOp.INT_SLESSEQUAL to "s<=",
        PcodeOp.INT_ZEXT to "zext",
        PcodeOp.INT_SEXT to "sext",
        PcodeOp.INT_NEGATE to "~", PcodeOp.BOOL_NEGATE to "!",
        PcodeOp.INT_2COMP to "-", PcodeOp.FLOAT_NEG to "f-",
        PcodeOp.FLOAT_ABS to "abs",
        PcodeOp.FLOAT_SQRT to "sqrt",
        PcodeOp.FLOAT_CEIL to "ceil",
        PcodeOp.FLOAT_FLOOR to "floor",
        PcodeOp.FLOAT_ROUND to "round",
        PcodeOp.FLOAT_TRUNC to "trunc",
        PcodeOp.FLOAT_NAN to "nan",
        PcodeOp.FLOAT_FLOAT2FLOAT to "float2float",
        PcodeOp.FLOAT_INT2FLOAT to "int2float",
        PcodeOp.INT_CARRY to "carry", PcodeOp.INT_SCARRY to "scarry",
        PcodeOp.INT_SBORROW to "sborrow",
        PcodeOp.PIECE to "piece", PcodeOp.SUBPIECE to "subpiece",
        PcodeOp.POPCOUNT to "popcount", PcodeOp.LZCOUNT to "lzcount"
    )

    /**
     * 一元整数操作码集合。
     * 包含所有只有一个输入操作数的整数操作码。
     */
    val UNARY_OPCODES: Set<Int> = setOf(
        PcodeOp.INT_2COMP,
        PcodeOp.INT_NEGATE,
        PcodeOp.BOOL_NEGATE,
        PcodeOp.FLOAT_NEG
    )

    /**
     * 二元操作码集合。
     * 包含所有需要两个输入操作数的操作码（整数和浮点数运算、逻辑运算等）。
     */
    val BINARY_OPCODES: Set<Int> = setOf(
        PcodeOp.INT_ADD, PcodeOp.FLOAT_ADD, PcodeOp.INT_SUB, PcodeOp.FLOAT_SUB,
        PcodeOp.INT_MULT, PcodeOp.FLOAT_MULT, PcodeOp.INT_DIV, PcodeOp.FLOAT_DIV,
        PcodeOp.INT_REM, PcodeOp.INT_SREM,
        PcodeOp.INT_AND, PcodeOp.BOOL_AND, PcodeOp.INT_OR, PcodeOp.BOOL_OR,
        PcodeOp.INT_XOR, PcodeOp.BOOL_XOR,
        PcodeOp.INT_LEFT, PcodeOp.INT_RIGHT, PcodeOp.INT_SRIGHT,
        PcodeOp.INT_EQUAL, PcodeOp.FLOAT_EQUAL, PcodeOp.INT_NOTEQUAL, PcodeOp.FLOAT_NOTEQUAL,
        PcodeOp.INT_LESS, PcodeOp.FLOAT_LESS, PcodeOp.INT_LESSEQUAL, PcodeOp.FLOAT_LESSEQUAL,
        PcodeOp.INT_SLESS, PcodeOp.INT_SLESSEQUAL
    )

    /**
     * 一元函数操作码集合。
     * 包含所有以函数形式调用的一元操作码（如数学函数、位计数等）。
     */
    val FUNC_UNARY_OPCODES: Set<Int> = setOf(
        PcodeOp.FLOAT_NAN,
        PcodeOp.FLOAT_ABS, PcodeOp.FLOAT_SQRT,
        PcodeOp.FLOAT_CEIL, PcodeOp.FLOAT_FLOOR, PcodeOp.FLOAT_ROUND,
        PcodeOp.POPCOUNT, PcodeOp.LZCOUNT
    )

    /**
     * 二元函数操作码集合。
     * 包含所有需要两个参数的函数型操作码（如进位、拼接等）。
     */
    val FUNC_BINARY_OPCODES: Set<Int> = setOf(
        PcodeOp.INT_CARRY, PcodeOp.INT_SCARRY, PcodeOp.INT_SBORROW,
        PcodeOp.PIECE, PcodeOp.SUBPIECE
    )

    /**
     * 带大小参数的一元函数操作码集合。
     * 包含所有需要指定输出大小的一元函数操作码（如扩展、截断等）。
     */
    val FUNC_UNARY_OPCODES_WITH_SIZE: Set<Int> = setOf(
        PcodeOp.INT_ZEXT, PcodeOp.INT_SEXT, PcodeOp.FLOAT_TRUNC,
        PcodeOp.FLOAT_INT2FLOAT, PcodeOp.FLOAT_FLOAT2FLOAT
    )

    /**
     * 跳转类操作码集合。
     * 包含所有会改变控制流的操作码（调用、分支、返回等）。
     */
    val JUMP_OPCODES: Set<Int> = setOf(
        PcodeOp.CALL, PcodeOp.CALLIND, PcodeOp.CALLOTHER,
        PcodeOp.BRANCH, PcodeOp.BRANCHIND, PcodeOp.CBRANCH, PcodeOp.RETURN
    )
}