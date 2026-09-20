package org.iotsplab.akiba.dbDaemon

import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.FrameType
import io.ktor.websocket.close
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.channels.consumeEach
import org.iotsplab.akiba.dbDaemon.DatabaseDaemon.Companion.globalLogger
import org.iotsplab.akiba.dbDaemon.dbutil.UserDatabaseSession
import org.iotsplab.akiba.dbDaemon.operations.PGInstances
import org.iotsplab.akiba.dbDaemon.operations.PGInstances.tokens
import java.util.UUID

abstract class AbstractPostRoute {

    data class FastAccessStruct (
        val token: UUID,
        val user: String,
        val session: UserDatabaseSession
    )

    class FastAccessException(val code: HttpStatusCode): Exception()

    // HTTP path, like 'auth/login'
    abstract val path: String

    // Whether to disable the route or not
    var disabled: Boolean = false

    // Whether to respond or not
    var respond: Boolean = true

    // Core handler
    protected abstract suspend fun handle(ctx: RouteContext): Any?

    open fun registerPost(routing: Routing) {
        routing.post(path) {
            if (disabled) {
                call.respond(HttpStatusCode.Locked)
                return@post
            }

            val ctx = RouteContext(call)

            val result = try {
                globalLogger.trace("Got POST request $path")
                handle(ctx).let {
                    globalLogger.trace("POST Response: {}", it)
                    it
                }
            } catch(e: ContentTransformationException) {
                // User data not valid
                globalLogger.error("Content format error: ${e.message}")
                call.respond(HttpStatusCode.BadRequest)
            } catch(e: Exception) {
                call.respond(HttpStatusCode.InternalServerError)
                globalLogger.error("Internal server error occurred on POST $path: ${e.message}")
                globalLogger.error(e.stackTraceToString())
                return@post
            }

            if (respond) {
                if (result == null) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(result)
                }
            }
        }
    }

    companion object {
        @Throws(FastAccessException::class)
        fun fastAccess(ctx: RouteContext): FastAccessStruct {
            val token = try {
                ctx.call.request.header(HttpHeaders.Authorization) ?.let { UUID.fromString(it) }
                    ?: throw FastAccessException(
                        HttpStatusCode.Unauthorized.description("No authorization token provided"))
            } catch (_: Exception) {
                throw FastAccessException(
                    HttpStatusCode.BadRequest.description("Invalid authorization token"))
            }
            val user = tokens[token]
                ?: throw FastAccessException(
                    HttpStatusCode.BadRequest.description("Owner of the token not found"))

            val dbSession: UserDatabaseSession = try {
                PGInstances.tokenSessions
                    .getResource(token, user)
                    ?: throw FastAccessException(
                        HttpStatusCode.BadRequest.description("No database session found"))
            } catch (e: Exception) {
                throw FastAccessException(
                    HttpStatusCode.BadRequest.description("Get session failed: ${e.message}"))
            }

            PGInstances.tokenSessions.renew(token, user)
            return FastAccessStruct(token, user, dbSession)
        }
    }
}