//package org.iotsplab.akiba.utils.io
//
//import java.io.FileInputStream
//import java.io.FileOutputStream
//import java.nio.file.Path
//import kotlin.io.path.exists
//
//class NamedPipe(private val path: Path): AutoCloseable {
//    init {
//        if (System.getProperty("os.name") != "Linux")
//            throw UnsupportedOperationException("Named pipe is only supported on Linux")
//
//        if(!path.exists())
//            Runtime.getRuntime().exec(arrayOf("mkfifo", path.toString())).waitFor()
//    }
//
//    fun read(size: Int): ByteArray {
//        FileInputStream(path.toFile()).use { inputStream ->
//            val buffer = ByteArray(size)
//            val bytesRead = inputStream.read(buffer)
//            return buffer.copyOfRange(0, bytesRead)
//        }
//    }
//
//    fun write(data: ByteArray) {
//        FileOutputStream(path.toFile()).use { outputStream ->
//            outputStream.write(data)
//        }
//    }
//
//    override fun close() {
//        Runtime.getRuntime().exec(arrayOf("rm", path.toString())).waitFor()
//    }
//}