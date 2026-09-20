package org.iotsplab.akiba.utils.listing

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Listing
import ghidra.util.task.TaskMonitor

/**
 * 获取指定地址之后的未定义数据区域。
 * 从给定地址开始查找连续的未定义数据，返回起始地址和长度。
 *
 * @param addr 开始搜索的地址。
 * @return 包含起始地址和长度的对，如果没有找到则返回 null。
 */
fun Listing.getUndefinedDataRegionAfter(addr: Address): Pair<Address, Long>? {
    val start = this.getUndefinedDataAfter(addr, TaskMonitor.DUMMY)?.address ?: return null

    var ptr = start
    while(true) {
        val nextUndef = this.getUndefinedDataAfter(ptr, TaskMonitor.DUMMY)?.address
        if (nextUndef == null || nextUndef.subtract(start) == 1L)
            return start to (ptr.subtract(start) + 1)
        ptr = nextUndef
    }
}