package org.iotsplab.akiba.utils.assembly

import ghidra.program.model.listing.Instruction

/**
 * 汇编操作数的抽象基类。
 * 用于封装指令操作数的信息和类型，提供统一的访问接口。
 *
 * @param inst 所属的指令对象。
 * @param opIdx 操作数在指令中的索引。
 */
abstract class AsmOperand(protected var inst: Instruction, protected var opIdx: Int) {
    /**
     * 原始操作数对象数组。
     * 从指令中获取的原始操作数对象，用于进一步的分析。
     */
    @JvmField
    protected var oriObjects: Array<Any> = inst.getOpObjects(opIdx)
    
    /**
     * 操作数类型标识。
     * Ghidra 定义的操作数类型常量，用于识别操作数的类别。
     */
    @JvmField
    protected var operandType: Int = inst.getOperandType(opIdx)
    
    /**
     * 操作数的字符串表示。
     * 格式化后的操作数字符串，去除了多余的空格。
     */
    @JvmField
    val opStr: String = getOperandString(inst, opIdx)

    /**
     * 获取操作数类型。
     *
     * @return 操作数的类型标识值。
     */
    fun getOperandType(): Int {
        return operandType
    }

    /**
     * 转换为易读的字符串格式。
     *
     * @return 描述此操作数的格式化字符串，包含索引和指令地址信息。
     */
    fun toPrettyString(): String {
        val ret = String.format("Operand #%d for instruction at %s:", opIdx, inst!!.address.toString())
        return ret
    }

    companion object {
        /**
         * 获取指令中指定索引的操作数字符串。
         * 处理括号等特殊情况，正确分割操作数。
         *
         * @param inst 目标指令。
         * @param index 操作数索引。
         * @return 格式化后的操作数字符串。
         */
        fun getOperandString(inst: Instruction, index: Int): String {
            val operandsString = inst.toString().split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1]
            val fakeOps = operandsString.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            var inBracket = false
            val realOps = ArrayList<String>()
            for (op in fakeOps) {
                if (!inBracket) realOps.add(op)
                else realOps[realOps.size - 1] = realOps.last() + "," + op
                if ((op.startsWith("[") && !op.endsWith("]")) || (op.startsWith("{") && op.endsWith("}"))) inBracket =
                    true
                else if (op.endsWith("]") || op.endsWith("}")) inBracket = false
            }
            return realOps[index]
        }
    }
}

