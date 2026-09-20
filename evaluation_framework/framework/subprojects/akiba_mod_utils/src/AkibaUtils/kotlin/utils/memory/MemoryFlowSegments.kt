package org.iotsplab.akiba.utils.memory

import ghidra.program.model.address.AddressSpace

/**
 * 内存流段管理器。
 * 用于管理和合并连续的内存数据流段，支持源地址到目标地址的映射关系。
 */
class MemoryFlowSegments {
    private val flowMap: MutableMap<AddressSpace, MutableMap<MemoryDebris, MemoryDebris>> = mutableMapOf()

    /**
     * 添加内存流段。
     * 将源地址到目标地址的映射关系添加到流图中，并自动合并相邻的连续段。
     *
     * @param src 源内存碎片。
     * @param dst 目标内存碎片。
     * @throws IllegalArgumentException 如果源和目标大小不一致。
     */
    @Throws(IllegalArgumentException::class)
    fun add(src: MemoryDebris, dst: MemoryDebris) {
        var newSrc: MemoryDebris = src
        var newDst: MemoryDebris = dst
        require(src.size == dst.size) { "src and dst must have the same size" }
        flowMap.putIfAbsent(src.address.addressSpace, mutableMapOf())

        val previousSegment = flowMap[src.address.addressSpace]?.entries?.find { (k, v) ->
            k.address.offset + k.size == src.address.offset && v.address.offset + v.size == dst.address.offset
        }
        previousSegment ?.let { (k, v) ->
            flowMap[src.address.addressSpace]?.remove(k)
            newSrc = MemoryDebris(k.address, k.size + dst.size)
            newDst = MemoryDebris(v.address, v.size + dst.size)
        }

        val nextSegment = flowMap[src.address.addressSpace]?.entries?.find { (kk, vv) ->
            kk.address.offset == newSrc.address.offset + newSrc.size &&
            vv.address.offset == newDst.address.offset + newDst.size
        }
        nextSegment ?.let { (kk, _) ->
            flowMap[src.address.addressSpace]?.remove(kk)
            newSrc.size += kk.size
            newDst.size += kk.size
        }

        flowMap[src.address.addressSpace]!![newSrc] = newDst
    }

    /**
     * 获取内存流映射表。
     *
     * @return 包含所有地址空间中的源到目标内存碎片映射的只读视图。
     */
    fun getFlowMap(): Map<MemoryDebris, MemoryDebris> {
        return flowMap.values.flatMap { it.entries }.associate { (k, v) -> k to v }
    }
}