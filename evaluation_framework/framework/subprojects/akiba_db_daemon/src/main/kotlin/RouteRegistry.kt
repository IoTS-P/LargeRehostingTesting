package org.iotsplab.akiba.dbDaemon

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.iotsplab.akiba.dbDaemon.operations.Backups
import org.iotsplab.akiba.dbDaemon.operations.Controls
import org.iotsplab.akiba.dbDaemon.operations.Insertions
import org.iotsplab.akiba.dbDaemon.operations.Modules
import org.iotsplab.akiba.dbDaemon.operations.PGInstances
import org.iotsplab.akiba.dbDaemon.operations.Queries

object RouteRegistry {

    // Add routes here
    private val postRoutes: List<AbstractPostRoute> = listOf(
        Queries.GetBinaryMetadata,
        Queries.GetModuleData,
        Queries.SelectIdInSQL,

        Insertions.MD5Exists,
        Insertions.InsertBinary,

        Modules.CreateModuleTable,
        Modules.CreateView,
        Modules.TableLock,
        Modules.TableUnlock,
        Modules.UpdateData,
        Modules.StartTask,
        Modules.FinishTask,

        Controls.EnableRoute,
        Controls.DisableRoute,
        Controls.Heartbeat,

        PGInstances.ClientLogin,
        PGInstances.ClientLogout,
        PGInstances.ConnectToInstance,
        PGInstances.DisconnectFromInstance,
        PGInstances.ShutdownInstance,
        PGInstances.DeleteInstance,

        Backups.PeekBackup
    )

    private val wsRoutes: List<AbstractWebsocketRoute> = listOf(
        PGInstances.CreateInstance,

        Backups.CreateBackup,
        Backups.RestoreBackup
    )

    // Register all routes
    fun registerAll(routing: Routing) {
        // register test route
        routing.get("/test") {
            call.respond("The server works well")
        }
        routing.post("/test") {
            call.respond("The server works well")
        }

        postRoutes.forEach { it.registerPost(routing) }
        wsRoutes.forEach { it.registerWebsocket(routing) }
    }

    fun enable(route: String): Any? {
        postRoutes.firstOrNull { it.path == route } ?.let {
            it.disabled = false
            return HttpStatusCode.OK
        } ?: run {
            return HttpStatusCode.NotFound
        }
    }

    fun disable(route: String): Any? {
        postRoutes.firstOrNull { it.path == route } ?.let {
            it.disabled = true
            return HttpStatusCode.OK
        } ?: run {
            return HttpStatusCode.NotFound
        }
    }
}