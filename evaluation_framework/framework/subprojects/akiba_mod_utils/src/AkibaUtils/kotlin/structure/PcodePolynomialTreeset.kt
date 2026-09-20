package org.iotsplab.akiba.structure

import ghidra.app.emulator.EmulatorHelper
import ghidra.program.model.pcode.PcodeOp
import ghidra.program.model.pcode.Varnode
import ghidra.util.exception.NotFoundException

/**
 * 管理一组多项式表达式树并提供统一的计算接口。
 * 自动处理树的构建、合并和依赖解析。
 */
class PcodePolynomialTreeset {
    /**
     * 多项式表达式树的内部存储。
     */
    private val trees: MutableList<PcodePolynomialTree> = mutableListOf()

    /**
     * 将 P-code 操作添加到 treeset，自动构建或更新多项式树。
     * 处理 varnode 同时作为输入和输出的复杂情况。
     *
     * 此方法执行以下步骤：
     * 1. 为没有现有表示的输入 varnode 创建新树。
     * 2. 检查输出 varnode 的树是否已存在：
     *    - 如果存在：删除旧表达式并用新操作替换。
     *    - 如果不存在：创建新树并合并依赖的子树。
     *
     * @param op 要添加的 P-code 操作。
     * @throws IllegalArgumentException 如果无法处理该操作则抛出此异常。
     */
    @Throws(IllegalArgumentException::class)
    fun addPcode(op: PcodeOp) {
        op.inputs.forEach { input ->
            if (!input.isConstant && trees.none { it.output == input })
                trees.add(PcodePolynomialTree(input))
        }

        // Find if the tree representing this output varnode exists:
        // If so: remove all its expressions and replace it with this new one
        //      - NOTE: Need to handle the case like `xxx INT_ADD xxx, yyy`, a varnode cannot be both input and output
        // If not: add a new tree
        trees.indices.firstOrNull { idx -> trees[idx].output == op.output }
            ?.let { idx ->
                val newTree = PcodePolynomialTree(op)
                newTree.allVarnodeArguments().mapNotNull { varnode -> trees.find { it.output == varnode } }
                    .forEach { newTree.replace(it.output, it.root) }
                trees[idx] = newTree
            } ?:run {
                val newTree = PcodePolynomialTree(op)
                newTree.allVarnodeArguments().mapNotNull { varnode -> trees.find { it.output == varnode } }
                    .forEach { newTree.replace(it.output, it.root) }
                if (op.opcode == PcodeOp.COPY && !op.inputs[0].isConstant) {
                    newTree.root = trees.find { it.output == op.inputs[0] }?.root ?: run {
                        trees.add(PcodePolynomialTree(op.inputs[0]))
                        trees.last().root
                    }
                }
                trees.add(newTree)
            }
    }

    /**
     * 使用模拟器上下文计算目标 varnode 的值。
     *
     * @param context 提供内存和寄存器值的模拟器助手。
     * @param target 要计算的 varnode。
     * @return 包含计算值和大小的序对。
     * @throws NotFoundException 如果在任何树中都找不到目标 varnode 则抛出此异常。
     */
    @Throws(NotFoundException::class)
    fun calculate(context: EmulatorHelper, target: Varnode): Pair<Long, Int> {
        if (target.isAddress || target.isConstant)
            return Pair(target.address.offset, target.size)
        trees.find { it.output == target }?.let { tree -> return tree.calculate(context) }
            ?: throw NotFoundException("Target varnode not found")
    }
}