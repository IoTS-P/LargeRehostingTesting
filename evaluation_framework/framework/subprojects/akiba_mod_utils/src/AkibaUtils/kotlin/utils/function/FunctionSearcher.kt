package org.iotsplab.akiba.utils.function

import ghidra.program.model.listing.Function
import ghidra.program.model.listing.FunctionIterator
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.abstractFinder.AdvancedAbstractSearcher
import java.util.function.Predicate

/**
 * 函数搜索器。
 * 根据给定的谓词条件在程序中搜索匹配的函数。
 *
 * @param program 要搜索的 Ghidra 程序。
 * @param predicate 用于匹配函数的谓词条件。
 */
class FunctionSearcher(private val program: Program, private val predicate: Predicate<Function>)
    : AdvancedAbstractSearcher<Function>() {

    /**
     * 执行函数搜索。
     * 遍历程序中的所有函数，并返回满足谓词条件的函数列表。
     *
     * @return 匹配的函数列表。
     * @throws Exception 如果搜索过程中发生错误。
     */
    @Throws(Exception::class)
    override fun search() : List<Function> {
        val functions: FunctionIterator = program.listing.getFunctions(true)
        return functions.filter { p -> predicate.test(p) }
    }
}