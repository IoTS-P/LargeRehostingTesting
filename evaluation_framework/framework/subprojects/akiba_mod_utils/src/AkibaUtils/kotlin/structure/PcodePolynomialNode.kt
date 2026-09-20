package org.iotsplab.akiba.structure

import ghidra.pcode.opbehavior.BinaryOpBehavior
import ghidra.pcode.opbehavior.UnaryOpBehavior
import ghidra.pcode.pcoderaw.PcodeOpRaw
import ghidra.program.model.listing.Program
import ghidra.program.model.pcode.PcodeOp
import ghidra.program.model.pcode.Varnode
import org.iotsplab.akiba.utils.pcode.PcodeConstants.BINARY_OPCODES
import org.iotsplab.akiba.utils.pcode.PcodeConstants.FUNC_BINARY_OPCODES
import org.iotsplab.akiba.utils.pcode.PcodeConstants.FUNC_UNARY_OPCODES
import org.iotsplab.akiba.utils.pcode.PcodeConstants.FUNC_UNARY_OPCODES_WITH_SIZE
import org.iotsplab.akiba.utils.pcode.PcodeConstants.OPCODE_SYMBOLS
import org.iotsplab.akiba.utils.pcode.PcodeConstants.UNARY_OPCODES
import org.iotsplab.akiba.utils.varnode.toPrettyString

/**
 * 表示 P-code 多项式表达式树中的节点。
 * 每个节点可以是操作节点（带有 P-code 操作符和子节点）或叶子节点（带有一个 varnode）。
 */
class PcodePolynomialNode {
    /**
     * 多项式树结构中的父节点。
     */
    var parent: PcodePolynomialNode? = null
    
    /**
     * 指示此节点是否表示 P-code 操作（true）或叶子 varnode（false）。
     */
    var isOp: Boolean = false
    
    /**
     * 与此节点关联的 P-code 操作（如果 isOp 为 true）。
     */
    var op: PcodeOp? = null
    
    /**
     * 此节点的输出大小（以字节为单位）。
     */
    var outputSize: Int = 4
    
    /**
     * 与此节点关联的 varnode（如果 isOp 为 false，即叶子节点）。
     */
    var node: Varnode? = null
    
    /**
     * 此节点的子节点（仅适用于操作节点）。
     */
    var children: MutableList<PcodePolynomialNode> = mutableListOf()

    /**
     * 从 P-code 操作构造一个操作节点。
     *
     * @param op P-code 操作，必须是二元或一元操作。
     * @param parent 多项式树中的父节点。
     * @throws IllegalArgumentException 如果操作不是二元或一元操作则抛出此异常。
     */
    constructor(op: PcodeOp, parent: PcodePolynomialNode? = null) {
        require(PcodeOpRaw(op).behavior is BinaryOpBehavior || PcodeOpRaw(op).behavior is UnaryOpBehavior) {
            "Unexpected op ${op.opcode} for polynomial tree"
        }
        this.op = op
        this.parent = parent
        this.outputSize = op.output.size
        isOp = true
    }

    /**
     * 从 varnode 构造一个叶子节点。
     *
     * @param node 要表示为叶子的 varnode。
     * @param parent 多项式树中的父节点。
     */
    constructor(node: Varnode, parent: PcodePolynomialNode? = null) {
        this.node = node
        this.parent = parent
        this.outputSize = node.size
        isOp = false
    }

    /**
     * 在同一位置用新节点交换子节点。
     *
     * @param toBeSwapped 要被替换的子节点。
     * @param newChild 用于替换旧节点的新节点。
     * @throws IllegalArgumentException 如果找不到要交换的子节点则抛出此异常。
     */
    @Throws(IllegalArgumentException::class)
    fun swapChildren(toBeSwapped: PcodePolynomialNode, newChild: PcodePolynomialNode) {
        children.mapIndexed { idx, it -> if (it.hashCode() == toBeSwapped.hashCode()) idx else -1 }
            .firstOrNull { it != -1 }
            ?.let { children[it] = newChild }
            ?: throw IllegalArgumentException("Child not found")
    }

    /**
     * 递归地用新节点替换叶子节点的所有出现。
     *
     * @param leaf 要被替换的叶子节点。
     * @param newNode 用于替换叶子节点的新节点。
     * @throws IllegalArgumentException 如果要替换的节点不是叶子节点则抛出此异常。
     */
    fun replace(leaf: PcodePolynomialNode, newNode: PcodePolynomialNode) {
        require(!leaf.isOp) { "Only leaf node can be replaced recursively" }
        children.forEachIndexed { idx, node ->
            if (node.isOp)
                node.replace(leaf, newNode)
            else if (node == leaf)
                children[idx] = newNode
        }
    }


    /**
     * 返回以此节点为根的多项式表达式的漂亮字符串表示。
     *
     * @return 格式化后的表达式字符串。
     */
    override fun toString(): String {
        return toString(this)
    }

    /**
     * 返回带有程序上下文的多项式表达式的漂亮字符串表示。
     *
     * @param program Ghidra 程序对象，为 varnode 解析提供额外上下文。
     * @return 带有程序特定信息的格式化表达式字符串。
     */
    fun toString(program: Program): String {
        return toString(this, program)
    }

    /**
     * 将节点及其子节点转换为漂亮的字符串表示。
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
     * @throws IllegalStateException 如果树结构无效（例如，缺少操作符或节点）则抛出此异常。
     */
    @Throws(IllegalStateException::class)
    private fun toString(root: PcodePolynomialNode, program: Program? = null): String {
        if (!root.isOp) {
            check(root.node != null) { "Broken polynomial tree" }
            return root.node!!.toPrettyString(program)
        } else {
            check(root.op != null) { "Broken polynomial tree" }
            return when (root.op!!.opcode) {
                in UNARY_OPCODES -> {
                    check(root.children.size == 1) { "Unary op ${root.op!!} should have 1 child" }
                    "(${OPCODE_SYMBOLS[root.op!!.opcode]!!} ${root.children[0]})"
                }
                in BINARY_OPCODES -> {
                    check(root.children.size == 2) { "Binary op ${root.op!!} should have 2 children" }
                    "(${root.children[0]} ${OPCODE_SYMBOLS[root.op!!.opcode]!!} ${root.children[1]})"
                }
                in FUNC_UNARY_OPCODES -> {
                    check(root.children.size == 1) { "Unary op ${root.op!!} should have 1 child" }
                    "${OPCODE_SYMBOLS[root.op!!.opcode]!!}(${root.children[0]})"
                }
                in FUNC_BINARY_OPCODES -> {
                    check(root.children.size == 2) { "Binary op ${root.op!!} should have 2 children" }
                    "${OPCODE_SYMBOLS[root.op!!.opcode]!!}(${root.children[0]}, ${root.children[1]})"
                }
                in FUNC_UNARY_OPCODES_WITH_SIZE -> {
                    check(root.children.size == 1) { "Unary op ${root.op!!} should have 1 child" }
                    "${OPCODE_SYMBOLS[root.op!!.opcode]!!}(${root.children[0]}, ${root.op!!.output.size})"
                }
                else -> throw IllegalStateException("Unknown op ${root.op!!}")
            }
        }
    }

    /**
     * 检查两个多项式节点是否相等。
     * 如果两个节点表示相同的操作或 varnode，则它们相等。
     *
     * @param other 与此节点比较的对象。
     * @return 如果节点相等返回 true，否则返回 false。
     * @throws IllegalStateException 如果任一节点具有无效结构则抛出此异常。
     */
    @Throws(IllegalStateException::class)
    override fun equals(other: Any?): Boolean {
        return when {
            other == null -> false
            other !is PcodePolynomialNode -> false
            this.isOp != other.isOp -> false
            this.isOp && this.op == null -> throw IllegalArgumentException("Invalid node")
            this.isOp && other.isOp -> this.op == other.op
            // Below: !this.isOp && !other.isOp
            this.node == null -> throw IllegalArgumentException("Invalid node")
            else -> this.node == other.node
        }
    }
}