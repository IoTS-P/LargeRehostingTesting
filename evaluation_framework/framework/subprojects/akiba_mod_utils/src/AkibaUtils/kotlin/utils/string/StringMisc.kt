package org.iotsplab.akiba.utils.string

import ghidra.program.model.data.StringDataType
import ghidra.program.model.listing.DataIterator
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.memory.MemoryUtil

/**
 * 获取程序中所有的字符串数据。
 * 遍历程序的数据列表，提取所有 StringDataType 类型的数据。
 *
 * @return 包含所有字符串及其地址的搜索结果列表。
 */
fun Program.allStrings(): List<StringSearchResult> {
    val ret = mutableListOf<StringSearchResult>()
    val di: DataIterator = listing.getData(true)
    for (data in di) {
        if (data.dataType !is StringDataType)
            continue
        ret.add(
            StringSearchResult(
                MemoryUtil.readProgramBytesToUTF8String(memory, data.address, data.length), data.address
            )
        )
    }
    return ret
}