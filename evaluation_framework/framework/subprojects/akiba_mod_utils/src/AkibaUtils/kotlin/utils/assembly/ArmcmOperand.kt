package org.iotsplab.akiba.utils.assembly

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Instruction
import ghidra.program.model.mem.MemoryAccessException
import org.iotsplab.akiba.utils.memory.MemoryUtil
import java.util.regex.Pattern

/**
 * ARM Cortex-M 架构的汇编操作数类。
 * 继承自 AsmOperand，提供 ARM 特有的操作数类型识别和处理功能。
 *
 * @param inst 所属的指令对象。
 * @param index 操作数在指令中的索引。
 * @param parent 父指令（可选），用于处理特殊情况。
 */
class ArmcmOperand(inst: Instruction, index: Int, private val parent: Instruction? = null)
    : AsmOperand(inst, index) {
    /**
     * 操作数大小（字节）。
     * 根据指令助记符自动确定操作数的大小。
     */
    val operandSize = getInstructionOperandSize(inst)

    init {
        // 获取 ARM 特有的操作数类型
        if (opStr.endsWith("!")) operandType = operandType or REG_SELF_STEP

        if (opStr.startsWith("[")) {
            if (opStr.contains(",")) {
                val innerOperands = opStr.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val matcher = IMMEDIATE_REGEX.matcher(innerOperands[1])
                operandType = if (matcher.matches()) operandType or REG_BASE_IMM
                else operandType or REG_BASE_REG
            } else operandType = operandType or REG_INDIRECT
        } else if (opStr.startsWith("{")) operandType = operandType or MULTI_REGISTER
    }

    override fun equals(other: Any?): Boolean {
        when (other) {
            is ArmcmOperand -> {
                if (this.operandType != other.operandType) return false
                return oriObjects.contentEquals(other.oriObjects)
            }
            is String -> return this.opStr == other
            else -> return false
        }
    }

    /**
     * 解引用操作数获取内存值。
     * 仅适用于立即数地址形式的操作数。
     *
     * @return 解引用后读取的长整型值。
     * @throws IllegalArgumentException 如果操作数不是立即数地址形式。
     * @throws UnsupportedOperationException 如果操作数格式不支持解引用。
     * @throws MemoryAccessException 如果内存访问失败。
     */
    @Throws(IllegalArgumentException::class, UnsupportedOperationException::class,
        MemoryAccessException::class)
    fun dereference(): Long {
        if (!ADDR_IMM_REGEX.matcher(opStr).matches())
            throw UnsupportedOperationException("$opStr is not immediate address")
        val address = oriObjects[0]
        assert(address is Address)
        return MemoryUtil.readSmall(super.inst.program, address as Address, operandSize)
    }

    companion object {
        /**
         * 寄存器间接寻址类型常量。
         */
        const val REG_INDIRECT: Int = 0x1000000
        
        /**
         * 基址寄存器加立即数偏移类型常量。
         */
        const val REG_BASE_IMM: Int = 0x2000000
        
        /**
         * 基址寄存器加寄存器偏移类型常量。
         */
        const val REG_BASE_REG: Int = 0x4000000
        
        /**
         * 多寄存器类型常量。
         */
        const val MULTI_REGISTER: Int = 0x8000000
        
        /**
         * 寄存器自增类型常量。
         */
        const val REG_SELF_STEP: Int = 0x10000000

        // 匹配 "[r0,#0x1]" 中的 #0x1 或 "movs r0,#0x1" 中的 0x1
        val IMMEDIATE_REGEX: Pattern = Pattern.compile("^#-?(0x)?[0-9a-fA-F]+$")
        // 匹配 {r2-r6,r10}, {r4,r5,r6,r7,r8}
        val MULTI_REG_REGEX: Pattern = Pattern.compile("^\\{(([^-,]-[^-,])|([^-,]),)*([^-,]-[^-,])|([^-,])}$")
        // 匹配 [0x500]
        val ADDR_IMM_REGEX: Pattern = Pattern.compile("^\\[0x[0-9a-fA-F]+]$")
        // 匹配 "[r0,#0x1]"
        val BASE_IMM_REGEX: Pattern = Pattern.compile("^\\[[a-zA-Z][0-9a-zA-Z]+,#-?(0x)?[0-9a-fA-F]+]$")

        /**
         * 从多寄存器操作数字符串中提取寄存器列表。
         * 支持连续寄存器范围（如 r2-r6）和单个寄存器的混合。
         *
         * @param opStr 多寄存器操作数字符串（如 {r2-r6,r10}）。
         * @return 寄存器名称数组，如果不是多寄存器格式则返回 null。
         */
        fun getMultiRegs(opStr: String): Array<String>? {
            val ret = ArrayList<String>()

            // 检查是否为多寄存器格式
            val matcher = MULTI_REG_REGEX.matcher(opStr)
            if (!matcher.matches()) return null

            val ops = opStr.substring(1, opStr.length - 1).split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (op in ops) {
                // 存在连续寄存器范围
                if (op.contains("-")) {
                    val startAndEnd = op.split("-".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    val startId = startAndEnd[0].substring(1).toInt()
                    val endId = startAndEnd[1].substring(1).toInt()
                    val prefix = startAndEnd[0].substring(0, 1)
                    for (i in startId..endId) ret.add(prefix + i)
                } else ret.add(op)
            }

            return ret.toTypedArray<String>()
        }

        /**
         * 获取指令操作数的大小。
         * 根据指令助记符判断是字节、半字还是字操作。
         *
         * @param inst 目标指令。
         * @return 操作数大小（字节），默认为 4 字节。
         */
        fun getInstructionOperandSize(inst: Instruction): Int {
            var size = 4
            if (inst.mnemonicString!! in ArmcmInstConsts.OPERAND_SIZE_1_MNEMONICS)
                size = 1
            else if (inst.mnemonicString!! in ArmcmInstConsts.OPERAND_SIZE_2_MNEMONICS)
                size = 2
            return size
        }
    }
}
