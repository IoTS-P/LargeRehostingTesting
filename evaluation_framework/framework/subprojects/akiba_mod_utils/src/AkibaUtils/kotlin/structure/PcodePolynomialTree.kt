package org.iotsplab.akiba.structure

import ghidra.app.emulator.EmulatorHelper
import ghidra.pcode.opbehavior.BinaryOpBehavior
import ghidra.pcode.opbehavior.UnaryOpBehavior
import ghidra.pcode.pcoderaw.PcodeOpRaw
import ghidra.program.model.listing.Program
import ghidra.program.model.pcode.PcodeOp
import ghidra.program.model.pcode.Varnode
import ghidra.util.exception.NotFoundException
import org.iotsplab.akiba.utils.varnode.toPrettyString

/**
 * 表示由 P-code 操作构建的多项式表达式树。
 * 树形结构允许符号执行和表达式操作。
 */
class PcodePolynomialTree(var output: Varnode) {
    /**
     * 多项式表达式树的根节点。
     */
    var root: PcodePolynomialNode = PcodePolynomialNode(output)
    
    /**
     * 字大小（以字节为单位），从输出 varnode 大小派生。
     */
    private val wordSize: Int
        get() = output.size

    /**
     * 从 P-code 操作构造一个多项式树。
     *
     * @param op 用于构建树的 P-code 操作，必须是二元或一元操作。
     * @throws IllegalArgumentException 如果操作不适合构建多项式树则抛出此异常。
     */
    @Throws(IllegalArgumentException::class)
    constructor(op: PcodeOp) : this(op.output) {
        require(PcodeOpRaw(op).behavior is BinaryOpBehavior || PcodeOpRaw(op).behavior is UnaryOpBehavior) {
            "Unexpected op ${op.opcode} for polynomial tree"
        }
        when {
            op.opcode == PcodeOp.COPY -> {
                root = PcodePolynomialNode(op.inputs[0])
            }
            PcodeOpRaw(op).behavior is UnaryOpBehavior -> {
                root = PcodePolynomialNode(op)
                root.children.add(PcodePolynomialNode(op.inputs[0], root))
            }
            PcodeOpRaw(op).behavior is BinaryOpBehavior -> {
                root = PcodePolynomialNode(op)
                root.children.add(PcodePolynomialNode(op.inputs[0], root))
                root.children.add(PcodePolynomialNode(op.inputs[1], root))
            }
            else -> throw IllegalArgumentException("Unexpected op ${op.mnemonic} for polynomial tree")
        }
    }

    /**
     * 获取多项式树中的所有 varnode 参数（叶子节点）。
     *
     * @return 表达式中用作输入的所有非常量 varnode 的列表。
     */
    fun allVarnodeArguments(): List<Varnode> {
        return traverse(root)
    }

    /**
     * 添加新的 P-code 操作以修改树的输出。
     * 通过添加新的根操作来扩展树。
     *
     * 示例：
     * - 原始树：c(output) = a + b
     * - 添加 addOp(d = c - p) 后：d(output) = (a + b) - p
     *
     * @param op 要添加的新 P-code 操作。
     * @param anotherTree 作为第二个操作数的另一个多项式树（用于二元操作）。
     * @throws IllegalArgumentException 如果操作无效或不满足约束则抛出此异常。
     */
    @Throws(IllegalArgumentException::class)
    fun addOp(op: PcodeOp, anotherTree: PcodePolynomialTree? = null) {
        addOp(op, anotherTree?.root)
    }

    /**
     * 添加一个新的 P-code 操作，并将另一个节点作为输入。
     *
     * @param op 要添加的新 P-code 操作。
     * @param anotherNode 作为输入的另一个多项式节点（用于二元操作）。
     * @throws IllegalArgumentException 如果操作类型与提供的参数不匹配则抛出此异常。
     */
    @Throws(IllegalArgumentException::class)
    fun addOp(op: PcodeOp, anotherNode: PcodePolynomialNode? = null) {
        require(PcodeOpRaw(op).behavior is BinaryOpBehavior || PcodeOpRaw(op).behavior is UnaryOpBehavior) {
            "Unexpected op ${op.mnemonic} for polynomial tree"
        }
        when (PcodeOpRaw(op).behavior) {
            is UnaryOpBehavior -> {
                require(anotherNode == null) { "No other tree needed for unary op" }
                require(output == op.inputs[0]) { "New op's input must be the original output for unary op" }
                val opNode = PcodePolynomialNode(op)
                opNode.children.add(root)
                root = opNode
            }
            is BinaryOpBehavior -> {
                require(anotherNode != null) { "Another tree needed to be another input of binary op" }
                val opNode = PcodePolynomialNode(op)
                opNode.children.add(root)
                opNode.children.add(anotherNode)
                root = opNode
            }
        }
        output = op.output
    }

    /**
     * 用新节点替换树中的特定节点。
     *
     * @param node 要被替换的节点。
     * @param newNode 用于替换旧节点的新节点。
     * @throws IllegalArgumentException 如果节点结构无效则抛出此异常。
     */
    @Throws(IllegalArgumentException::class)
    fun replace(node: PcodePolynomialNode, newNode: PcodePolynomialNode) {
        root.replace(node, newNode)
    }

    /**
     * 用新的多项式子树替换 varnode 的所有出现。
     *
     * @param node 要被替换的 varnode。
     * @param newNode 新多项式子树的根节点。
     */
    fun replace(node: Varnode, newNode: PcodePolynomialNode) {
        root.replace(PcodePolynomialNode(node), newNode)
    }

    /**
     * 通过用新的 P-code 操作替换 varnode 来扩展树。
     * 与 addOp 不同，这不会改变输出但会扩展参数。
     *
     * 示例：
     * - 原始树：c(output) = a + b
     * - 扩展：expandOp(b = p + 1) 后：c(output) = a + (p + 1)
     *
     * @param op 用于扩展的 P-code 操作。`op` 的输出必须存在于此树中。
     * @throws IllegalArgumentException 如果在树中找不到操作的输出 varnode 则抛出此异常。
     */
    @Throws(IllegalArgumentException::class)
    fun expandOp(op: PcodeOp) {
        val outputNodes: List<PcodePolynomialNode> = findVarnode(op.output)
        if (outputNodes.isEmpty())
            throw IllegalArgumentException("Output varnode $op not found in this tree")
        outputNodes.forEach {
            it.parent?.swapChildren(it, PcodePolynomialNode(op)) ?: run {
                // We need to swap the root node
                if(root != it || outputNodes.size != 1)
                    throw RuntimeException("Broken polynomial tree")
                root = PcodePolynomialNode(op)
            }
        }
    }

    /**
     * 通过用多项式子树替换 varnode 来扩展树。
     *
     * @param node 要被替换的 varnode（必须存在于树中）。
     * @param treeRoot 用于替换 varnode 的多项式子树的根节点。
     * @throws IllegalArgumentException 如果在树中找不到 varnode 则抛出此异常。
     */
    fun expandOp(node: Varnode, treeRoot: PcodePolynomialNode) {
        val outputNodes: List<PcodePolynomialNode> = findVarnode(node)
        if (outputNodes.isEmpty())
            throw IllegalArgumentException("Output varnode $node not found in this tree")
        outputNodes.forEach {
            it.parent?.swapChildren(it, treeRoot) ?: run {
                // We need to swap the root node
                if(root != it || outputNodes.size != 1)
                    throw RuntimeException("Broken polynomial tree")
                root = treeRoot
            }
        }
    }

    /**
     * 通过合并另一个多项式树来扩展树。
     *
     * @param tree 用于扩展的多项式树。
     */
    fun expandOp(tree: PcodePolynomialTree) {
        expandOp(tree.output, tree.root)
    }

    /**
     * 查找多项式树中特定 varnode 的所有出现。
     *
     * @param varnode 要搜索的 varnode。
     * @return 包含指定 varnode 的节点列表。
     */
    fun findVarnode(varnode: Varnode): List<PcodePolynomialNode> {
        return findVarnode(root, varnode)
    }

    private fun findVarnode(root: PcodePolynomialNode, varnode: Varnode): List<PcodePolynomialNode> {
        if (!root.isOp)
            return if (root.node == varnode) listOf(root) else listOf()
        return root.children.map { findVarnode(it, varnode) }.flatten()
    }

    private fun traverse(root: PcodePolynomialNode): MutableList<Varnode> {
        return if (root.isOp) {
            root.children.map { traverse(it) }.flatten().distinct().toMutableList()
        } else {
            val varnode = root.node!!
            if (!varnode.isConstant)
                mutableListOf(varnode)
            else
                mutableListOf()
        }
    }

    /**
     * 使用提供的 varnode 值映射计算多项式树的值。
     *
     * @param args varnode 到其值的映射。
     * @return 计算结果和输出大小的序对。
     */
    fun calculate(args: Map<Varnode, Long>): Pair<Long, Int> {
        return recursiveCalculate(root, args)
    }

    /**
     * 使用模拟器上下文计算多项式树的值。
     * 自动获取所有需要的 varnode 参数并从模拟器中读取它们的值。
     *
     * @param context 提供内存和寄存器值的模拟器助手。
     * @return 计算结果和输出大小的序对。
     * @throws UnsupportedOperationException 如果遇到不支持的 varnode 类型则抛出此异常。
     */
    @Throws(UnsupportedOperationException::class)
    fun calculate(context: EmulatorHelper): Pair<Long, Int> {
        val allNeededVarnodes = allVarnodeArguments()
        allNeededVarnodes.forEach {
            if (!it.isAddress && !it.isRegister)
                throw UnsupportedOperationException("Unsupported varnode type: ${it.address.addressSpace.name}")
        }
        val allNeededData = allNeededVarnodes.associateWith { varnode ->
            when {
                varnode.isAddress || varnode.isRegister ->
                    context.emulator.memState.getValue(varnode)

                else -> throw UnsupportedOperationException(
                    "Unsupported varnode type: ${varnode.address.addressSpace.name}"
                )
            }
        }
        return calculate(allNeededData)
    }

    /**
     * 递归计算多项式树的值。
     *
     * @param node 树的根节点。
     * @param args varnode 到其值的映射。
     * @return 计算结果和输出大小的序对。
     * @throws NotFoundException 如果在 args 中找不到非常量 varnode 则抛出此异常。
     * @throws RuntimeException 如果树结构损坏或遇到意外操作则抛出此异常。
     */
    @Throws(NotFoundException::class, RuntimeException::class)
    private fun recursiveCalculate(node: PcodePolynomialNode, args: Map<Varnode, Long>): Pair<Long, Int> {
        if (!node.isOp)
            return if (node.node!!.isConstant) (node.node!!.offset to node.node!!.size)
                   else node.node!!.let { args[it] } ?.let { it to node.outputSize }
                                                     ?: throw NotFoundException("${node.node} not found in args")
        node.op ?: throw RuntimeException("Broken polynomial tree")
        when (val behavior = PcodeOpRaw(node.op!!).behavior) {
            is UnaryOpBehavior -> {
                val input = recursiveCalculate(node.children[0], args)
                val output = behavior.evaluateUnary(node.outputSize, input.second, input.first)
                return output to node.outputSize
            }
            is BinaryOpBehavior -> {
                val left = recursiveCalculate(node.children[0], args)
                val right = recursiveCalculate(node.children[1], args)
                val output = behavior.evaluateBinary(node.outputSize, left.second, left.first, right.first)
                return output to node.outputSize
            }
            else -> throw RuntimeException("Unexpected operation for op ${node.op}")
        }
    }

    /**
     * 将多项式树转换为漂亮的字符串表示。
     *
     * 字符串格式:
     * 1. 每个参数可以是寄存器或唯一的 varnode：
     *    - 寄存器：`R_<reg_name>_<size>`（例如：`R_RAX_8`）
     *    - 地址：`A_<space_name>:<offset>_<size>`（例如：`A_ram:0x4000_4`）
     *    - 唯一 varnode: `U_<offset>_<size>`（例如：`U_0x100_4`）
     * 2. 参数使用代表 P-code 操作的符号连接：
     *    - `+`/`f+`: INT_ADD, FLOAT_ADD
     *    - `-`/`f-`: INT_SUB, FLOAT_SUB
     *    - `*`/`f*`: INT_MULT, FLOAT_MULT
     *    - `/`/`f/`: INT_DIV, FLOAT_DIV
     *    - `s/`: INT_SDIV (signed division)
     *    - `%`/`s%`: INT_REM, INT_SREM
     *    - `==`/`f==`: INT_EQUAL, FLOAT_EQUAL
     *    - `!=`/`f!=`: INT_NOTEQUAL, FLOAT_NOTEQUAL
     *    - `<`/`f<`: INT_LESS, FLOAT_LESS
     *    - `s<`: INT_SLESS (signed less than)
     *    - `<=`/`f<=`: INT_LESSEQUAL, FLOAT_LESSEQUAL
     *    - `s<=`: INT_SLESSEQUAL (signed less or equal)
     *    - `-`/`f-`: INT_2COMP, FLOAT_NEG (negation)
     *    - `~`/`!`: INT_NEGATE, BOOL_NEGATE
     *    - `^`/`^^`: INT_XOR, BOOL_XOR
     *    - `&`/`&&`: INT_AND, BOOL_AND
     *    - `|`/`||`: INT_OR, BOOL_OR
     *    - `<<`: INT_LEFT
     *    - `>>`: INT_RIGHT
     *    - `s>>`: INT_SRIGHT (signed right shift)
     *    - `int2float<[int_size]>(...)`: INT2FLOAT
     *    - `float2float<[float_size]>(...)`: FLOAT2FLOAT
     *    - `trunc<[int_size]>(...)`: FLOAT_TRUNC
     *    - `zext<[new_size]>(...)`/`sext<[new_size]>(...)`: INT_ZEXT, INT_SEXT
     *    - 其他操作表示为函数，例如 `piece(a, b)`
     *
     * @return 表示多项式表达式的漂亮字符串。
     */
    override fun toString(): String {
        return "${output.toPrettyString()} = $root"
    }

    fun toString(program: Program): String {
        return "${output.toPrettyString(program)} = ${root.toString(program)}"
    }
}