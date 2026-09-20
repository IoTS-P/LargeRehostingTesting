package org.iotsplab.akiba.utils.assembly

import ghidra.app.emulator.EmulatorHelper
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.listing.Instruction
import ghidra.program.model.pcode.PcodeOp
import ghidra.program.model.pcode.Varnode
import org.iotsplab.akiba.structure.DataflowManager
import org.iotsplab.akiba.structure.PcodePolynomialTree
import org.iotsplab.akiba.structure.PcodePolynomialTreeset
import org.iotsplab.akiba.utils.pcode.PcodeConstants
import org.iotsplab.akiba.utils.pcode.PcodeUtil

/**
 * 判断指令是否可以作为基本块的结束。
 * 检查指令的 P-code 中是否包含跳转操作。
 *
 * @return 如果指令可以结束基本块则返回 true。
 */
fun Instruction.isValidToEndBasicBlock(): Boolean {
    return this.pcode.any {
        it.opcode in PcodeConstants.JUMP_OPCODES
    }
}

/**
 * 获取指令中指定 varnode 的多项式树。
 * 自动查找 varnode 在 P-code 中的位置并构建树。
 *
 * @param dst 目标 varnode。
 * @return 构建的多项式树。
 * @throws IllegalArgumentException 如果在指令中找不到指定的 varnode。
 */
@Throws(IllegalArgumentException::class)
private fun Instruction.getPolynomialTree(dst: Varnode): PcodePolynomialTree {
    val opIdx = pcode.indices.reversed().firstOrNull { pcode[it].output == dst }
        ?: throw IllegalArgumentException("Varnode $dst not found in instruction $mnemonicString")
    return getPolynomialTree(dst, opIdx)
}

/**
 * 获取指令中指定 varnode 在特定 P-code 索引处的多项式树。
 * 递归展开所有 unique varnode 直到所有输入都是非 unique 的。
 *
 * @param dst 目标 varnode。
 * @param opIdx P-code 操作索引。
 * @param isNotOutput 如果为 true，表示 dst 不是该 P-code 的输出。
 * @return 构建的多项式树。
 * @throws IllegalArgumentException 如果在指令中找不到指定的 varnode。
 */
@Throws(IllegalArgumentException::class)
fun Instruction.getPolynomialTree(dst: Varnode, opIdx: Int, isNotOutput: Boolean = false): PcodePolynomialTree {
    val tree = if (!isNotOutput && dst == pcode[opIdx].output) PcodePolynomialTree(pcode[opIdx])
    else PcodePolynomialTree(dst)
    while (true) {
        val uniqueVarnodes = tree.allVarnodeArguments().filter { it.isUnique }
        // 循环不会是死循环，因为 unique varnode 不可能是无根的
        // （unique varnode 在被赋值为某个 P-code 的输出之前不能作为输入使用）
        if (uniqueVarnodes.isEmpty())
            break
        uniqueVarnodes.forEach { uv ->
            (0..<opIdx).reversed().first { pcode[it].output == uv }
                .let { idx -> tree.expandOp(getPolynomialTree(uv, idx)) }
        }
    }
    return tree
}

/**
 * 分析指令的所有内存访问。
 * 为指令的每个 P-code 构建多项式树来表达 varnode 的计算过程，然后查找所有 load/store 指令并记录它们的内存访问。
 * 由于 LOAD 和 STORE pcodeop（较低表示）只接受地址空间 ID 和偏移量两个输入，因此通过分析这些树，我们可以得到地址值的计算过程。
 * 另外，varnode 包含大小信息，所以我们不需要自己处理不同大小的情况。
 * 注意：那些被加载但在寄存器中改变的内存数据将被丢弃。
 *
 * @param context 执行指令的模拟器上下文，用于计算多项式表达式。
 * @param snapshot 数据流管理器，用于记录内存访问条件和寄存器状态。
 * @throws RuntimeException 如果多项式树计算过程中发生错误。
 * @throws AssertionError 如果 varnode 类型断言失败。
 */
@Throws(RuntimeException::class, AssertionError::class)
fun Instruction.analyzeMemoryDataFlow(
    context: EmulatorHelper,
    snapshot: DataflowManager = DataflowManager()
) {
    val program = context.program
    val trees = PcodePolynomialTreeset()
    snapshot.willJump = false
    snapshot.jumpTarget = null
    pcode.forEachIndexed { idx, it ->
        when (it.opcode) {
            PcodeOp.COPY -> {
                val (dst, src) = it.output to it.inputs[0]
                if (dst == src)
                    return@forEachIndexed
                when {
                    dst.isRegister && src.isAddress -> {
                        snapshot.registersWithMemoryData[program.getRegister(dst)] = src
                    }
                    dst.isAddress && src.isRegister -> {
                        snapshot.registersWithMemoryData[program.getRegister(src)] ?.let { addrVarnode ->
                            snapshot.dataFlowRecorder.add(addrVarnode to dst)
                        }
                    }
                    else -> trees.addPcode(it)
                }
            }
            PcodeOp.LOAD -> {
                val (src, dst) = PcodeUtil.parseLoad(program, it)
                val addrValue = trees.calculate(context, src)
                val addrVarnode = Varnode(FlatProgramAPI(program).toAddr(addrValue.first), addrValue.second)
                assert(dst.isRegister)
                snapshot.registersWithMemoryData[program.getRegister(dst)] = addrVarnode
            }
            PcodeOp.STORE -> {
                val (src, dst) = PcodeUtil.parseStore(program, it)
                val addrValue = trees.calculate(context, dst)
                val addrVarnode = Varnode(FlatProgramAPI(program).toAddr(addrValue.first), addrValue.second)
                if (src.isRegister)
                    snapshot.registersWithMemoryData[program.getRegister(src)]?.let { srcAddr ->
                        snapshot.dataFlowRecorder.add(srcAddr to addrVarnode)
                    } ?: run {
                        // 记录存储零值的 store 指令，这对于获取未初始化的全局变量内存区域很有用
                        if (context.readRegister(program.getRegister(src)) == 0.toBigInteger())
                            snapshot.zeroStoreRecorder.add(addrVarnode)
                    }
                else {
                    val srcAddr = trees.calculate(context, src)
                    if (srcAddr.first == 0L)
                        snapshot.zeroStoreRecorder.add(addrVarnode)
                }
            }
            PcodeOp.CBRANCH -> {
                val conditionValue = trees.calculate(context, it.inputs[1])
                if (conditionValue.first != 0L) {
                    snapshot.willJump = true
                    snapshot.jumpTarget = FlatProgramAPI(program).toAddr(trees.calculate(context, it.inputs[0]).first)
                    return
                }
            }
            else -> {
                if (idx == pcode.indices.last) {
                    if (it.opcode in PcodeConstants.JUMP_OPCODES) {
                        snapshot.willJump = true
                        snapshot.jumpTarget =
                            FlatProgramAPI(program).toAddr(trees.calculate(context, it.inputs[0]).first)
                    }
                    return@forEachIndexed
                }
                else {
                    try { trees.addPcode(it) } catch (_: Exception) {}
                }
            }
        }
    }
}