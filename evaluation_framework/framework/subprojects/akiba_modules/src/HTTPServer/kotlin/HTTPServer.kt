package org.iotsplab.akiba.process

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.mainAuxiliary.ProgramManager.metadata
import org.iotsplab.akiba.mainAuxiliary.WorkspaceManager.globalLogger
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.server.DynamicServer
import org.iotsplab.akiba.process.server.ProgressServer
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.NoCreateDatabase
import org.iotsplab.akiba.utils.RouteRequestMethod
import java.net.ServerSocket

/**
 * HTTPServer: A HTTP web server for managing multiple routes registered, will start as a global former procedure
 *
 * Detailed process:
 *  - Get all classes that contains a `RouteRequestMethod` annotation and register routes with given request methods
 *  - Start all routes for responding
 *
 * @author: Hornos3, Hornos3@github.com
 */
@IgnoreRuntimeTimeout
@NoCreateDatabase
class HTTPServer(
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO
): AkibaModule (
    id = -1,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel
) {

    @Throws(IllegalStateException::class)
    override suspend fun startProcess() {
        // Check if the server is running
        if (try {
                withContext(Dispatchers.IO) { ServerSocket(usingPort).use { it.reuseAddress = true } }
                false
            } catch (_: Exception) { true }) {
            logger.info("HTTP Server started at port $usingPort")
            importStageServer()
        } else
            throw IllegalStateException("HTTP Server failed to start")
    }

    /**
     * importStageServer: Import the stage server, the stage server need to import all task info in advance.
     */
    private fun importStageServer() {
        // An additional instruction to initialize the progress server
        val progressServer = servers["ProgressServer"] as ProgressServer

        metadata.forEach { p ->
            progressServer.addTask(p.path, p.id)
        }
        globalLogger.info("Complete initializing progress server, at /progress")
    }

    companion object {
        private var usingPort: Int = 0
        val servers: HashMap<String, DynamicServer> = hashMapOf()
        const val MAX_TRY_COUNT = 16
        const val PORT_START = 31778

        fun enableRoute(name: String) { servers[name]?.enabled = true }

        fun disableRoute(name: String) { servers[name]?.enabled = false }

        init {
            for (port in (PORT_START..<(PORT_START + MAX_TRY_COUNT))) {
                try {
                    embeddedServer(Netty, port) {
                        DynamicServer::class.java.permittedSubclasses .filter {
                            it.annotations.any { anno -> anno is RouteRequestMethod }
                        } .forEach {
                            val instance = it.getDeclaredConstructor().newInstance() as DynamicServer
                            servers[instance.name] = instance
                            enableRoute(instance.name)
                            val annotation = it.getAnnotation(RouteRequestMethod::class.java) ?:
                            throw IllegalArgumentException("RouteRequestMethod annotation required")
                            annotation.method.forEach { method ->
                                routing {
                                    when (method) {
                                        "GET" -> { get(instance.routePath) { instance.respondGet(call) } }
                                        "POST" -> { post(instance.routePath) { instance.respondPost(call) } }
                                        else -> { require(false) { "Incorrect request method name, must be GET or POST"}}
                                    }
                                }
                            }
                            globalAPIContext.lookup(instance)
                        }
                    } .start(wait = false)
                    usingPort = port
                    break
                } catch (_: Exception) {
                    if (port == PORT_START + MAX_TRY_COUNT - 1)
                        throw IllegalStateException(
                            "HTTP Server failed to start: concurrency full (reaching ${MAX_TRY_COUNT})")
                }
            }
        }
    }
}