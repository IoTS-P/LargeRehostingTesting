package org.iotsplab.akiba.utils.memory

import ghidra.features.base.memsearch.format.SearchFormat
import ghidra.features.base.memsearch.gui.SearchSettings
import ghidra.features.base.memsearch.matcher.ByteMatcher
import ghidra.features.base.memsearch.matcher.RegExByteMatcher
import ghidra.features.base.memsearch.searcher.MemoryMatch
import ghidra.program.disassemble.Disassembler
import ghidra.program.model.address.AddressSet
import ghidra.program.model.listing.Program
import ghidra.program.model.mem.Memory
import ghidra.util.task.TaskMonitor
import java.util.function.Predicate
import java.util.regex.Pattern

/**
 * 正则表达式模式匹配器。
 * 使用正则表达式匹配内存内容的字节模式。
 *
 * @param memory Ghidra 内存对象。
 * @param pattern 用于匹配的正则表达式模式。
 */
class RegexPattern(private val memory: Memory, private val pattern: Pattern) : Predicate<MemoryMatch> {
    override fun test(matchTarget: MemoryMatch): Boolean {
        val memContent: String = MemoryUtil.readProgramBytesToUTF8String(memory, matchTarget)
        return pattern.matcher(memContent).matches()
    }

    /**
     * 转换为字节匹配器。
     * 将正则表达式模式转换为可用于内存搜索的 ByteMatcher 对象。
     *
     * @param program Ghidra 程序对象。
     * @return 字节匹配器对象。
     */
    fun toMatcher(program: Program): ByteMatcher {
        return RegExByteMatcher(pattern.toString(), SearchSettings().withSearchFormat(SearchFormat.REG_EX))
    }

    companion object {
        /**
         * 创建小字符串匹配模式。
         * 匹配可打印 ASCII 字符和常见空白字符的序列。
         *
         * @param memory Ghidra 内存对象。
         * @return 正则表达式模式对象。
         */
        @JvmStatic
        fun smallStringsPattern(memory: Memory): RegexPattern {
            return RegexPattern(memory, Pattern.compile("[\\x00\t\n\r\\x20-\\x7e]+"))
        }
    }
}

/**
 * 反汇编尝试模式匹配器。
 * 尝试对匹配的内存区域进行反汇编，用于验证是否为有效代码。
 *
 * @param memory Ghidra 内存对象。
 * @param monitor 任务监视器。
 */
class DisasmAttemptPattern(private val memory: Memory, private val monitor: TaskMonitor) : Predicate<MemoryMatch> {
    override fun test(matchTarget: MemoryMatch): Boolean {
        // 我们不会使用 matchTarget 中的 size 元素
        val disassembler = Disassembler.getDisassembler(memory.program, monitor, null)
        val targetSet = AddressSet(matchTarget.address)
        disassembler.disassemble(matchTarget.address, targetSet, false)

        return true
    }
}