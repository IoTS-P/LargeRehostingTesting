package org.iotsplab.akiba.utils.string

import ghidra.features.base.memsearch.searcher.MemoryMatch
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.string.StringSearchResult
import org.iotsplab.akiba.utils.memory.MemoryUtil.Companion.readProgramBytesToUTF8String

/**
 * ASCII 序列搜索器。
 * 用于在内存中搜索 ASCII 字符串序列，支持长度过滤和结果清理。
 *
 * @param program Ghidra 程序对象。
 * @param minLength 最小搜索长度，默认为 DEFAULT_ASCII_SEARCH_MIN_LENGTH。
 * @param maxLength 最大搜索长度，默认为 DEFAULT_ASCII_SEARCH_MAX_LENGTH。
 * @param postMapHandler 后处理函数，用于清理搜索结果。
 */
class AsciiSequenceSearcher(program: Program,
                            minLength: Int = DEFAULT_ASCII_SEARCH_MIN_LENGTH,
                            maxLength: Int = DEFAULT_ASCII_SEARCH_MAX_LENGTH,
                            postMapHandler: (MemoryMatch) -> StringSearchResult? = {
                                r -> stripResult(program, r)
                            })
    : RegexSearcher(program, asciiRegexOfLength(minLength, maxLength), postMapHandler = postMapHandler) {

    companion object {
        /**
         * 清理搜索结果，移除前后的零字节。
         *
         * @param program Ghidra 程序对象。
         * @param r 内存匹配结果。
         * @return 清理后的字符串搜索结果，如果结果为空则返回 null。
         */
        @JvmStatic
        fun stripResult(program: Program, r: MemoryMatch): StringSearchResult? {
            var start = r.address
            var end = r.address.add(r.length.toLong())
            var realSize = r.length
            while (start < end) {
                if (program.memory.getByte(start) != 0.toByte())
                    break
                start = start.add(1)
                realSize -= 1
            }

            if (start == end) return null

            while (program.memory.getByte(end.subtract(1)) == 0.toByte()) {
                end = end.subtract(1)
                realSize -= 1
            }

            return StringSearchResult(readProgramBytesToUTF8String(program.memory, start, realSize), start)
        }
    }
}