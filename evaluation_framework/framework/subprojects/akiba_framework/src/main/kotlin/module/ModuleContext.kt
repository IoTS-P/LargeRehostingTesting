package org.iotsplab.akiba.module

import kotlinx.coroutines.ThreadContextElement
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.managers.BinaryMetadata
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.utils.TaskInterface
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KFunction
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.isAccessible    // for task domain APIs

class ModuleContext(val metadata: BinaryMetadata)
    : CoroutineContext.Element
{
    private val globalLateinitInterfaces: HashMap<KFunction<*>, Any> = HashMap()
    val data: HashMap<String, Any?> = HashMap()

    fun<T: Any> lookup(instance: T) {
        instance.javaClass.kotlin.memberFunctions.forEach {
            if (it.annotations.any { annotation -> annotation is TaskInterface }) {
                it.isAccessible = true
                globalLateinitInterfaces[it] = instance
            }
        }
    }

    fun call(function: KFunction<*>, vararg args: Any?): Any? {
        globalLateinitInterfaces[function] ?. let {
            return function.call(it, *args)
        } ?: require(false) { "Function ${function.name} not found" }
        return null
    }

    companion object Key : CoroutineContext.Key<ModuleContext>

    override val key: CoroutineContext.Key<*> get() = Key
}

object GlobalContext : CoroutineContext.Element {
    object Key : CoroutineContext.Key<GlobalContext>

    override val key: CoroutineContext.Key<*> get() = Key
}

object LoggerHolder {
    val threadLocal: ThreadLocal<Logger> = ThreadLocal()
}

class ModuleLogContext(val logger: Logger)
    : CoroutineContext.Element, ThreadContextElement<Logger>
{
    companion object Key: CoroutineContext.Key<ModuleLogContext>

    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): Logger {
        val old = LoggerHolder.threadLocal.get()
        LoggerHolder.threadLocal.set(logger)
        return old ?: logger
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Logger) {
        LoggerHolder.threadLocal.set(oldState)
    }
}

object Log {
    val current: Logger
        get() = LoggerHolder.threadLocal.get() ?: globalLogger
}