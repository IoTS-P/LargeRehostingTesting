package org.iotsplab.akiba.dbDaemon

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import org.iotsplab.akiba.dbDaemon.DatabaseDaemon.Companion.globalLogger

abstract class AbstractWebsocketRoute {
    class AkibaWebsocketException(override val message: String): Exception(message)

    // HTTP path, like 'auth/login'
    abstract val path: String

    // Whether to disable the route or not
    var disabled: Boolean = false

    // Core handler
    @Throws(AkibaWebsocketException::class)
    protected open suspend fun handle(session: DefaultWebSocketServerSession, frames: ReceiveChannel<Frame>) {}

    open fun registerWebsocket(routing: Routing) {
        routing.webSocket(path) {
            if (disabled) {
                call.respond(HttpStatusCode.Locked)
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not available"))
                return@webSocket
            }

            try {
                globalLogger.trace("Got websocket request $path")
                handle(this, incoming)
            } catch(_: ContentTransformationException) {
                close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Content error"))
            } catch(e: AkibaWebsocketException) {
                close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, e.message))
            } catch(e: Throwable) {
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Internal server error"))
                globalLogger.error("Internal server error occurred on websocket $path: ${e.message}")
                globalLogger.error(e.stackTraceToString())
            }

            close(CloseReason(CloseReason.Codes.NORMAL, "Closed normally"))
        }
    }
}