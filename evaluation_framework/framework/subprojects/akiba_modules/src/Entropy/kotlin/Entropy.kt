package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.FailOnCancelled
import java.io.FileInputStream
import kotlin.io.path.fileSize
import kotlin.math.log2

@WithTableColumn("entropy", "DOUBLE PRECISION")
@FailOnCancelled
class Entropy (
    id: Int,
    program: Program?,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    private val ctrs: IntArray = IntArray(256)
    private val bufferSize = 8192

    override suspend fun startProcess() {
        val totalSize = usingFile.toPath().fileSize()

        FileInputStream(usingFile).use { inputStream ->
            val buffer = ByteArray(bufferSize)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                for (i in 0 until bytesRead) {
                    val byteValue = buffer[i].toInt() and 0xFF // 转换为0-255范围
                    ctrs[byteValue] += 1
                }
            }
        }

        val entropy = ctrs.sumOf { cnt ->
            if (cnt == 0)   0.toDouble()
            else            -1 * cnt.toDouble() / totalSize * log2(cnt.toDouble() / totalSize)
        }
        logger.info("Entropy: $entropy")
        updateData(mapOf("entropy" to entropy))
    }
}