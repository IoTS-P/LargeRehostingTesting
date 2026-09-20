package org.iotsplab.akiba.utils.memory

import ghidra.program.model.address.Address
import ghidra.program.model.address.Address.max
import ghidra.program.model.address.Address.min
import ghidra.program.model.pcode.Varnode

/**
 * 内存碎片类。
 * 表示一段连续的内存区域，用于跟踪数据流和内存访问。
 *
 * @param address 内存区域的起始地址。
 * @param size 内存区域的大小（字节）。
 */
open class MemoryDebris (
    var address: Address,
    var size: Int,
) {
    companion object {
        /**
         * 从 Varnode 创建内存碎片。
         *
         * @param varnode Ghidra Varnode 对象。
         * @return 对应的 MemoryDebris 对象。
         */
        fun fromVarnode(varnode: Varnode): MemoryDebris {
            return MemoryDebris(varnode.address, varnode.size)
        }

        /**
         * 组装多个内存段。
         * 合并重叠或相邻的内存段，返回不重叠的内存段列表。
         *
         * @param segments 要组装的内存段列表。
         * @return 合并后的内存段列表。
         */
        fun assembleMultipleSegments(segments: List<MemoryDebris>): List<MemoryDebris> {
            val ret = mutableListOf<MemoryDebris>()
            segments.forEach { seg ->
                ret.filter { 
                    // 可能存在一些重叠的内存段
                    !(it.address.add(it.size.toLong()) < seg.address || it.address > seg.address.add(seg.size.toLong()))
                } .let { segments ->
                    if (segments.isEmpty()) {
                        ret.add(seg)
                        return@let
                    }
                    val startMost = min(segments.minByOrNull { it.address }!!.address, seg.address)
                    val endMost = max(segments.sortedBy { it.address.add(it.size.toLong()) }.last().address,
                                        seg.address.add(seg.size.toLong()))
                    segments.forEach { ret.remove(it) }
                    ret.add(MemoryDebris(startMost, endMost.subtract(startMost).toInt()))
                }
            }

            return ret
        }
    }
}