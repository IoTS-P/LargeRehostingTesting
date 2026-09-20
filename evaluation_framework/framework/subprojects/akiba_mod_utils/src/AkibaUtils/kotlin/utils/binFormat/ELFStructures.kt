package org.iotsplab.akiba.utils.binFormat

import ghidra.app.util.bin.BinaryReader
import ghidra.app.util.bin.ByteArrayProvider
import ghidra.app.util.bin.format.elf.ElfHeader
import ghidra.app.util.bin.format.elf.ElfProgramHeader
import ghidra.app.util.bin.format.elf.ElfProgramHeaderConstants
import ghidra.app.util.bin.format.elf.ElfSectionHeader
import ghidra.app.util.bin.format.elf.ElfSectionHeaderConstants
import ghidra.app.util.bin.format.elf.ElfSectionHeaderType
import ghidra.features.base.memsearch.bytesource.AddressableByteSource
import ghidra.features.base.memsearch.bytesource.ProgramByteSource
import ghidra.features.base.memsearch.format.SearchFormat
import ghidra.features.base.memsearch.gui.SearchSettings
import ghidra.features.base.memsearch.matcher.RegExByteMatcher
import ghidra.features.base.memsearch.searcher.MemoryMatch
import ghidra.features.base.memsearch.searcher.MemorySearcher
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import ghidra.util.datastruct.ListAccumulator
import ghidra.util.task.TaskMonitor
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.utils.memory.MemoryUtil
import org.iotsplab.akiba.utils.string.RegexSearcher.Companion.REGEX_MAX_SEARCH_COUNT
import java.nio.charset.StandardCharsets

/**
 * ELF 文件格式结构解析类。
 * 用于解析和访问 ELF 文件的头部信息、程序头表和节头表。
 *
 * @param program Ghidra 程序对象。
 * @param logger 可选的日志记录器，用于记录解析过程中的错误。
 */
class ELFStructures (
    private val program: Program,
    private val logger: Logger? = null
) {
    /**
     * ELF 文件头。
     * 包含 ELF 文件的基本信息和元数据。
     */
    val elfHeader: ElfHeader
    
    /**
     * ELF 程序头表列表。
     * 描述进程映像的各个段，用于加载和执行。
     */
    val elfProgramHeaders: List<ElfProgramHeader>
    
    /**
     * ELF 节头表映射。
     * 键为节区名称，值为节头对象，描述各个节区的信息。
     */
    val elfSectionHeaders: Map<String, ElfSectionHeader>

    init {
        if (!program.memory.blocks.map { it.name }.containsAll(
            listOf("_elfHeader", "_elfProgramHeaders", "_elfSectionHeaders", ".shstrtab")))
            throw IllegalArgumentException("Program doesn't contains an ELF file")

        // 获取 ELF 头
        val hBlock = program.memory.getBlock("_elfHeader")
        val hbContents = ByteArray(hBlock.size.toInt())
        hBlock.getBytes(hBlock.start, hbContents)
        val elfHeaderBp = ByteArrayProvider(hbContents)
        elfHeader = ElfHeader(elfHeaderBp) { err -> logger?.error(err) }

        // 获取 ELF 程序头表
        val phBlock = program.memory.getBlock("_elfProgramHeaders")
        val phbContents = ByteArray(ELF_PROGRAM_HEADER_ENTRY_SIZE)
        val programHeaders = mutableListOf<ElfProgramHeader>()

        (0..<phBlock.size / ELF_PROGRAM_HEADER_ENTRY_SIZE) .forEach { idx ->
            phBlock.getBytes(phBlock.start.add(idx * ELF_PROGRAM_HEADER_ENTRY_SIZE), phbContents)
            val elfProgramHeaderBp = ByteArrayProvider(phbContents)
            val phBinaryReader = BinaryReader(elfProgramHeaderBp, elfHeader.isLittleEndian)
            programHeaders.add(ElfProgramHeader(phBinaryReader, elfHeader))
        }
        elfProgramHeaders = programHeaders

        // 获取 ELF 节头表
        val shBlock = program.memory.getBlock("_elfSectionHeaders")
        val shbContents = ByteArray(ELF_SECTION_HEADER_ENTRY_SIZE)
        val sectionHeaders = mutableListOf<ElfSectionHeader>()
        val shstrtab = program.memory.getBlock(".shstrtab")
        val shstrtabContents = ByteArray(shBlock.size.toInt())
        shstrtab.getBytes(shstrtab.start, shstrtabContents)
        val sectionHeaderNames = mutableListOf<String>()

        (0..<shBlock.size / ELF_SECTION_HEADER_ENTRY_SIZE) .forEach { idx ->
            shBlock.getBytes(shBlock.start.add(idx * ELF_SECTION_HEADER_ENTRY_SIZE), shbContents)
            val elfSectionHeaderBp = ByteArrayProvider(shbContents)
            val shBinaryReader = BinaryReader(elfSectionHeaderBp, elfHeader.isLittleEndian)
            val sh = ElfSectionHeader(shBinaryReader, elfHeader)
            sectionHeaders.add(sh)
            sectionHeaderNames.add(
                if (sh.type == ElfSectionHeaderType.SHT_NULL.value) ""
                else {
                    val length = (0..<shstrtabContents.size).filter {
                        shstrtabContents[it] == 0.toByte() && it > sh.name}.min() - sh.name
                    String(shstrtabContents, sh.name, length)
                }
            )
        }
        elfSectionHeaders = sectionHeaderNames.zip(sectionHeaders).toMap()
    }

    /**
     * 获取节区所属的程序头。
     * 查找包含指定节区的程序头表项。
     *
     * @param section 要查询的 ELF 节头。
     * @return 包含该节区的程序头，如果不存在则返回 null。
     */
    fun getBelongedProgram(section: ElfSectionHeader): ElfProgramHeader? {
        return elfProgramHeaders.firstOrNull { phdr ->
            phdr.virtualAddress <= section.address && section.address < phdr.virtualAddress + phdr.memorySize
        }
    }

    /**
     * 判断节区是否为调试节区。
     * 调试节区不属于任何程序头，仅用于调试信息。
     *
     * @param section 要检查的 ELF 节头。
     * @return 如果是调试节区则返回 true，否则返回 false。
     */
    fun isDebugSection(section: ElfSectionHeader): Boolean {
        return elfProgramHeaders.none { phdr ->
            phdr.virtualAddress <= section.address && section.address < phdr.virtualAddress + phdr.memorySize
        }
    }

    /**
     * 获取节区的实际权限。
     * 根据节区所属的程序头和节区标志计算实际权限，并修正写权限和执行权限。
     *
     * @param section 要查询的 ELF 节头。
     * @return 节区的实际权限值（读、写、执行的组合）。
     * @throws IllegalStateException 如果节区不属于任何程序头。
     */
    fun getRealSectionPermission(section: ElfSectionHeader): Int {
        val phdrEntry = getBelongedProgram(section)!!
        var perm = phdrEntry.flags and ELF_PHDR_PF_ALL
        // 修正写权限
        if ((section.flags and ElfSectionHeaderConstants.SHF_WRITE.toLong()) == 0L)
            perm = perm and (ElfProgramHeaderConstants.PF_X or ElfProgramHeaderConstants.PF_R)
        // 修正执行权限
        if ((section.flags and ElfSectionHeaderConstants.SHF_EXECINSTR.toLong()) == 0L)
            perm = perm and (ElfProgramHeaderConstants.PF_R or ElfProgramHeaderConstants.PF_W)
        return perm
    }

    /**
     * 获取 ARM Cortex-M 固件文件中断向量表的首地址。
     * 通过在需要加载的段中搜索入口地址值来判断中断向量表的位置，因为中断向量表的第 4-7 字节即应为固件入口地址。
     *
     * @return 寻找到的中断向量表首地址
     * @throws IllegalStateException 没有在需要加载的段中找到入口地址，或找到多个入口地址
     */
    @Throws(IllegalStateException::class)
    fun getIvtTableStart(): Address {
        val entryPoint = program.addressFactory.defaultAddressSpace.getAddress(elfHeader.e_entry())
        val byteSource: AddressableByteSource = ProgramByteSource(program)

        val target = String(MemoryUtil.numberToBytes(entryPoint.offset).sliceArray(0..3),
            StandardCharsets.ISO_8859_1)
            .replace("\\", "\\\\")
            .replace("(", "\\(").replace(")", "\\)")
            .replace("[", "\\[").replace("]", "\\]")
            .replace("{", "\\{").replace("}", "\\}")
            .replace("+", "\\+")
            .replace("*", "\\*")
            .replace("?", "\\?")
            .replace(".", "\\.")
        val searcher = MemorySearcher(byteSource,
            RegExByteMatcher(
                target, SearchSettings().withSearchFormat(SearchFormat.BINARY)),
            program.memory.allInitializedAddressSet, REGEX_MAX_SEARCH_COUNT)
        val results = ListAccumulator<MemoryMatch>()
        searcher.findAll(results, TaskMonitor.DUMMY)
        val resultList = results.asList().filter {
            it.address.addressSpace == program.addressFactory.defaultAddressSpace }
        when (resultList.size) {
            0 -> throw IllegalStateException("Nowhere found to refer entry point")
            1 -> return resultList[0].address.subtract(4)
            // TODO: How to choose from multiple matches?
            else -> throw IllegalStateException("Multiple reference found to entry point")
        }
    }

    companion object {
        /**
         * ELF 程序头表项大小
         */
        const val ELF_PROGRAM_HEADER_ENTRY_SIZE = 0x20

        /**
         * ELF 节头表项大小
         */
        const val ELF_SECTION_HEADER_ENTRY_SIZE = 0x28

        /**
         * ELF 段权限位掩码
         */
        const val ELF_PHDR_PF_ALL = 7

        /**
         * 将 ELF 段的权限转换为字符串。
         *
         * @param perm ELF 段权限整数值
         * @return ELF 段权限字符串
         * @throws IllegalStateException 没有在需要加载的段中找到入口地址，或找到多个入口地址
         */
        fun getPermissionString(perm: Int): String {
            var ret = ""
            ret += if (perm and ElfProgramHeaderConstants.PF_R != 0) "r" else "-"
            ret += if (perm and ElfProgramHeaderConstants.PF_W != 0) "w" else "-"
            ret += if (perm and ElfProgramHeaderConstants.PF_X != 0) "x" else "-"
            return ret
        }
    }
}
