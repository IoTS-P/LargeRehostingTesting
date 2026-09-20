package org.iotsplab.akiba.dbDaemon.operations

import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import org.iotsplab.akiba.dbDaemon.AbstractPostRoute
import org.iotsplab.akiba.dbDaemon.AbstractWebsocketRoute
import org.iotsplab.akiba.dbDaemon.DatabaseDaemon.Companion.globalLogger
import org.iotsplab.akiba.dbDaemon.RouteContext
import org.iotsplab.akiba.dbDaemon.dbutil.BackupManager
import org.iotsplab.akiba.dbDaemon.operations.PGInstances.tokens
import java.util.UUID

object Backups {

    data class BackupMetadata (
        val isFull: Boolean,
        val token: String,
        val instance: String,
        val alias: String?,
        val description: String?
    )

    object CreateBackup: AbstractWebsocketRoute() {
        override val path: String = "/ws/backup/create"
        private val scope = CoroutineScope(Dispatchers.IO)

        @Throws(AkibaWebsocketException::class)
        override suspend fun handle(session: DefaultWebSocketServerSession, frames: ReceiveChannel<Frame>) {
            val data = session.receiveDeserialized<BackupMetadata>()
            val user = tokens[UUID.fromString(data.token)]
                ?: throw AkibaWebsocketException("Token ${data.token} not found")

            PGInstances.instances.keys.find { it == data.instance }
                ?.let {
                    if (PGInstances.instances[it]?.owner != user)
                        throw AkibaWebsocketException("Instance not owned")
                } ?: throw AkibaWebsocketException("Instance not found")

            if (PGInstances.tokenSessions.instanceIsInUse(data.instance))
                throw AkibaWebsocketException(
                        "Instance is in use. If you just updated in this database, please release first")

            // Backup and update backup data in database
            val backupLabel = try {
                withContext(Dispatchers.IO) {
                    BackupManager(data.instance).backup(
                        data.alias, data.description, data.isFull)
                }
            } catch (e: Exception) {
                globalLogger.error("Failed to backup FULL: ${e.message}")
                e.printStackTrace()
                throw AkibaWebsocketException("Failed to backup instance")
            }

            session.sendSerialized(mapOf("label" to backupLabel))
        }
    }

    object PeekBackup: AbstractPostRoute() {
        override val path: String = "/backup/peek"

        override suspend fun handle(ctx: RouteContext): Any? {
            val token = ctx.call.request.header(HttpHeaders.Authorization)
                ?: return HttpStatusCode.Unauthorized.description("No authorization token provided")
            val user = tokens[UUID.fromString(token)]
                ?: return HttpStatusCode.BadRequest.description("Token $token not found")
            val instance = ctx.receive<String>()

            PGInstances.instances.keys.find { it == instance }
                ?.let {
                    if (PGInstances.instances[it]?.owner != user)
                        return HttpStatusCode.BadRequest.description("Instance not owned")
                } ?: return HttpStatusCode.BadRequest.description("Instance not found")

            return jacksonObjectMapper()
                .registerModule(
                    SimpleModule().addSerializer(
                        BackupManager.BackupNode::class.java,
                        BackupManager.BackupNodeSerializer
                    ))
                .writeValueAsString(BackupManager(instance).getLogicalData())
        }
    }

    object RestoreBackup: AbstractWebsocketRoute() {
        override val path: String = "/ws/backup/restore"

        data class TransferTo(val token: String, val instance: String, val aliasOrLabel: String)

        override suspend fun handle(session: DefaultWebSocketServerSession, frames: ReceiveChannel<Frame>) {
            val data = jacksonObjectMapper().readValue<TransferTo>(frames.receive().data.decodeToString())
            val user = tokens[UUID.fromString(data.token)]
                ?: throw AkibaWebsocketException("Token ${data.token} not found")

            PGInstances.instances.keys.find { it == data.instance }
                ?.let {
                    if (PGInstances.instances[it]?.owner != user)
                        throw AkibaWebsocketException("Instance not owned")
                } ?: throw AkibaWebsocketException("Instance not found")

            // Restore backup
            try {
                withContext(Dispatchers.IO) {
                    BackupManager(data.instance).restoreBackup(data.aliasOrLabel)
                }
            } catch (e: Exception) {
                globalLogger.error("Failed to restore backup: ${e.message}")
                throw AkibaWebsocketException("Failed to restore backup: ${e.message}")
            }

            session.sendSerialized(mapOf("msg" to "Backup restoration completed"))
        }
    }
}