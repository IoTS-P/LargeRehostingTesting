//package org.iotsplab.akiba.utils.io
//
//import kotlinx.coroutines.ThreadContextElement
//import java.io.PrintStream
//import java.io.StringWriter
//import kotlin.coroutines.CoroutineContext
//
//val outputBufferThreadLocal = ThreadLocal<StringWriter?>()
//
//class CoroutineOutputBuffer(private val writer: StringWriter) : ThreadContextElement<StringWriter?> {
//    companion object Key : CoroutineContext.Key<CoroutineOutputBuffer>
//
//    override val key: CoroutineContext.Key<*>
//        get() = Key
//
//    override fun updateThreadContext(context: CoroutineContext): StringWriter? {
//        val old = outputBufferThreadLocal.get()
//        outputBufferThreadLocal.set(writer)
//        return old
//    }
//
//    override fun restoreThreadContext(context: CoroutineContext, oldState: StringWriter?) {
//        outputBufferThreadLocal.set(oldState)
//    }
//}
//
//object FinderIO : PrintStream(System.out) {
//    override fun write(buf: ByteArray, off: Int, len: Int) {
//        val writer = outputBufferThreadLocal.get()
//        if (writer != null) {
//            writer.write(String(buf, off, len))
//        } else {
//            super.write(buf, off, len)
//        }
//    }
//
//    override fun write(b: Int) {
//        val writer = outputBufferThreadLocal.get()
//        if (writer != null) {
//            writer.write(b)
//        } else {
//            super.write(b)
//        }
//    }
//}