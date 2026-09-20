package org.iotsplab.akiba.utils.reference

import ghidra.graph.GDirectedGraph
import ghidra.graph.GEdge
import ghidra.graph.GraphFactory
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.function.allInstructions
import java.io.Closeable
import java.lang.IllegalStateException

/**
 * 函数调用引用图。
 * 构建和管理程序中函数之间的调用关系图，用于分析调用链和识别特殊函数。
 *
 * @param program Ghidra 程序对象。
 */
class FuncCallXrefGraph (
    private val program: Program
): Closeable {
    // 所有顶点都是函数的入口点地址。
    // 函数不稳定且可能随时变化，为避免不稳定性，使用地址而不是函数对象。
    class FuncCallXrefEdge(private val start: Address, private val end: Address): GEdge<Address> {
        override fun getStart(): Address { return start }
        override fun getEnd(): Address { return end }
    }
    private val innerGraph: GDirectedGraph<Address, FuncCallXrefEdge> = GraphFactory.createDirectedGraph()

    /**
     * 获取没有调用者的函数。
     *
     * @return 没有任何入边的函数列表（即没有被其他函数调用的函数）。
     * @throws ConcurrentModificationException 如果程序函数列表在图使用期间发生变化。
     */
    @Throws(ConcurrentModificationException::class)
    fun getFunctionsOfNoCaller(): List<Function> {
        // 我们不允许在使用图时函数发生变化
        try {
            return innerGraph.vertices.filter { v -> innerGraph.getPredecessors(v).isEmpty() } .map { v ->
                program.listing.getFunctionAt(v)!!
            }
        } catch (_: NullPointerException) {
            throw ConcurrentModificationException("Program function list changed, " +
                    "do not change functions while using FuncCallXrefGraph. " +
                    "Regenerate a graph if you want to update functions.")
        }
    }

    /**
     * 获取叶子函数（不调用其他函数的函数）。
     *
     * @return 没有任何出边的函数列表（即不调用其他函数的函数）。
     */
    fun getLeafFunctions(): List<Function> {
        try {
            return innerGraph.vertices.filter { v -> innerGraph.getSuccessors(v).isEmpty() } .map { v ->
                program.listing.getFunctionAt(v)!!
            }
        } catch (_: NullPointerException) {
            throw ConcurrentModificationException("Program function list changed, " +
                    "do not change functions while using FuncCallXrefGraph. " +
                    "Regenerate a graph if you want to update functions.")
        }
    }

    /**
     * 重建图。
     * 从程序中生成图，可用于初始化和更新。
     *
     * 将生成函数之间调用关系的图。
     * 特别注意：如果一个函数没有返回值且最后一条指令是跳转而不是调用，它将被视为调用（仅当跳转到另一个函数时）。
     *
     * @param includeRecursive 是否包含递归调用。
     * @param extraEdges 要添加到图中的额外边。如果通过间接调用分析等方式发现 Ghidra 未找到的调用，可以添加到这里。
     */
    @Throws(ConcurrentModificationException::class)
    fun rebuild(includeRecursive: Boolean, extraEdges: List<FuncCallXrefEdge> = listOf()) {
        val functions = program.listing.getFunctions(true).toList()
        functions.map { it.entryPoint }.forEach { innerGraph.addVertex(it) }
        functions.forEach { f ->
            program.referenceManager.getReferencesTo(f.entryPoint).forEach { ref ->
                if (ReferenceConstants.REFERENCE_CALL.contains(ref.referenceType)) {
                    val fromFunc = program.listing.getFunctionContaining(ref.fromAddress) ?:
                        return@forEach
                    val toFunc = program.listing.getFunctionContaining(ref.toAddress) ?:
                        throw IllegalStateException("Broken xref")
                    // 递归调用不包含在内
                    if (fromFunc != toFunc || includeRecursive)
                        innerGraph.addEdge(FuncCallXrefEdge(fromFunc.entryPoint, ref.toAddress))
                } else if (ReferenceConstants.REFERENCE_JUMP.contains(ref.referenceType)) {
                    val fromFunc = program.listing.getFunctionContaining(ref.fromAddress)
                    if (fromFunc == null ||
                        ref.fromAddress != fromFunc.allInstructions().last().address)
                        return@forEach
                    if (fromFunc == f)
                        return@forEach
                    // 如果引用是跳转，只接受那些是函数最后一条指令且目标函数不是源函数的跳转
                    innerGraph.addEdge(FuncCallXrefEdge(fromFunc.entryPoint, ref.toAddress))
                }
            }
        }
        extraEdges.forEach { innerGraph.addEdge(it) }
    }

    /**
     * 关闭图并释放资源。
     */
    override fun close() {
        innerGraph.removeEdges(innerGraph.edges)
        innerGraph.removeVertices(innerGraph.vertices)
    }

    companion object {
        /**
         * 从 Ghidra 程序中创建函数调用引用图。
         *
         * @param program Ghidra 程序对象。
         * @param includeRecursive 是否包含递归调用。
         * @return 函数调用引用图对象。
         */
        fun fromProgram(program: Program, includeRecursive: Boolean = false): FuncCallXrefGraph {
            val graph = FuncCallXrefGraph(program)
            graph.rebuild(includeRecursive)
            return graph
        }
    }
}