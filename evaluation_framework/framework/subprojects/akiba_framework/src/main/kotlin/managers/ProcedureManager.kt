package org.iotsplab.akiba.managers

import kotlinx.coroutines.withContext
import org.iotsplab.akiba.managers.ProgramManager.failedDir
import org.iotsplab.akiba.managers.ProgramManager.runtimeErrorDir
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.AkibaModule.Companion.FAILED
import org.iotsplab.akiba.module.AkibaModule.Companion.RUNTIME_ERROR
import org.iotsplab.akiba.module.ModuleContext
import org.iotsplab.akiba.module.ModuleLogContext
import org.iotsplab.akiba.utils.NoNeedToClose
import org.iotsplab.akiba.utils.ProcedureArguments
import java.nio.file.Path
import java.util.HashMap
import kotlin.coroutines.coroutineContext
import kotlin.io.path.moveTo
import kotlin.reflect.full.primaryConstructor

object ProcedureManager {
    var globalPreTasks: List<ProcedureArguments> = mutableListOf()

    val requiredProperties = mutableListOf<String>()

    suspend fun invokeProcedure(
        path: Path?,
        procedure: ProcedureArguments,
        args: HashMap<String, Any?>,
        apiContext: ModuleContext
    ): Boolean {
        val constructor = procedure.mainClass?.kotlin!!.primaryConstructor!!
        var failed = false
        val instance = try {
            constructor.call(
                *constructor.parameters.map {
                    args[it.name]
                }.toTypedArray(),
            ) as AkibaModule
        } catch (e: Exception) {
            globalLogger.error("Exception occurred while instantiating ${procedure.mainClass!!.name} " +
                "for ID ${args["id"]}: ${e.cause?.message ?: e.message}")
            throw e
        }

        withContext(coroutineContext + ModuleLogContext(instance.logger)) {
            if (procedure.timeout < 0) {
                instance.startProcess()
            } else {
                instance.startProcess(procedure.timeout)
            }
        }

        apiContext.lookup(instance)

        if (instance.javaClass.annotations.none { it is NoNeedToClose })
            instance.close()

        if (instance.failureSign == FAILED) {
            failed = true
            globalLogger.error("Failed to run ${instance.javaClass.simpleName}" +
                    "${path?.let { " on $it" } ?: ""}, latter tasks skipped")
            instance.logDir.moveTo(failedDir.resolve(instance.logDir.fileName))
        } else if (instance.failureSign == RUNTIME_ERROR) {
            failed = true
            globalLogger.error("Failed to run ${instance.javaClass.simpleName}" +
                    "${path?.let { " on $it" } ?: ""} due to runtime error")
            instance.logDir.moveTo(runtimeErrorDir.resolve(instance.logDir.fileName))
        }
        return failed
    }
}