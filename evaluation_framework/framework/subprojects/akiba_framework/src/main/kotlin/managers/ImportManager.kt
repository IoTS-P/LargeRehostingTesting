package org.iotsplab.akiba.managers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ghidra.program.model.lang.Language
import ghidra.program.model.lang.LanguageID
import ghidra.program.model.listing.Program
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.iotsplab.akiba.Main.Companion.importConfig
import org.iotsplab.akiba.client.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.managers.ProgramManager.autoAnalyzeInTimeout
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.managers.WorkspaceManager.languageProvider
import org.iotsplab.akiba.managers.WorkspaceManager.project
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*
import kotlin.math.min

object ImportManager {
    @Serializable
    data class ImportConfig(
        val entries: List<Map<String, String?>>? = null
    )

    data class ImportProperty(
        var path: Path,         // Relative path of binary root
        var arch: Language? = null,
        var extraProperties: Map<String, String?>? = null,   // All properties must be string or null
        var fileSize: Int = -1
    )

    @Serializable
    data class FileSegment(
        val oldOffset: Long,
        val newOffset: Long,
        val length: Long
    )

    private lateinit var config: ImportConfig
    private val importList: MutableList<ImportProperty> = mutableListOf()

    @Throws(IllegalStateException::class)
    fun import() {
        if (mainConf.importRoot == null)
            throw IllegalArgumentException("Import root is not set")
        lockImport()

        readConfig()

        importList.mapIndexed { idx, entry ->
            val originalPath = if (entry.path.startsWith("/")) entry.path.absolute()
                               else Path.of(mainConf.importRoot!!).resolve(entry.path).absolute()

            globalLogger.info("Importing [${idx + 1}/${importList.size}] $originalPath")
            val originalChecksum = ProgramManager.getFileMD5Checksum(originalPath)
            if (DatabaseClient.checkMD5Duplicate(originalChecksum)) {
                globalLogger.warn("Found duplicate checksum of ${entry.path}, skipped")
                return@mapIndexed
            }

            // Check if file exists
            if (originalPath.notExists() || !originalPath.isRegularFile()) {
                globalLogger.error("File not found: $originalPath, skipped")
                return@mapIndexed
            }

            // We have removed all duplicated files in `readConfig`, no need to check it again here

            // If Ghidra can automatically detect the file format, import directly and insert it to database
            ProgramManager.tryCreateProgramWithAutoDetect(project, originalPath)?.let {
                // Ghidra successfully identified the binary format, import directly
                val id = DatabaseClient.insertBinary(
                    DatabaseClient.InsertData(
                        originalPath = originalPath.absolutePathString(),
                        processedPath = null,
                        checksum = originalChecksum,
                        processedChecksum = null,
                        size = originalPath.fileSize(),
                        processedSize = -1,
                        loadProperties = null,
                        arch = it.languageID.idAsString,
                        format = it.executableFormat,
                        compilerSpec = it.compiler
                    )
                )
                originalPath.copyTo(WorkspaceManager.binaryPath.resolve("$id.bin"))

                project.close(it)
                return@mapIndexed
            }

            // Preprocess binaries, now only contains removing unnecessary continuous \x00 segments
            val preprocessed: Pair<Path, List<FileSegment>>? = preprocessFile(originalPath)
            val program: Program? = entry.arch?.let {
                val prog = project.importProgram(
                    originalPath.toFile(),
                    languageProvider.getLanguage(it.languageID),
                    null
                )
                autoAnalyzeInTimeout(prog, mainConf.autoAnalysisTimeout)
                prog
            } ?: ProgramManager.tryCreateProgramWithoutLang(
                project = project,
                path = preprocessed?.first ?: originalPath,
                loadProperties = preprocessed?.second ?: listOf()
            ) ?: run {
                globalLogger.warn("Failed to guess language: $originalPath")
                null
            }

            // If no arch is specified, try to guess its arch, if no arch matched, use 'n/a'
            val arch = entry.arch?.languageID?.toString() ?: program?.languageID?.toString() ?: "n/a"

            val id = DatabaseClient.insertBinary(DatabaseClient.InsertData(
                originalPath = originalPath.absolutePathString(),
                processedPath = preprocessed?.first?.absolutePathString(),
                checksum = originalChecksum,
                processedChecksum = preprocessed?.let { ProgramManager.getFileMD5Checksum(it.first) },
                size = originalPath.fileSize(),
                processedSize = preprocessed?.first?.fileSize() ?: -1,
                loadProperties = preprocessed?.let { jacksonObjectMapper().writeValueAsString(it.second) },
                arch = arch,
                format = program?.executableFormat?:"n/a",
                compilerSpec = program?.compiler?:"n/a"
            ))
            originalPath.copyTo(
                WorkspaceManager.binaryPath.resolve("$id.bin"), overwrite = true)
            preprocessed?.first?.moveTo(
                WorkspaceManager.processedBinaryPath.resolve("$id.bin"), overwrite = true)

            program ?.let {
                it.name = "$id-${originalPath.fileName}"
                project.saveAs(it, "/", it.name, false)
                project.close(program)
            }
        }

        unlockImport()
    }

    @Throws(IllegalStateException::class)
    private fun lockImport() {
        val lockFile: Path = Path.of(mainConf.binariesRoot).resolve("import.lock")
        if (lockFile.exists()) {
            throw IllegalStateException("An import task is already running")
        }
    }

    @Throws(IllegalStateException::class)
    private fun unlockImport() {
        val lockFile: Path = Path.of(mainConf.binariesRoot).resolve("import.lock")
        if (lockFile.exists()) {
            lockFile.deleteIfExists()
        }
    }

    @Throws(IllegalStateException::class)
    private fun readConfig() {
        check (importConfig != null) { "Import config is not set" }
        val path = Path.of(importConfig!!)
        globalLogger.info("Import config path: $path")
        check (path.isRegularFile()) { "Import config file does not exist" }
        try {
            config = Json.decodeFromString(path.readText())
        } catch (e: Exception) {
            throw IllegalStateException("Failed to read import config file: ${e.message}")
        }
        parseList()
    }

    @Throws(IllegalArgumentException::class)
    private fun parseList() {
        config.entries?.forEach {
            importList.addAll(parseEntry(it))
        }
    }

    @Throws(IllegalArgumentException::class)
    private fun parseEntry(entry: Map<String, String?>): List<ImportProperty> {
        require(entry["path"] != null && entry["path"] is String) { "Path is not set" }
        val absolutePath = Path.of(mainConf.importRoot!!).resolve(Path.of(entry["path"]!!))
        require(absolutePath.exists()) { "Path $absolutePath does not exist" }
        val arch = entry["arch"] ?. let {
            val languageID = LanguageID(it)
            languageProvider.getLanguage(languageID) ?: run {
                throw IllegalArgumentException("Architecture invalid: $it")
            }
        }
        val properties: MutableMap<String, String?> = mutableMapOf()
        entry.forEach { (k, v) ->
            if (k != "path" && k != "arch") {
                require(v is String || v == null) { "Each property must be string or null" }
                properties[k] = v
            }
        }

        if (absolutePath.isRegularFile()) {
            globalLogger.info("Found ${Path.of(entry["path"]!!)}")
            return listOf(ImportProperty(Path.of(entry["path"]!!), arch, properties,
                absolutePath.toFile().length().toInt()))
        } else {
            val files = absolutePath.toFile().listFiles()
            return files ?.mapIndexed { idx, it ->
                globalLogger.info("Found ${it.absolutePath} in $absolutePath")
                if (it.length() < MIN_BINARIES_SIZE) return@mapIndexed null
                if (!it.toPath().isRegularFile()) return@mapIndexed null
                ImportProperty(Path.of(mainConf.importRoot!!).relativize(it.toPath()), arch, properties,
                    it.length().toInt())
            }
            ?.filterNotNull() ?: listOf()
        }
    }

    // Process files to remove long zero segments

    fun preprocessFile(path: Path): Pair<Path, List<FileSegment>>? {
        val zeroRegions = detectZeroRegions(path)
        if (zeroRegions.isEmpty())
            return null

        val nonZeroSegments = calculateNonZeroSegments(path.fileSize(), zeroRegions)
        if (nonZeroSegments.isNotEmpty())
            return createTrimmedFile(path, nonZeroSegments) to nonZeroSegments
        else {
            globalLogger.warn("$path is an all-0 file, ignored")
            return null
        }
    }

    private fun detectZeroRegions(path: Path): List<Pair<Long, Long>> {
        val zeroRegions = mutableListOf<Pair<Long, Long>>()
        var currentStart = -1L
        var currentLength = 0L

        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.allocate(8192)
            var filePosition = 0L

            while (channel.read(buffer) != -1) {
                buffer.flip()
                for (i in 0 until buffer.limit()) {
                    if (buffer.get(i) == 0.toByte()) {
                        if (currentStart == -1L) currentStart = filePosition + i
                        currentLength++
                    } else {
                        checkAndRecordRegion(currentStart, currentLength, zeroRegions)
                        currentStart = -1L
                        currentLength = 0L
                    }
                }
                filePosition += buffer.limit()
                buffer.clear()
            }

            checkAndRecordRegion(currentStart, currentLength, zeroRegions)
        }

        return mergeAdjacentRegions(zeroRegions)
    }

    private fun checkAndRecordRegion(start: Long, length: Long, regions: MutableList<Pair<Long, Long>>) {
        if (length >= 0x10000) {
            regions.add(start to (start + length))
        }
    }

    private fun mergeAdjacentRegions(regions: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        return regions.sortedBy { it.first }.fold(mutableListOf()) { acc, region ->
            acc.lastOrNull()?.let { last ->
                if (region.first <= last.second) {
                    acc[acc.lastIndex] = last.first to maxOf(last.second, region.second)
                    return@fold acc
                }
            }
            acc.add(region)
            acc
        }
    }

    private fun calculateNonZeroSegments(fileSize: Long, zeroRegions: List<Pair<Long, Long>>): List<FileSegment> {
        val segments = mutableListOf<FileSegment>()
        var prevEnd = 0L
        var newOffset = 0L
        var segStart: Long
        var segEnd: Long

        for ((start, end) in zeroRegions.sortedBy { it.first }) {
            if (start > prevEnd) {
                segStart = prevEnd - prevEnd % ZERO_SPACE_ALIGNMENT
                segEnd = if (start % ZERO_SPACE_ALIGNMENT == 0L) start
                         else start + ZERO_SPACE_ALIGNMENT - start % ZERO_SPACE_ALIGNMENT
                segments.add(FileSegment(segStart, newOffset, segEnd - segStart))
                newOffset += segEnd - segStart
            }
            prevEnd = end
        }

        if (prevEnd < fileSize) {
            segStart = prevEnd - prevEnd % ZERO_SPACE_ALIGNMENT
            segments.add(FileSegment(segStart, newOffset, fileSize - segStart))
        }

        return segments
    }

    private fun createTrimmedFile(source: Path, segments: List<FileSegment>): Path {
        val target = Path.of("/tmp").resolve("trimmed-" + source.fileName)

        FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { targetChannel ->
            FileChannel.open(source, StandardOpenOption.READ).use { sourceChannel ->
                for ((offset, _, length) in segments) {
                    var remaining = length
                    var position = offset

                    while (remaining > 0) {
                        val transferSize = min(remaining, 8 * 1024 * 1024) // 8MB buffer
                        targetChannel.transferFrom(
                            sourceChannel.position(position),
                            targetChannel.size(),
                            transferSize
                        )
                        remaining -= transferSize
                        position += transferSize
                    }
                }
                sourceChannel.force(true)
                sourceChannel.close()
            }
            targetChannel.force(true)
            targetChannel.close()
        }

        return target
    }

    private const val MIN_BINARIES_SIZE: Int = 0x1000
    private const val ZERO_SPACE_ALIGNMENT: Int = 0x10
}