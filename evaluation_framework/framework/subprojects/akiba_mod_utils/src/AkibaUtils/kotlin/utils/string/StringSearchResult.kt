package org.iotsplab.akiba.utils.string

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.abstractFinder.SearchResult

/**
 * 字符串搜索结果类。
 * 表示在内存中搜索到的字符串及其位置信息。
 *
 * @param value 搜索到的字符串值。
 * @param address 字符串在内存中的起始地址。
 */
class StringSearchResult(value: String, val address: Address)
    : SearchResult<String>(value), Comparable<StringSearchResult> {
    /**
     * 字符串长度。
     * @return 字符串的字符数。
     */
    val size: Int
        get() = value.length

    /**
     * 按地址比较两个搜索结果。
     *
     * @param other 另一个字符串搜索结果。
     * @return 地址比较结果。
     */
    override fun compareTo(other: StringSearchResult): Int {
        return address.compareTo(other.address)
    }

    /**
     * 判断是否等于另一个对象。
     *
     * @param other 要比较的对象。
     * @return 如果相等则返回 true，否则返回 false。
     */
    override fun equals(other: Any?): Boolean {
        return super.equals(other) && address == (other as StringSearchResult).address
    }

    /**
     * 转换为字符串表示。
     *
     * @return 格式化的字符串描述。
     */
    override fun toString(): String {
        return "$address, size $size: $value"
    }

    /**
     * 计算哈希码。
     *
     * @return 对象的哈希值。
     */
    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + address.hashCode()
        result = 31 * result + size
        return result
    }

    companion object {
        /**
         * 移除重叠的匹配结果。
         * 用于选择主要匹配项并丢弃重叠的匹配。
         * 例如：在 0x0~0x10、0x9~0x15、0x12~0x20 处有 3 个匹配，则第二个将被丢弃。
         *
         * @param results 搜索结果列表。
         * @return 处理后的结果列表，不包含重叠项。
         */
        fun removeOverlapMatches(results: List<StringSearchResult>) : List<StringSearchResult> {
            // 首先对结果排序
            val sortedResults: MutableList<StringSearchResult> = results.sorted().toMutableList()
            val handledResults: MutableList<StringSearchResult> = mutableListOf()

            sortedResults.forEachIndexed {
                index, result -> run {
                    if (index == 0)
                        return@forEachIndexed
                    val lastUpperBound = sortedResults[index - 1].address.offset + sortedResults[index - 1].size
                    if (lastUpperBound <= result.address.offset)
                        handledResults.add(result)
                }
            }
            return handledResults
        }

        /**
         * 移除冲突的匹配结果。
         * 用于删除与代码、其他数据等冲突的匹配。
         * 将移除所有未被 "undefined" 数据填充的匹配（包括 null，没有数据的代码）。
         *
         * @param program Ghidra 程序对象。
         * @param results 搜索结果列表。
         * @return 处理后的结果列表，不包含冲突项。
         */
        fun removeConflictMatches(program: Program, results: List<StringSearchResult>) : List<StringSearchResult> {
            val handledResults: MutableList<StringSearchResult> = mutableListOf()
            results.forEach {
                r -> run {
                    for (off in 0..<r.size) {
                        val addr: Address = r.address.add(off.toLong())
                        if (program.listing.getDataContaining(addr)?.dataType.toString() != "undefined"
                            || program.listing.getDataContaining(addr) == null)
                            return@run
                    }
                    handledResults.add(r)
                }
            }
            return handledResults
        }
    }
}