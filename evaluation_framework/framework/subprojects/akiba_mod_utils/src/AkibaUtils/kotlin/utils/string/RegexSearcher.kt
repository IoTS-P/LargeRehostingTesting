package org.iotsplab.akiba.utils.string

import ghidra.features.base.memsearch.bytesource.AddressableByteSource
import ghidra.features.base.memsearch.bytesource.ProgramByteSource
import ghidra.features.base.memsearch.format.SearchFormat
import ghidra.features.base.memsearch.gui.SearchSettings
import ghidra.features.base.memsearch.matcher.RegExByteMatcher
import ghidra.features.base.memsearch.searcher.MemoryMatch
import ghidra.features.base.memsearch.searcher.MemorySearcher
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.listing.Program
import ghidra.util.datastruct.ListAccumulator
import org.iotsplab.akiba.utils.abstractFinder.AdvancedAbstractSearcher
import org.iotsplab.akiba.utils.memory.MemoryUtil.Companion.readProgramBytesToUTF8String
import java.util.regex.Pattern

/**
 * 正则表达式搜索器。
 * 使用正则表达式在内存中搜索匹配的字符串模式。
 *
 * @param program Ghidra 程序对象。
 * @param predicate 用于匹配的正则表达式模式。
 * @param clearOverlap 是否清除重叠的匹配结果。
 * @param clearConflict 是否清除冲突的匹配结果。
 * @param postMapHandler 后处理函数，用于转换搜索结果。
 */
open class RegexSearcher(protected val program: Program, private val predicate: Pattern,
                 private val clearOverlap: Boolean = true, private val clearConflict: Boolean = true,
                 private val postMapHandler: (MemoryMatch) -> StringSearchResult? = {
                     r ->
                     StringSearchResult(readProgramBytesToUTF8String(program.memory, r), r.address)
                 })
    : AdvancedAbstractSearcher<StringSearchResult>() {

    private val api: FlatProgramAPI = FlatProgramAPI(program)

    /**
     * 执行搜索。
     * 使用正则表达式在内存中查找所有匹配项，并应用后处理和清理。
     *
     * @return 搜索结果列表。
     * @throws Exception 如果搜索过程中发生错误。
     */
    @Throws(Exception::class)
    override fun search() : List<StringSearchResult> {
        // 我们接收 Pattern 对象，这样用户就无法传递无效的正则表达式字符串
        val byteSource: AddressableByteSource = ProgramByteSource(program)
        val searcher = MemorySearcher(byteSource, RegExByteMatcher(predicate.toString(),
            SearchSettings().withSearchFormat(SearchFormat.REG_EX)),
            program.memory.allInitializedAddressSet, REGEX_MAX_SEARCH_COUNT)
        val results = ListAccumulator<MemoryMatch>()
        searcher.findAll(results, api.monitor)
        val resultList = results.asList()

        var result = resultList.map { r -> postMapHandler(r) } .requireNoNulls()

        if (clearOverlap)
            result = StringSearchResult.removeOverlapMatches(result)

        if (clearConflict)
            result = StringSearchResult.removeConflictMatches(program, result)

        return result
    }

    companion object {
        /**
         * 正则表达式搜索的最大数量限制。
         */
        const val REGEX_MAX_SEARCH_COUNT = 10000
        
        /**
         * ASCII 搜索的默认最小长度。
         */
        const val DEFAULT_ASCII_SEARCH_MIN_LENGTH = 5
        
        /**
         * ASCII 搜索的默认最大长度。
         */
        const val DEFAULT_ASCII_SEARCH_MAX_LENGTH = 10000

        /**
         * UNIX 颜色 ASCII 码正则表达式模式。
         * 匹配 ANSI 转义序列中的颜色控制码。
         */
        @JvmStatic
        val UNIX_COLOR_ASCII_REGEX: Pattern =
            Pattern.compile("\u001b\\[([0-9]+;)*([0-9]+)m([\\x20-\\x7e]|\\t|\\n|\\r|(\u001b\\[([0-9]+;)*([0-9]+)m))*")

        /**
         * 默认的 ASCII 正则表达式模式。Ghidra 内存搜索允许 \x00，所以我们可以直接使用正则表达式识别字符串，默认长度大于 5
         */
        @JvmStatic
        val DEFAULT_ASCII_REGEX: Pattern = Pattern.compile("\\x00([\\x20-\\x7e]|\\t|\\n|\\r){5,}\\x00")

        /**
         * 创建指定最小长度的 ASCII 正则表达式。
         *
         * @param noShorterThan 字符串最小长度。
         * @return 编译后的正则表达式模式。
         */
        @JvmStatic
        fun asciiRegexOfLength(noShorterThan: Int) : Pattern {
            val re: String = "\\x00([\\x20-\\x7e]|\\t|\\n|\\r){$noShorterThan,}\\x00"
            return Pattern.compile(re)
        }

        /**
         * 创建指定长度范围的 ASCII 正则表达式。
         *
         * @param noShorterThan 字符串最小长度。
         * @param shorterThan 字符串最大长度。
         * @return 编译后的正则表达式模式。
         */
        @JvmStatic
        fun asciiRegexOfLength(noShorterThan: Int, shorterThan: Int) : Pattern {
            val re: String = "\\x00([\\x20-\\x7e]|\\t|\\n|\\r){$noShorterThan,$shorterThan}\\x00"
            return Pattern.compile(re)
        }
    }
}