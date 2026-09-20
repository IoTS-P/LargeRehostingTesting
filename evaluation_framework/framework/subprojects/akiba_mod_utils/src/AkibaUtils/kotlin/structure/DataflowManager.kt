package org.iotsplab.akiba.structure

import ghidra.program.model.address.Address
import ghidra.program.model.lang.Register
import ghidra.program.model.pcode.Varnode
import org.iotsplab.akiba.utils.memory.MemoryFlowSegments
import org.iotsplab.akiba.utils.memory.MemoryDebris

/**
 * 通过跟踪寄存器到内存的关系和数据流路径来管理数据流分析。
 *
 * @property registersWithMemoryData 存储寄存器到其对应内存 varnodes 映射的可变映射表。
 * @property dataFlowRecorder 记录表示数据流关系的 varnode 对（源 -> 目标）的可变列表。
 * @property zeroStoreRecorder 记录被存储零值的 varnode 的可变列表。
 */
class DataflowManager(
    val registersWithMemoryData: MutableMap<Register, Varnode> = mutableMapOf(),
    val dataFlowRecorder: MutableList<Pair<Varnode, Varnode>> = mutableListOf(),
    val zeroStoreRecorder: MutableList<Varnode> = mutableListOf(),
)  {
    /**
     * 指示当前指令是否为跳转指令。
     */
    var willJump: Boolean = false
    
    /**
     * 跳转指令的目标地址（如果适用）。
     */
    var jumpTarget: Address? = null

    /**
     * 将记录的数据流组装成统一的流映射。
     *
     * @return 表示内存碎片流关系的映射，展示数据如何在内存位置之间移动。
     */
    fun assembleFlow(): Map<MemoryDebris, MemoryDebris> {
        val ret = MemoryFlowSegments()
        dataFlowRecorder.forEach {
            ret.add(MemoryDebris.fromVarnode(it.first), MemoryDebris.fromVarnode(it.second))
        }
        return ret.getFlowMap()
    }

    /**
     * 将所有记录的零存储操作组装成一个合并的列表。
     *
     * @return 已被存储零值的内存碎片段列表。
     */
    fun assembleZeroStore(): List<MemoryDebris> {
        return MemoryDebris.assembleMultipleSegments(zeroStoreRecorder.map { MemoryDebris.fromVarnode(it) })
    }

    /**
     * 清除所有记录的数据，包括寄存器映射、数据流和零存储记录。
     */
    fun clear() {
        registersWithMemoryData.clear()
        dataFlowRecorder.clear()
        zeroStoreRecorder.clear()
    }
}
