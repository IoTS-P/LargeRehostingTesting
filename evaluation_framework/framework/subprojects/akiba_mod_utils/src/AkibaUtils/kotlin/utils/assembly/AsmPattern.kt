package org.iotsplab.akiba.utils.assembly

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Instruction
import ghidra.program.model.pcode.PcodeOp
import ghidra.program.model.pcode.Varnode
import org.iotsplab.akiba.utils.pcode.PcodeConstants.JUMP_OPCODES
import java.util.function.Predicate
import kotlin.reflect.KClass

/**
 * 操作数匹配谓词。
 * 检查指定索引的操作数是否等于目标字符串。
 *
 * @param opIndex 操作数索引。
 * @param targetOp 目标操作数字符串。
 */
class operandIs(private val opIndex: Int, private val targetOp: String) : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val armOp = ArmcmOperand(matchTarget, opIndex)
        return armOp.equals(targetOp)
    }
}

/**
 * 操作数类型匹配谓词。
 * 检查指定索引的操作数是否为特定类型。
 *
 * @param opIndex 操作数索引。
 * @param opType 目标操作数类型。
 */
class operandTypeIs(private val opIndex: Int, private val opType: Int) : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val armOp = ArmcmOperand(matchTarget, opIndex)
        return armOp.getOperandType() == opType
    }
}

/**
 * 子操作数类型匹配谓词。
 * 检查指定索引的子操作数是否为特定类型。
 *
 * @param opIndex 操作数索引。
 * @param subIndex 子操作数索引，默认为 0。
 * @param type 目标类型的 KClass。
 */
class subOperandIs<T: Any>(private val opIndex: Int, private val subIndex: Int = 0, private val type: KClass<T>)
    : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        return type.isInstance(matchTarget.getOpObjects(opIndex)[subIndex])
    }
}

/**
 * 助记符精确匹配谓词。
 * 检查指令的助记符是否完全匹配目标字符串（忽略大小写）。
 *
 * @param mnemonic 目标助记符。
 */
class mnemonicIs(private val mnemonic: String) : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        return matchTarget.toString().split(" ")[0].equals(mnemonic, ignoreCase = true)
    }
}

/**
 * 助记符前缀匹配谓词。
 * 检查指令的助记符是否以目标字符串开头（忽略大小写）。
 *
 * @param mnemonic 目标助记符前缀。
 */
class mnemonicStartsWith(private val mnemonic: String) : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        return matchTarget.toString().split(" ")[0].startsWith(mnemonic, ignoreCase = true)
    }
}

/**
 * 助记符正则表达式匹配谓词。
 * 检查指令的完整字符串是否匹配给定的正则表达式模式。
 *
 * @param pattern 用于匹配的正则表达式模式。
 */
class mnemonicLike(private val pattern: Regex) : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        return pattern.matches(matchTarget.toString())
    }
}

/**
 * 分支指令判断对象。
 * 检查指令是否为分支指令（最后一条 P-code 包含 BRANCH）。
 */
object isBranch : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return pcs[pcs.size - 1].toString().contains("BRANCH")
    }
}

/**
 * 条件分支指令判断对象。
 * 检查指令是否为条件分支指令（最后一条 P-code 以 CBRANCH 开头）。
 */
object isConditionalBranch : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return pcs[pcs.size - 1].toString().startsWith("CBRANCH")
    }
}

/**
 * 调用指令判断对象。
 * 检查指令是否为调用指令（CALL 或 CALLIND）。
 */
object isCall : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return if (pcs.isEmpty()) false else pcs[pcs.size - 1].opcode in listOf(PcodeOp.CALL, PcodeOp.CALLIND)
    }
}

/**
 * 直接调用指令判断对象。
 * 检查指令是否为直接调用（CALL 且第一个操作数为地址）。
 */
object isDirectCall : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return if (pcs.isEmpty()) false else pcs[pcs.size - 1].opcode == PcodeOp.CALL &&
                matchTarget.getOpObjects(0)[0] is Address
    }
}

/**
 * 返回指令判断对象。
 * 检查指令是否为返回指令（RETURN）。
 */
object isReturn : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return pcs[pcs.size - 1].opcode == PcodeOp.RETURN
    }
}

/**
 * 加载指令判断对象。
 * 检查指令是否包含 LOAD 操作或 COPY 到地址空间的操作。
 */
object isLoad: Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return pcs.any { pc ->
             when (pc.opcode) {
                 PcodeOp.COPY -> {
                     val input: Varnode = pc.inputs[0]
                     input.isAddress
                 }
                 PcodeOp.LOAD -> true
                 else -> false
             }
        }
    }
}

/**
 * 存储指令判断对象。
 * 检查指令是否包含 STORE 操作或从寄存器 COPY 到地址空间的操作。
 */
object isStore: Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return pcs.any { pc ->
            when (pc.opcode) {
                PcodeOp.COPY -> {
                    val output: Varnode = pc.output
                    output.isAddress
                }
                PcodeOp.STORE -> true
                else -> false
            }
        }
    }
}

/**
 * 内存读写指令判断对象。
 * 检查指令是否为加载或存储指令。
 */
object isMemoryRW : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        return isLoad.test(matchTarget) || isStore.test(matchTarget)
    }
}

/**
 * 通用跳转指令判断对象。
 * 检查指令是否为跳转指令（包括条件跳转和无条件跳转）。
 */
object isGenericJump: Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        if (pcs.isEmpty())      // 对于 `nop` 等指令可能发生这种情况
            return false
        return JUMP_OPCODES.contains(pcs.last().opcode)
    }
}