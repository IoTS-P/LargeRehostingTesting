package org.iotsplab.akiba.dbDaemon.operations

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import org.iotsplab.akiba.dbDaemon.AbstractPostRoute
import org.iotsplab.akiba.dbDaemon.RouteContext
import org.iotsplab.akiba.dbDaemon.RouteRegistry
import org.iotsplab.akiba.dbDaemon.dbutil.UserDatabaseSession
import org.iotsplab.akiba.dbDaemon.operations.PGInstances.tokens
import java.util.UUID

/**
 * Control routes, for daemon controls
 *
 * Including:
 *
 * @property EnableRoute Enable an HTTP route
 * @property DisableRoute Disable an HTTP route
 */
object Controls {
    // TODO: THIS ROUTE IS DANGEROUS AND CANNOT BE SIMPLY EXPOSED, ADD PRIVILEGE CONTROL LATER
    /**
     * Enable an HTTP route
     *
     * @property path `/control/enable`
     */
    object EnableRoute: AbstractPostRoute() {
        override val path: String = "/control/enable"

        override suspend fun handle(ctx: RouteContext): Any? {
            val routeName = ctx.receive<Map<String, String>>()["route"]
                ?: return HttpStatusCode.BadRequest.description("No route name provided")

            return RouteRegistry.enable(routeName)
        }
    }

    /**
     * Disable an HTTP route
     *
     * @property path `/control/disable`
     */
    object DisableRoute: AbstractPostRoute() {
        override val path: String = "/control/disable"

        override suspend fun handle(ctx: RouteContext): Any? {
            val routeName = ctx.receive<Map<String, String>>()["route"]
                ?: return HttpStatusCode.BadRequest.description("No route name provided")

            return RouteRegistry.disable(routeName)
        }
    }

    object Heartbeat: AbstractPostRoute() {
        override val path: String = "/heartbeat"

        override suspend fun handle(ctx: RouteContext): Any? {
            val token = ctx.call.request.header(HttpHeaders.Authorization) ?.let { UUID.fromString(it) }
                ?: return HttpStatusCode.Unauthorized.description("No authorization token provided")
            val user = tokens[token]
                ?: return HttpStatusCode.BadRequest.description("Token $token not found")
            val dbSession: UserDatabaseSession? = try {
                PGInstances.tokenSessions.getResource(token, user)
            } catch (e: Exception) {
                throw FastAccessException(
                    HttpStatusCode.BadRequest.description("Get session failed: ${e.message}"))
            }

            dbSession?.let { PGInstances.tokenSessions.renew(token, user) }
            return HttpStatusCode.NoContent
        }
    }
}