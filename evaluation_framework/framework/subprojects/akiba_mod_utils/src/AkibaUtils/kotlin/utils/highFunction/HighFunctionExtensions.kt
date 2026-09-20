package org.iotsplab.akiba.utils.highFunction

import ghidra.app.decompiler.ClangTokenGroup
import ghidra.app.decompiler.DecompileResults
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Function
import ghidra.program.model.pcode.HighFunction
import ghidra.program.model.pcode.PcodeBlockBasic
import ghidra.util.task.TaskMonitor
import ghidra.util.task.TimeoutTaskMonitor
import java.util.concurrent.TimeUnit

/**
 * 获取高级函数的直接前驱映射。
 * 为每个基本块构建其直接前驱块的集合。
 *
 * @return 映射表，键为基本块，值为其直接前驱块的集合。
 */
fun HighFunction.getDirectAncestorMap(): Map<PcodeBlockBasic, Set<PcodeBlockBasic>> {
    val result: MutableMap<PcodeBlockBasic, MutableSet<PcodeBlockBasic>> = mutableMapOf()
    basicBlocks.forEach { b ->
        // 可能存在没有输出的块，例如返回块
        if (b.trueOut != null)
            result[b.trueOut] ?.add(b) ?: run { result[b.trueOut as PcodeBlockBasic] = mutableSetOf(b) }
        if (b.falseOut != null)
            result[b.falseOut] ?.add(b) ?: run { result[b.falseOut as PcodeBlockBasic] = mutableSetOf(b) }
    }
    return result.mapValues { it.value.toSet() }
}

/**
 * 获取可以到达目标基本块的所有基本块。
 * 通过前驱映射反向搜索所有能够到达目标块的块。
 *
 * @param target 目标基本块。
 * @return 可以到达目标的基本块集合。
 * @throws IllegalArgumentException 如果无法构建前驱映射。
 */
@Throws(IllegalArgumentException::class)
fun HighFunction.getBlocksReachableTo(target: PcodeBlockBasic): Set<PcodeBlockBasic> {
    val ancestorMap = getDirectAncestorMap()
    var result: MutableSet<PcodeBlockBasic> = mutableSetOf()
    val nextRoundResult: MutableSet<PcodeBlockBasic> = ancestorMap[target] ?.toMutableSet() ?: return setOf()

    while (result.size != nextRoundResult.size) {
        result = nextRoundResult.toMutableSet()
        nextRoundResult.clear()
        result.forEach { nextRoundResult.addAll(ancestorMap[it] ?: setOf()) }
    }

    return result
}

/**
 * 获取包含指定地址的基本块。
 * 注意：某些特殊的基本块可能不占用完整的指令，例如 x86 架构中的 cmovxx 条件移动指令会创建一个迷你基本块，
 * 但这种基本块对其他块没有影响，所以我们忽略它。
 *
 * @param addr 要查找的地址。
 * @return 包含该地址的基本块，如果不存在则返回 null。
 */
fun HighFunction.getBlockAt(addr: Address): PcodeBlockBasic? {
    // 可能存在一些特殊的基本块，甚至无法占用单条指令
    // 例如 x86 架构中的 cmovxx，这是一个条件移动指令，会在该指令中创建一个迷你基本块
    // 但这个迷你基本块对其他块没有任何影响，所以我们简单地忽略它
    return basicBlocks.firstOrNull { it.start == addr && it.start != it.stop }
}

/**
 * 获取以指定地址结束的基本块。
 * 与 getBlockAt 相同，忽略特殊的小型基本块。
 *
 * @param addr 要查找的地址。
 * @return 以该地址结束的基本块，如果不存在则返回 null。
 */
fun HighFunction.getBlockEndsWith(addr: Address): PcodeBlockBasic? {
    // Same as getBlockAt
    return basicBlocks.firstOrNull { it.stop == addr && it.start != it.stop }
}

/**
 * 获取函数的默认反编译结果。
 * 使用默认的超时时间（11 秒）对函数进行反编译。
 *
 * @return 反编译结果对象。
 * @throws IllegalArgumentException 如果反编译过程中发生错误。
 */
@Throws(IllegalArgumentException::class)
fun Function.getDefaultDecompResult(): DecompileResults {
    val decompiler = HighFunctionUtil.getDefaultDecompiler(program)
    val result = decompiler.decompileFunction(this, 10, TimeoutTaskMonitor.timeoutIn(11, TimeUnit.SECONDS))
    decompiler.closeProgram()
    return result
}

/**
 * 获取函数的 C 代码结构。
 * 返回反编译后的 C 代码标记组，包含完整的语法树信息。
 *
 * @return C 代码标记组对象。
 */
fun Function.getCCodeStructure(): ClangTokenGroup {
    return getDefaultDecompResult().cCodeMarkup
}

/**
 * 获取函数的 C 代码字符串表示。
 * 返回反编译后的格式化 C 代码文本。
 *
 * @return C 代码字符串。
 */
fun Function.getCCode(): String {
    return getDefaultDecompResult().decompiledFunction.c
}