package org.iotsplab.akiba.utils.function

import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Instruction
import ghidra.program.model.mem.MemoryAccessException
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.utils.assembly.isGenericJump
import java.util.function.Predicate
import java.util.regex.Pattern

/**
 * 默认的函数末尾匹配器。
 * 匹配最后一条指令为通用跳转指令的函数。
 */
val defaultFunctionEpilogueMatcher = AsmPatternEntry(-1, isGenericJump)

/**
 * 助记符模式条目。
 * 通过正则表达式匹配指定位置指令的助记符。
 *
 * @param index 指令在函数中的索引（负数表示从末尾计数）。
 * @param pattern 用于匹配的正则表达式模式。
 */
class MnemonicPatternEntry(private val index: Int, private val pattern: String) : Predicate<Function> {
    override fun test(matchTarget: Function): Boolean {
        val instructions = matchTarget.allInstructions()
        val realIndex = if (index < 0) instructions.size + index else index
        if (realIndex < 0 || realIndex >= instructions.size) return false
        val inst = instructions[realIndex]
        val mnemonic = inst.toString()
        return Pattern.compile(pattern).matcher(mnemonic).matches()
    }

    override fun hashCode(): Int {
        return Integer.hashCode(Integer.hashCode(index) xor pattern.hashCode())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false

        other as MnemonicPatternEntry

        if (index != other.index) return false
        if (pattern != other.pattern) return false

        return true
    }
}

/**
 * 二进制模式条目。
 * 通过比较指定位置指令的二进制字节序列来匹配。
 *
 * @param index 指令在函数中的索引（负数表示从末尾计数）。
 * @param pattern 用于匹配的字节数组模式。
 */
class BinaryPatternEntry(private val index: Int, private val pattern: ByteArray) : Predicate<Function> {
    override fun test(matchTarget: Function): Boolean {
        val instructions = matchTarget.allInstructions()
        val realIndex = if (index < 0) instructions.size + index else index
        if (realIndex < 0 || realIndex >= instructions.size) return false
        val inst = instructions[realIndex]
        try {
            val instBytes = inst.bytes
            return instBytes.contentEquals(pattern)
        } catch (_: MemoryAccessException) {
            globalLogger.error(
                String.format(
                    "Memory access failed at %s%#x, but this should not happen",
                    inst.address.addressSpace.toString(), inst.address.offset
                )
            )
            assert(false)
            return false
        }
    }

    override fun hashCode(): Int {
        return Integer.hashCode(Integer.hashCode(index) xor pattern.contentHashCode())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false

        other as BinaryPatternEntry

        if (index != other.index) return false
        if (!pattern.contentEquals(other.pattern)) return false

        return true
    }
}

/**
 * 汇编模式条目。
 * 使用指令谓词来匹配指定位置的指令。
 *
 * @param index 指令在函数中的索引（负数表示从末尾计数）。
 * @param pattern 用于匹配指令的谓词。
 */
class AsmPatternEntry(private val index: Int, private val pattern: Predicate<Instruction>): Predicate<Function> {
    override fun test(matchTarget: Function): Boolean {
        val instructions = matchTarget.allInstructions()
        val realIndex = if (index < 0) instructions.size + index else index
        if (realIndex < 0 || realIndex >= instructions.size) return false
        val inst = instructions[realIndex]
        return pattern.test(inst)
    }
}