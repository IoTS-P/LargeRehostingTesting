package org.iotsplab.akiba.process

import ghidra.app.util.bin.format.elf.ElfProgramHeader
import ghidra.app.util.bin.format.elf.ElfProgramHeaderType
import ghidra.program.model.listing.Program
import ghidra.util.task.TaskMonitor
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.fuzzware.CoverageInfo
import org.iotsplab.akiba.utils.DataProducer
import org.iotsplab.akiba.utils.DoNotCreateTable
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.TaskInterface
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.binFormat.ELFStructures
import org.iotsplab.akiba.utils.binFormat.ELFStructures.Companion.ELF_PHDR_PF_ALL
import org.iotsplab.akiba.utils.function.allFunctionStarts
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.notExists
import kotlin.io.path.writeText

/**
 * FuzzwareGateway: Prerequisites for fuzzware processes
 * FuzzwareGateway will generate configs for fuzzware to run smoothly, and offers some useful functions
 * that may be needed during fuzzware processes.
 *
 * [!] NOTE: If your binary root is stored in NTFS drives, any runs of fuzzware is not recommended, because
 *           NTFS doesn't support file names that contain ":", which is used in fuzzware input files. It will
 *           cause fails of fuzzware initialization.
 */
@DataProducer<FuzzwareGatewayConfig>("fuzzware_basic_conf")
@WithConfigClass(FuzzwareGatewayConfig::class)
@DoNotCreateTable
@IgnoreRuntimeTimeout
class FuzzwareGateway (
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    configPath = configPath,
    defaultConfig = FuzzwareGatewayConfig(),
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    val conf: FuzzwareGatewayConfig
        get() = config as FuzzwareGatewayConfig
    private val rootDir = Path.of(mainConf.binariesRoot, conf.projectRoot, id.toString())
    var rebased: Boolean = false

    override suspend fun startProcess() {
        setTaskData("fuzzware_basic_conf", conf)

        if (rootDir.notExists())
            rootDir.toFile().mkdirs()
    }

    @TaskInterface
    fun activateEnv(): Boolean {
        val process = ProcessBuilder(*conf.cmdPrefix.toTypedArray(), "${conf.cmdPredo} && type workon")
            .redirectErrorStream(true).start()
        process.waitFor()
        process.inputStream.bufferedReader().readText().let {
            if (it.contains("not found")) {
                logger.error("Command 'workon' not found, try to run `source /path/to/virtualenvwrapper.sh`")
                return false
            }
        }

        val process2 = ProcessBuilder(*conf.cmdPrefix.toTypedArray(), "${conf.cmdPredo} && workon ${conf.venv}")
            .redirectErrorStream(true).start()
        process2.waitFor()
        process2.inputStream.bufferedReader().readText().let {
            if (it.contains("does not exist")) {
                logger.error("Venv ${conf.venv} does not exist, your fuzzware may not be installed. Install first")
                return false
            }
        }

        val process3 = ProcessBuilder(
            *conf.cmdPrefix.toTypedArray(), "${conf.cmdPredo} && workon ${conf.venv} && type fuzzware")
            .redirectErrorStream(true).start()
        process3.waitFor()
        process3.inputStream.bufferedReader().readText().let {
            if (it.contains("not found")) {
                logger.error("Command 'fuzzware' not found, your fuzzware may not be installed. Install first")
                return false
            }
        }

        val process4 = ProcessBuilder(
            *conf.cmdPrefix.toTypedArray(), "${conf.cmdPredo} && type arm-none-eabi-objcopy"
        ).redirectErrorStream(true).start()
        process4.waitFor()
        process4.inputStream.bufferedReader().readText().let {
            if (it.contains("not found")) {
                logger.error("Command 'arm-none-eabi-objcopy' not found, install first.")
                return false
            }
        }

        return true
    }

    /**
     * Generate fuzzware config
     * NOTE: The base address here is based on ORIGINAL FILES (MAY HAVE LARGE \x00 SEGMENTS), NOT TRIMMED!
     */
    @Throws(IllegalStateException::class)
    @TaskInterface
    suspend fun generateFuzzwareConfig(
        baseAddress: Long,
        ivtStart: Long,
        forHoedur: Boolean,
        taskMonitor: TaskMonitor
    ): Path {
        logger.debug("Generating Fuzzware config: Base address = " +
                "${baseAddress.toString(16)}, IVT start = ${ivtStart.toString(16)}")

        val configPath = rootDir.resolve("config.yml")

        if (configPath.isRegularFile() && !conf.regenerateConfig)
            return configPath
        else
            configPath.writeText("")    // Clear previous config that may exist

        if (conf.doRebase && rebased) {
            prog.memory.blocks.forEach {
                logger.debug("Apply base address to block [{} - {}]", it.start, it.end)
                // Because the base address here is calculated on original files,
                // we need to add block addresses and  base addresses, rather than just substitute them
                prog.memory.moveBlock(it, it.start.add(baseAddress), taskMonitor)
            }
            rebased = true
        } else if (rebased) {
            throw IllegalStateException(
                "FuzzwareGateway: Rebase multiple times is not allowed, which may get wrong basic block addresses")
        }

        val allFunctions: Map<Long, String> = prog.allFunctionStarts().filter {
            prog.listing.getFunctionAt(it) != null
        }.associate { addr ->
            addr.offset to prog.listing.getFunctionAt(addr).name
        }
        if (allFunctions.isEmpty()) {
            logger.error("We cannot emulate a file that cannot get any function, not a valid firmware bin?")
            throw IllegalStateException()
        }

        logger.info("Generating fuzzware config...")

        // Directly use original binary file (sometimes trimmed)
        val originalPath = Path.of(getMetadata().originalPath)
        originalPath.copyTo(rootDir.resolve(originalPath.fileName), overwrite = true)
        configPath.writeText(String.format(
            configTemplate,
            baseAddress,
            if(forHoedur) originalPath.fileName else originalPath.absolutePathString(),
            0L,
            originalPath.fileSize(),
            allFunctions.map {"  0x${it.key.toString(16)}: ${it.value}" }.joinToString("\n")
        ))

        return configPath
    }

    @TaskInterface
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    suspend fun generateFuzzwareConfigForELF(forHoedur: Boolean): Path {
        if (prog.executableFormat != "Executable and Linking Format (ELF)") {
            logger.error("Not an ELF file")
            throw IllegalArgumentException()
        }

        val elfType: String? = prog.getOptions("Program Information").getString("ELF File Type", null)
        when (elfType) {
            in listOf("executable", "relocatable") -> logger.info("Got executable ELF file")
            null -> throw IllegalStateException("Failed to get ELF file type")
            else -> {
                logger.error("ELF file is not executable, $elfType got")
                throw IllegalArgumentException()
            }
        }

        val configPath = rootDir.resolve("config.yml")

        val elfHeaders = ELFStructures(prog, logger)
        var entryPoint = prog.addressFactory.defaultAddressSpace
            .getAddress(elfHeaders.elfHeader.e_entry())
        if (entryPoint.offset % 2 != 0L)
            entryPoint = entryPoint.subtract(1)
        // Check if there is a function in entry point
        prog.listing.getFunctionAt(entryPoint)
            ?: {
                logger.error("No function found at entry point, this ELF may be invalid.")
                throw IllegalStateException()
            }
        // Check whether the entry point is disassembled in Thumb mode, which is necessary in ARM Cortex chips
        val tmode = prog.getRegister("TMode")
            ?: throw IllegalStateException("No TMode register found in arch ${prog.languageID.idAsString}")
        if (prog.programContext.getRegisterValue(tmode, entryPoint).unsignedValue != 1.toBigInteger()) {
            logger.error("Entry point is not in thumb mode, this file may not be able to run on ARM Cortex chips")
            throw IllegalArgumentException()
        }

        logger.info("Config generated")

        val allFunctions: Map<Long, String> = prog.allFunctionStarts().associate { addr ->
            addr.offset to prog.listing.getFunctionAt(addr).name
        }

        if (allFunctions.isEmpty()) {
            logger.error("We cannot emulate a file that cannot get any function, not a valid firmware elf?")
            throw IllegalStateException()
        }

        val configString = elfConfigTemplate.format(
            parseSections(elfHeaders, forHoedur),
                allFunctions.map {"  0x${it.key.toString(16)}: ${it.value}" }.joinToString("\n")
            )

        configPath.writeText(configString)

        return configPath
    }

    suspend fun parseSections(elfHeaders: ELFStructures, forHoedur: Boolean): String {
        data class Part(val vaddr: Long, val size: Long, val fileOffset: Long, val perm: Int, val phdrIdx: Int)

        val parts: MutableList<Part> = mutableListOf()
        val binFile = rootDir.resolve("parsedELF.bin").toFile()
        val tempBinFile = rootDir.resolve("parsedELF_tmp.bin").toFile()
        binFile.writeText("")
        tempBinFile.writeText("")

        var bytesWritten: Long = 0
        val elfBytes = File(getMetadata().processedPath ?: getMetadata().originalPath).readBytes()
        var yamlConfig = ""

        // Sort out all parts, initialized or uninitialized
        // And generate bin file
        elfHeaders.elfProgramHeaders.mapIndexed { idx, it -> it to idx }
            .filter { it.first.type == ElfProgramHeaderType.PT_LOAD.value }.forEach { (it, idx) ->
            if (it.virtualAddress == it.physicalAddress) {
                if (it.fileSize == 0L)
                    parts.add(Part(
                        it.virtualAddress, it.memorySize, -1, it.flags and ELF_PHDR_PF_ALL, idx))
                else {
                    parts.add(Part(
                        it.virtualAddress, it.memorySize, bytesWritten, it.flags and ELF_PHDR_PF_ALL, idx)
                    )
                    val bytes = elfBytes.copyOfRange(it.offset.toInt(), (it.offset + it.fileSize).toInt())
                    binFile.appendBytes(bytes)
                    bytesWritten += it.fileSize
                }
            } else {
                parts.add(Part(
                    it.virtualAddress, it.memorySize, -1, it.flags and ELF_PHDR_PF_ALL, idx)
                )
                if (it.fileSize != 0L) {
                    parts.add(Part(
                        it.physicalAddress, it.fileSize, bytesWritten, it.flags and ELF_PHDR_PF_ALL, idx))
                    val bytes = elfBytes.copyOfRange(it.offset.toInt(), (it.offset + it.fileSize).toInt())
                    binFile.appendBytes(bytes)
                    bytesWritten += it.fileSize
                }
            }
        }

        // Meld parts
        parts.sortBy { it.vaddr }
        val meldedParts: MutableList<List<Int>> = mutableListOf()
        var lastMeld: MutableList<Int> = mutableListOf(0)
        parts.forEachIndexed { idx, part ->
            if (idx == parts.size - 1)
                return@forEachIndexed
            if ((parts[idx + 1].vaddr shr 12) == ((parts[idx].vaddr + parts[idx].size - 1) shr 12))
                lastMeld.add(idx + 1)
            else {
                meldedParts.add(lastMeld)
                lastMeld = mutableListOf(idx + 1)
            }
        }
        meldedParts.add(lastMeld)

        // Generate new bin file according to melded parts
        val binBytes = binFile.readBytes()
        val ivtAddr = elfHeaders.getIvtTableStart().offset
        bytesWritten = 0
        meldedParts.forEach { m ->
            val size = parts[m.last()].vaddr + parts[m.last()].size - parts[m.first()].vaddr
            val bytes = ByteArray(size.toInt())
            val base = parts[m.first()].vaddr
            if (m.all { parts[it].fileOffset == -1L }) {
                // All part is uninitialized
                // Expand ram region
                yamlConfig += if (parts[m.first()].vaddr >= 0x2000_0000 && parts[m.first()].vaddr < 0x3000_0000)
                    elfUnloadableMemEntry.format(
                        "ram",
                        parts[m.first()].vaddr,
                        ELFStructures.getPermissionString(parts.map { it.perm }.reduce { acc, value -> acc or value }),
                        RAM_SIZE
                    ).prependIndent("  ").removeSuffix("  ")
                else
                    elfUnloadableMemEntry.format(
                        getSectionPartName(elfHeaders,
                            m.map { elfHeaders.elfProgramHeaders[parts[it].phdrIdx] }),
                        parts[m.first()].vaddr,
                        ELFStructures.getPermissionString(parts.map { it.perm }.reduce { acc, value -> acc or value }),
                        size
                    ).prependIndent("  ").removeSuffix("  ")
            } else {
                m.forEach { idx ->
                    if (parts[idx].fileOffset != -1L) {
                        binBytes.copyOfRange(
                            parts[idx].fileOffset.toInt(), (parts[idx].fileOffset + parts[idx].size).toInt())
                            .copyInto(bytes, (parts[idx].vaddr - base).toInt())
                    }
                }
                tempBinFile.appendBytes(bytes)

                yamlConfig += elfLoadableMemEntry.format(
                    getSectionPartName(elfHeaders, m.map { elfHeaders.elfProgramHeaders[it] }),
                    parts[m.first()].vaddr,
                    if(forHoedur) binFile.name else binFile.absolutePath,
                    bytesWritten,
                    ELFStructures.getPermissionString(parts.map { it.perm }.reduce { acc, value -> acc or value }),
                    size
                ).prependIndent("  ").removeSuffix("  ")
                if (parts[m.first()].vaddr <= ivtAddr && ivtAddr < parts[m.first()].vaddr + size)
                    yamlConfig += "    ivt_offset: 0x${(ivtAddr - parts[m.first()].vaddr).toString(16)}\n"
                bytesWritten += size
            }
        }

        binFile.delete()
        tempBinFile.toPath().moveTo(binFile.toPath())
        return yamlConfig
    }

    private fun getSectionPartName(structure: ELFStructures, phdrs: List<ElfProgramHeader>): String {
        // The name of a group of program headers is set to the name of the section that has the
        // largest size in these headers
        return phdrs.map { phdr ->
            structure.elfSectionHeaders.filter {
                val b = structure.getBelongedProgram(it.value)
                b == phdr
            }
        }.flatMap { it.entries }.associate { it.key to it.value }.maxBy { it.value.size }.key
    }

    @TaskInterface
    @Throws(IllegalArgumentException::class, NumberFormatException::class, IllegalStateException::class)
    fun getCovInfo(proj: Path): CoverageInfo {
        if (proj.notExists())
            throw IllegalArgumentException("Project directory ($proj) not found")
        val cmd = "${conf.cmdPredo} && workon ${conf.venv} && " +
                  "fuzzware cov -p ${proj.absolutePathString()}"

        val info = CoverageInfo()
        val symbolPattern = Regex("^0x([0-9a-f]+) \\((.+)\\)$")
        val basicBlockPattern = Regex("^0x([0-9a-f]+) \\((.+)( \\+ 0x([0-9a-f]+))?\\)$")

        val builder = ProcessBuilder(*conf.cmdPrefix.toTypedArray(), cmd)
        val process: Process = builder.start()

        val output = process.inputStream.bufferedReader().readLines()
        var idx = 0
        output.forEach { line ->
            if (line == "====== Found Symbols ======" ||
                line == "====== Not Found Symbols ======" ||
                line == "====== Found Basic Blocks ======" )
                idx += 1
            else if (line.startsWith("0x")) {
                when (idx) {
                    1 -> {
                        val result = symbolPattern.matchEntire(line)
                            ?: throw IllegalStateException("Invalid line: $line")
                        info.foundSymbols.add(result.groupValues[1].toLong(16) to result.groupValues[2])
                    }
                    2 -> {
                        val result = symbolPattern.matchEntire(line)
                            ?: throw IllegalStateException("Invalid line: $line")
                        info.foundSymbols.add(result.groupValues[1].toLong(16) to result.groupValues[2])
                    }
                    3 -> {
                        val result = basicBlockPattern.matchEntire(line)
                            ?: throw IllegalStateException("Invalid line: $line")
                        info.foundBasicBlocks.add(
                            result.groupValues[1].toLong(16) to
                            (result.groupValues[2] to result.groupValues[4].let {
                                if (it.isEmpty()) 0
                                else it.toLong(16)
                            }))
                    }
                }
            }
        }
        return info
    }

    companion object {
        val configTemplate: String = """
            interrupt_triggers:
              trigger:
                every_nth_tick: 0x3e8
                fuzz_mode: round_robin
            memory_map:
              irq_ret:
                base_addr: 0xfffff000
                permissions: --x
                size: 0x1000
              mmio:
                base_addr: 0x40000000
                permissions: rw-
                size: 0x20000000
              nvic:
                base_addr: 0xe0000000
                permissions: rw-
                size: 0x10005000
              ram:
                base_addr: 0x20000000
                permissions: rw-
                size: 0x100000
              text:
                base_addr: %#x
                file: %s
                ivt_offset: %#x
                permissions: r-x
                size: %#x
              phr1:
                base_addr: 0x10000000
                permissions: rw-
                size: 0x10000000
            symbols:
            %s
        """.trimIndent()

        val elfConfigTemplate: String = """
            interrupt_triggers:
              trigger:
                every_nth_tick: 0x3e8
                fuzz_mode: round_robin
            memory_map:
              irq_ret:
                base_addr: 0xfffff000
                permissions: --x
                size: 0x1000
              mmio:
                base_addr: 0x40000000
                permissions: rw-
                size: 0x20000000
              nvic:
                base_addr: 0xe0000000
                permissions: rw-
                size: 0x10005000
            %s
            symbols:
            %s
        """.trimIndent()

        val elfUnloadableMemEntry: String = """
            %s:
              base_addr: %#x
              permissions: %s
              size: %#x
            
        """.trimIndent()

        val elfLoadableMemEntry: String = """
            %s:
              base_addr: %#x
              file: %s
              fileOffset: %#x
              permissions: %s
              size: %#x
            
        """.trimIndent()

        const val RAM_SIZE = 0x100000
    }
}