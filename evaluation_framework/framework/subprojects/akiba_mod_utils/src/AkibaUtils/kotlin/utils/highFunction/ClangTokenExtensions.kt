package org.iotsplab.akiba.utils.highFunction

import ghidra.app.decompiler.ClangNode
import ghidra.app.decompiler.ClangStatement
import ghidra.app.decompiler.ClangTokenGroup
import ghidra.app.decompiler.ClangVariableToken
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Program
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.iotsplab.akiba.utils.memory.tryParseToLong
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.Executors.newFixedThreadPool
import java.util.concurrent.ForkJoinPool

/**
 * 递归搜索 Clang 标记。
 * 通过给定的谓词条件递归搜索 Clang 标记树。
 *
 * @param stopRecWhileMatched 匹配时停止深度搜索，即如果父节点匹配成功，则不再搜索其子节点。
 * @param predicate           用于匹配的谓词函数。
 * @return                    匹配的标记列表。
 */
fun ClangNode.searchRecursive(
    stopRecWhileMatched: Boolean = false,
    predicate: (ClangNode) -> Boolean): List<ClangNode> {
    val ret = mutableListOf<ClangNode>()
    if (predicate(this))
        ret.add(this)
    if (!stopRecWhileMatched)
        ret.addAll((0..<numChildren()).flatMap {
            Child(it).searchRecursive(stopRecWhileMatched, predicate) })
    return ret
}

/**
 * 获取 Clang 标记组中的所有语句。
 *
 * @return 包含所有 ClangStatement 对象的列表。
 */
fun ClangTokenGroup.allStatements(): List<ClangStatement> {
    return searchRecursive { it is ClangStatement }.map { it as ClangStatement }
}

/**
 * 获取 Clang 标记组中所有未映射的数据地址。
 * 查找形如 DAT_xxx 的符号并返回其对应的地址，过滤掉已在内存中的地址。
 *
 * @param program Ghidra 程序对象，用于检查地址是否存在于内存中。
 * @return 未映射数据地址的排序列表。
 */
fun ClangTokenGroup.allUnmappedDataAddress(program: Program): List<Address> {
    val api = FlatProgramAPI(program)
    return searchRecursive { Regex("_?DAT_[0-9a-f]+").matches(it.toString()) }
        .map { api.toAddr(it.toString().split("_").last()) }
        .filter { !program.memory.contains(it) }
        .distinct().sortedBy { it.offset }
}

/**
 * 获取程序中所有未映射的数据地址（C 代码级别）。
 * 通过反编译程序中的所有函数来查找未映射的 DAT_xxx 符号。
 * 注意：此函数会反编译程序中的所有函数，因此可能需要很长时间，我们提供 `threadNumber` 参数来控制反编译的线程数。
 *
 * @param threadNumber 用于反编译的线程数。
 * @return 包含所有未映射地址列表和失败函数列表的对（那些反编译失败的函数）。
 */
suspend fun Program.allUnmappedDataAddressInC(threadNumber: Int = 1): Pair<List<Address>, List<Function>> {
    val failedFunctions = ConcurrentSkipListSet<Function> { func1: Function, func2: Function ->
        func1.entryPoint.offset.compareTo(func2.entryPoint.offset)
    }
    val addresses = ConcurrentSkipListSet<Address>()
    val dispatcher = ForkJoinPool(threadNumber).asCoroutineDispatcher()
    coroutineScope {
        functionManager.getFunctions(true).map { func -> async(dispatcher) {
            // In some cases, the decompiling process may fail due to bad function definitions,
            // so we need to check if it is null
            addresses.addAll(func.getDefaultDecompResult().cCodeMarkup
                ?.allUnmappedDataAddress(this@allUnmappedDataAddressInC)
                ?: run {
                failedFunctions.add(func)
                listOf()
            })
        } }.awaitAll()
    }
    return addresses.toList() to failedFunctions.toList()
}

/**
 * 获取 Clang 标记组中的所有标量值。
 * 查找所有可以解析为 Long 类型的变量标记。
 *
 * @return 不重复的标量值列表。
 */
fun ClangTokenGroup.allScalars(): List<Long> {
    return searchRecursive {
        it is ClangVariableToken && tryParseToLong(it.toString()) != null
    }.map { tryParseToLong(it.toString())!! }.distinct()
}

/**
 * 获取程序中所有的标量值（C 代码级别）。
 * 通过反编译程序中的所有函数来提取 C 代码中的标量常量。
 * 注意：此函数会反编译程序中的所有函数，因此可能需要很长时间，我们提供 `threadNumber` 参数来控制反编译的线程数。
 *
 * @param threadNumber 用于反编译的线程数。
 * @return 包含所有标量值列表和失败函数列表的对（那些反编译失败的函数）。
 */
suspend fun Program.allScalarsInC(threadNumber: Int = 1): Pair<List<Long>, List<Function>> {
    val failedFunctions = ConcurrentSkipListSet<Function> { func1: Function, func2: Function ->
        func1.entryPoint.offset.compareTo(func2.entryPoint.offset)
    }
    val scalars = ConcurrentSkipListSet<Long>()
    val pool = newFixedThreadPool(threadNumber)
    val dispatcher = pool.asCoroutineDispatcher()
    coroutineScope {
        functionManager.getFunctions(true).map { func -> async(dispatcher) {
            // In some cases, the decompiling process may fail due to bad function definitions,
            // so we need to check if it is null
            scalars.addAll(func.getDefaultDecompResult().cCodeMarkup
                ?.allScalars()
                ?: run {
                failedFunctions.add(func)
                listOf()
            })
        } }.awaitAll()
    }
    dispatcher.close()
    return scalars.toList() to failedFunctions.toList()
}