package org.iotsplab.akiba.utils.assembly

import ghidra.program.model.listing.Instruction
import ghidra.program.model.listing.InstructionIterator
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.abstractFinder.AdvancedAbstractSearcher
import java.util.function.Predicate

/**
 * 汇编指令搜索器。
 * 根据给定的谓词条件在程序中搜索匹配的指令。
 *
 * @param program Ghidra 程序对象。
 * @param predicate 用于匹配指令的谓词条件。
 */
class AsmSearcher(private val program: Program, private val predicate: Predicate<Instruction>)
    : AdvancedAbstractSearcher<Instruction>() {

    /**
     * 执行搜索操作，返回所有匹配的指令列表。
     *
     * @return 匹配的指令列表。
     * @throws Exception 如果搜索过程中发生错误。
     */
    @Throws(Exception::class)
    override fun search() : List<Instruction> {
        val instructions: InstructionIterator = program.listing.getInstructions(true)
        return instructions.filter { i -> predicate.test(i) }
    }
}