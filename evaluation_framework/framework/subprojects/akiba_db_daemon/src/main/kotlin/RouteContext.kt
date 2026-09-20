package org.iotsplab.akiba.dbDaemon


import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

class RouteContext(
    val call: ApplicationCall
) {
    suspend inline fun <reified T> receive(): T =
        call.receive()

    suspend fun respond(obj: Any) =
        call.respond(obj)

    fun header(name: String): String? =
        call.request.headers[name]
}