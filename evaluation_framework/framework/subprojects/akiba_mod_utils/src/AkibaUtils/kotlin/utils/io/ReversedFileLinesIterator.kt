package org.iotsplab.akiba.utils.io

import kotlinx.io.files.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * 反向文件行迭代器。
 * 从文件末尾开始逐行向前迭代，支持大文件的高效反向遍历。
 *
 * @param path 要迭代的文件路径。
 */
class ReversedFileLinesIterator(private val path: Path): Iterator<String> {
    private var position: Long = path.toFile().length()
    private val lineBreak = "\n".toByteArray()[0]
    private val buf = ByteArray(1024)
    private var bufPtr = 0
    private var currentLine = StringBuilder()

    /**
     * 获取下一个行。
     */
    override fun next(): String {
        RandomAccessFile(path.toFile(), "r").use { file ->
            while (bufPtr >= 0) {
                if (buf[bufPtr] == lineBreak && currentLine.isNotEmpty()) {
                    val ret = currentLine.reverse().toString()
                    currentLine.clear()
                    --bufPtr
                    return ret
                } else {
                    currentLine.append(buf[bufPtr].toInt().toChar())
                    --bufPtr
                }
            }

            while (position > 0) {
                val bytesToRead = minOf(position, buf.size.toLong()).toInt()
                position -= bytesToRead
                file.seek(position)

                file.readFully(buf, 0, bytesToRead)
                bufPtr = bytesToRead - 1

                while (bufPtr >= 0) {
                    if (buf[bufPtr] == lineBreak && currentLine.isNotEmpty()) {
                        val ret = currentLine.reverse().toString()
                        currentLine.clear()
                        --bufPtr
                        return ret
                    } else {
                        currentLine.append(buf[bufPtr].toInt().toChar())
                        --bufPtr
                    }
                }
            }

            // Reached at the beginning of the file
            if (currentLine.isNotEmpty()) {
                val ret = currentLine.reverse().toString()
                currentLine.clear()
                return ret
            } else
                throw NoSuchElementException("File has iterated to the beginning")
        }
    }

    /**
     * 检查是否还有下一个行。
     */
    override fun hasNext(): Boolean {
        return position > 0 || bufPtr > 0
    }

    init {
        if (!path.toFile().exists())
            throw FileNotFoundException("File $path does not exist.")
    }
}