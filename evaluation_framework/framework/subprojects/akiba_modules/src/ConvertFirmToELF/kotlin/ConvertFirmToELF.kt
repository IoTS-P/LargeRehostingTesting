package org.iotsplab.akiba.process

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.process.iotgs.BasicLoadMetadata
import org.iotsplab.akiba.utils.DataConsumer
import net.fornwall.jelf.ElfFile
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.FailOnCancelled
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.memory.MemorySection
import org.iotsplab.akiba.utils.memory.MemoryUtil.Companion.numberToBytes
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize

/**
 * ConvertFirmToELF: Convert firmware file to ELF file, to make it easier to run in various fuzzing tools.
 *
 * We need much information to restore a bin back to an ELF:
 * - Basic info:
 *   - Word length (32 / 64)
 *   - Endianness (little / big)
 *   - Architecture (x86 / ARM / ...)
 * - Memory sections, every section need:
 *   - Base address
 *   - Offset in file (May not exist for some section, like .bss)
 *   - Size
 * - Entry point
 */
@DataConsumer<BasicLoadMetadata>("load_metadata")
@WithTableColumn("elf_path", "TEXT")
@WithConfigClass(ConvertFirmToELFConfig::class)
@IgnoreRuntimeTimeout
@FailOnCancelled
class ConvertFirmToELF(
    id: Int,
    program: Program,
    configPath: String,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    program = program,
    configPath = configPath,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!
    val conf: ConvertFirmToELFConfig
        get() = config as ConvertFirmToELFConfig

    private lateinit var outElfPath: Path

    init {
        if (System.getProperty("os.name") != "Linux")
            throw Exception("ConvertFirmToELF: Only Linux is supported")
    }

    @Throws(Exception::class)
    override suspend fun startProcess() {
        outElfPath = Path.of(mainConf.binariesRoot, "parsed_elfs", "$id.elf")
        outElfPath.parent.createDirectories()

        // Use a temporary file in /tmp to exchange data with the ELF parser
        val tempFile = Path.of("/tmp/${UUID.randomUUID()}")
        // val tempFile = Path.of("/tmp/tmp-$id")
        val metadata: BasicLoadMetadata =
            when (conf.useSource) {
                "existing" -> getTaskData("load_metadata") as BasicLoadMetadata
                "firmxray" -> parseMetadataFromFirmXRay()
                else -> throw IllegalArgumentException("Invalid use source: ${conf.useSource}")
            }

        FileOutputStream(tempFile.toFile()).use {
            encodeMetadata(metadata, it, conf.useSource == "firmxray")
        }
        logger.info("Encoded load property into ${tempFile.absolutePathString()}")

        // Execute command to start C++ ELF parser
        extractFileInJar("ELFBuilder/cmake-build-debug/ELFBuilder", Path.of("/tmp/ELFBuilder"))

        logger.debug("Start building ELF file through existing metadata...")
        ProcessBuilder("chmod", "+x", "/tmp/ELFBuilder").start().waitFor()
        val proc = ProcessBuilder("/tmp/ELFBuilder", tempFile.toString(), outElfPath.toString())
            .redirectErrorStream(true).start()
        proc.waitFor()
        if (proc.exitValue() != 0) {
            logger.error("Process failed: \n${proc.inputStream.bufferedReader().readText()}")
            failureSign = FAILED
            return
        } else {
            logger.info("Process success: \n${proc.inputStream.bufferedReader().readText()}")
        }
        logger.debug("Finished building ELF file to {}", outElfPath.absolutePathString())

        // Save ELF path to database
        updateData(mapOf(
            "elf_path" to Path.of(mainConf.binariesRoot).relativize(outElfPath).toString()))

        // Delete temp file
        tempFile.toFile().delete()
    }

    fun encodeMetadata(metadata: BasicLoadMetadata, stream: FileOutputStream, useOriginalFile: Boolean = false) {
        val (arch, endian, wordSize, series) = prog.languageID.idAsString.split(":")

        // Architecture
        archMap["$arch:$wordSize"] ?. let {
            stream.write(numberToBytes(it.toShort()))
        } ?: throw UnsupportedOperationException("Unsupported architecture: $arch:$wordSize")

        // Word size
        stream.write(
            byteArrayOf( when (wordSize) {
                "32" -> ElfFile.CLASS_32
                "64" -> ElfFile.CLASS_64
                else -> throw UnsupportedOperationException("Unsupported word size: $wordSize")
            })
        )

        // Endianness
        stream.write(
            byteArrayOf( when (endian) {
                "BE" -> ElfFile.DATA_MSB
                "LE" -> ElfFile.DATA_LSB
                else -> throw UnsupportedOperationException("Unsupported endianness: $endian")
            })
        )

        // Entry point
        stream.write(numberToBytes(metadata.entryPoint.offset))

        // Sections
        // image base, size, is_mapped, prot, name, content(if is_mapped is true)
        for (section in metadata.sections) {
            stream.write(numberToBytes(section.mapStart.offset))
            stream.write(numberToBytes(section.size))
            stream.write(byteArrayOf(if (section.fileOffset != null) 1 else 0))
            stream.write(numberToBytes(section.prot))
            // Null terminated, experiments show that there is no ending '\0' while parsing String to ByteArray
            stream.write(section.name.toByteArray().plus(byteArrayOf(0)))
            if (section.fileOffset != null) {
                val content = ByteArray(section.size.toInt())
                val bin = RandomAccessFile(if (useOriginalFile) originalFile else usingFile, "r")
                bin.seek(section.fileOffset!!)
                val bytesRead = bin.read(content)
                // If not enough bytes got, fill the rest with 0
                if (bytesRead < content.size)
                    (bytesRead..<content.size).forEach { content[it] = 0.toByte() }
                stream.write(content)
            }
        }
    }

    private suspend fun parseMetadataFromFirmXRay(): BasicLoadMetadata {
        val api = FlatProgramAPI(prog)
        val baseAddress = getTaskData("firmxray_results.base_address") as Long
        val initialSP = prog.memory.getInt(prog.memory.blocks.minBy { it.start }.start)
        val entryPoint = prog.memory.getInt(prog.memory.blocks.minBy { it.start }.start.add(4))
        val sections = listOf(
            MemorySection(
                fileOffset = 0,
                size = originalFile.toPath().fileSize(),
                mapStart = api.toAddr(baseAddress),
                description = "Program Flash",
                name = "load",
                prot = MemorySection.PROT_READ or MemorySection.PROT_WRITE or MemorySection.PROT_EXEC
            )
        )
        return BasicLoadMetadata(
            baseAddress = api.toAddr(baseAddress),
            entryPoint = api.toAddr(entryPoint.toLong()),
            sections = sections,
            initialSP = api.toAddr(initialSP.toLong())
        )
    }

    companion object {
        val archMap: HashMap<String, Int> = hashMapOf(
            "ARM:32" to ElfFile.ARCH_ARM,
            "ARM:64" to ElfFile.ARCH_AARCH64,
            "MIPS:32" to ElfFile.ARCH_MIPS,
            "MIPS:64" to ElfFile.ARCH_MIPS,
            "RISCV:32" to 243,  // RISCV
            "RISCV:64" to 243,
            "x86:32" to ElfFile.ARCH_i386,
            "x86:64" to ElfFile.ARCH_X86_64
        )
    }
}