package org.iotsplab.akiba.dbDaemon.operations

import org.iotsplab.akiba.dbDaemon.AbstractPostRoute
import org.iotsplab.akiba.dbDaemon.RouteContext

object Updates {
    object UpdateMetadata: AbstractPostRoute() {
        override val path: String = "/update/metadata"

        data class UpdateRequest (
            val column: String,
            val value: Any,
        )

        override suspend fun handle(ctx: RouteContext): Any? {
            TODO("Not yet implemented")
        }
    }
}