package org.iotsplab.akiba.dbDaemon.operations

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.iotsplab.akiba.dbDaemon.AbstractPostRoute
import org.iotsplab.akiba.dbDaemon.AbstractWebsocketRoute
import org.iotsplab.akiba.dbDaemon.DatabaseDaemon.Companion.config
import org.iotsplab.akiba.dbDaemon.DatabaseDaemon.Companion.globalLogger
import org.iotsplab.akiba.dbDaemon.RouteContext
import org.iotsplab.akiba.dbDaemon.dbutil.UserDatabaseSession
import org.iotsplab.akiba.dbDaemon.operations.PGInstances.CreateInstance.createLock
import org.iotsplab.akiba.dbDaemon.token.ResourceData
import org.iotsplab.akiba.dbDaemon.token.TimedResourceMap
import org.iotsplab.akiba.dbDaemon.token.TimedResourceMap.NotLockedException
import org.iotsplab.akiba.dbDaemon.token.TimedResourceMap.NotOwnedException
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.jvm.Throws

object PGInstances {

    data class InstanceMetadata (
        val port: Int,
        val owner: String,   // Also the name of the only database contained in this instance
        var logicalPrior: String?    // Last backup restored, used to build backup trees
    )

    const val MIN_PORT = 31800
    const val MAX_PORT = 31999

    // Major version of postgresql
    var pgVer: Int = -1
    val instanceRoot: Path = Path.of(config.instanceRoot).absolute()

    // instances created
    // key: instance name
    // value: necessary information of an instance
    lateinit var instances: MutableMap<String, InstanceMetadata>

    fun initialize() {
        val mapFile = File(config.instanceMapFile)
        instances = if (mapFile.exists()) {
            jacksonObjectMapper().readValue(mapFile)
        } else
            mutableMapOf()

        val getVer = ProcessBuilder("pg_config", "--version").start()
        getVer.waitFor()
        pgVer = getVer.inputStream.bufferedReader().readText()
            .substringAfter("PostgreSQL")
            .substringBefore(".")
            .trim()
            .toInt()
    }

    /**
     * User session map: Map storing tokens and relative resources.
     *
     * - Generic `K` (`UUID`): Token.
     * - Generic `V` (`UserSession`): Session of this token, containing the instance opened by this token.
     * - Generic `R` (`String`): Owner username
     */
    class UserSessionMap: TimedResourceMap<UUID, UserDatabaseSession, String>(
        Duration.ofSeconds(config.maxLockWaitingTime.toLong())
    ) {
        @Throws(NotLockedException::class, NotOwnedException::class)
        override fun unlock(resourceKey: UUID, owner: String) {
            val ownerInfo: Pair<ResourceData<String>, UserDatabaseSession?>
                = resourcesOwned[resourceKey] ?: throw NotLockedException()

            if (ownerInfo.first.owner != owner) throw NotOwnedException()
            else {
                val removed = resourcesOwned.remove(resourceKey)
                removed?.first?.timer?.get()?.cancel(false)
                // Release all sessions owned
                removed?.second?.tableSessions?.unlockAllOfOwner(owner)
            }
        }

        override fun renew(resourceKey: UUID, owner: String) {
            super.renew(resourceKey, owner)
            val ownerInfo = resourcesOwned[resourceKey] ?: throw NotLockedException()
            if (ownerInfo.first.owner != owner)   throw NotOwnedException()
            else
                ownerInfo.second?.tableSessions?.renewAll()
        }

        override fun onTimeout(resourceKey: UUID, owner: String) {
            // Avoid race condition of `renew` and `onTimeout`
            val entry = resourcesOwned[resourceKey] ?: return
            if (entry.first.owner != owner) return
            if (Instant.now().isBefore(entry.first.expireAt)) return

            val removed = resourcesOwned.remove(resourceKey)
            // Release all sessions owned
            removed?.second?.tableSessions?.unlockAllOfOwner(owner)
        }

        override fun releaseHook(key: UUID, resource: Pair<ResourceData<String>, UserDatabaseSession?>, owner: String) {
            resource.second?.tableSessions?.unlockAllOfOwner(owner)
        }

        fun unlockAllOfOwner(owner: String) {
            for (resource in resourcesOwned.keys) {
                if (resourcesOwned[resource]?.first?.owner == owner)
                    unlock(resource, owner)
            }
        }

        fun instanceIsInUse(instanceName: String): Boolean {
            for (resource in resourcesOwned.keys)
                if (resourcesOwned[resource]?.second?.instanceName == instanceName)
                    return true
            return false
        }
    }

    val tokens: ConcurrentHashMap<UUID, String> = ConcurrentHashMap()
    val tokenSessions: UserSessionMap = UserSessionMap()

    /**
     * Get a connection of a token to a table
     *
     * @param tableName Table name
     * @param token Client token
     * @return Connection and error message, there will be one and only one that is not null.
     *         There could be many error messages, including bad tableName (table name not found),
     *         bad token (token not found), unlocked table (table not locked),
     *         conflict lock (table locked by any other client)
     */
    fun getConnection(tableName: String, token: UUID): Pair<Connection?, HttpStatusCode?> {
        val user = tokens[token]
            ?: return null to HttpStatusCode.BadRequest.description("Token $token not found")
        try {
            val tokenSession = tokenSessions.getResource(
                token, user)
                ?: return null to HttpStatusCode.BadRequest.description("Token $token invalid")
            val tableSession = tokenSession.tableSessions.getResource(tableName, user)
                ?: return null to HttpStatusCode.BadRequest.description("Table $tableName invalid or unlocked")
            return tableSession to null
        } catch (_: NotLockedException) {
            return null to HttpStatusCode.Conflict.description("Table $tableName is not locked")
        } catch (_: NotOwnedException) {
            return null to HttpStatusCode.Conflict.description("Table $tableName is locked by other clients")
        }
    }

    @Throws(PGPortFullException::class, IllegalStateException::class)
    private fun createPsqlInstance(userName: String, instanceName: String): InstanceMetadata {
        val port = selectInstancePort()
        val process = ProcessBuilder("resources/initialize_pg_instance.sh")
        val env = process.environment()
        env["INSTANCE_NAME"] = instanceName
        env["INSTANCE_ROOT"] = instanceRoot.toString()
        env["BACKUP_ROOT"] = config.backupRoot
        env["USER_NAME"] = userName
        env["PORT"] = port.toString()
        val p = process.redirectErrorStream(true).start()
        p.waitFor()
        if (p.exitValue() != 0)
            throw IllegalStateException("Instance $instanceName initialization failed, output:\n" +
                p.inputStream.bufferedReader().readText()
            )
        return InstanceMetadata(port, userName, null)
    }

    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    private fun deletePsqlInstanceUnchecked(instanceName: String) {
        // We must check the instance name to avoid directory traversal
        // After all running "rm -rf /" is not a good idea :(
        if (instanceName.contains("/"))
            throw IllegalArgumentException("Instance name cannot contain /")
        val instancePath = instanceRoot.resolve(instanceName)
        if (instancePath.notExists())
            return
        val process = ProcessBuilder("sudo", "rm", "-rf", "$instanceRoot/$instanceName")
        val p = process.redirectErrorStream(true).start()
        p.waitFor()
        if (instancePath.exists())
            throw IllegalStateException(
                "Instance $instanceName deletion failed, output:\n" + p.inputStream.bufferedReader().readText())
    }

    @Throws(IllegalArgumentException::class)
    fun instanceIsOn(instanceName: String): Boolean {
        val instanceInfo = instances[instanceName]
            ?: throw IllegalArgumentException("Instance $instanceName not found")
        val process = ProcessBuilder(
            "pg_isready", "-h", "127.0.0.1", "-p", instanceInfo.port.toString(),
            "-U", instanceInfo.owner, "-d", instanceInfo.owner).start()
        process.waitFor()
        return process.exitValue() == 0
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun startInstance(instanceName: String) {
        val instanceInfo = instances[instanceName]
            ?: throw IllegalArgumentException("Instance $instanceName not found")
        if (instanceIsOn(instanceName))
            return

        val startProcess = ProcessBuilder("sudo", "-i", "-u", "postgres",
            "/usr/lib/postgresql/$pgVer/bin/pg_ctl", "-D", "$instanceRoot/$instanceName", "-w", "start").start()
        startProcess.waitFor()

        if (!instanceIsOn(instanceName))
            throw IllegalStateException("Instance $instanceName failed to start")
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun shutdownInstance(instanceName: String) {
        val instanceInfo = instances[instanceName]
            ?: throw IllegalArgumentException("Instance $instanceName not found")
        if (!instanceIsOn(instanceName))
            return

        val shutdownProcess = ProcessBuilder("sudo", "-i", "-u", "postgres",
            "/usr/lib/postgresql/$pgVer/bin/pg_ctl", "-D", "$instanceRoot/$instanceName", "-w", "stop").start()
        shutdownProcess.waitFor()

        if (instanceIsOn(instanceName))
            throw IllegalStateException("Instance $instanceName failed to stop")
    }

    @Throws(PGPortFullException::class)
    private fun selectInstancePort(): Int {
        for (port in MIN_PORT..MAX_PORT) {
            if (instances.values.any { it.port == port })
                continue
            return port
        }
        throw PGPortFullException()
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun shutdownAllInstances() {
        instances.forEach { (name, metadata) ->
            if (instanceIsOn(name))
                shutdownInstance(name)
        }
    }

    class PGPortFullException: Exception("PostgreSQL instance full, no local port available")

    // ============================================================
    // -------------------------- ROUTES --------------------------
    // ============================================================

    object ClientLogin: AbstractPostRoute() {
        override val path: String = "/instance/login"

        data class AuthData (
            val username: String,
            val password: String
        )

        // TODO: Add authentications
        override suspend fun handle(ctx: RouteContext): Any? {
            val authData = ctx.receive<AuthData>()
            val token = UUID.randomUUID()
            tokens[token] = authData.username
            return mapOf("token" to token)
        }
    }

    object ClientLogout: AbstractPostRoute() {
        override val path: String = "/instance/logout"

        override suspend fun handle(ctx: RouteContext): Any? {
            val token = UUID.fromString(ctx.header("Authorization"))
            val user = tokens[token]
                ?: return HttpStatusCode.BadRequest.description("Token $token not found")
            tokenSessions.unlockAllOfOwner(user)
            return HttpStatusCode.OK.description("Logout successful")
        }
    }

    private var creatingInstances: String? = null

    object CreateInstance: AbstractWebsocketRoute() {
        override val path: String = "/ws/instance/create"
        private val createLock: Mutex = Mutex()

        data class CreateData (
            val token: String,
            val instanceName: String
        )

        override suspend fun handle(session: DefaultWebSocketServerSession, frames: ReceiveChannel<Frame>) {
            val data = session.receiveDeserialized<CreateData>()
            val user = tokens[UUID.fromString(data.token)]
                ?: throw AkibaWebsocketException("Token ${data.token} not found")
            instances[data.instanceName]
                ?.let { throw AkibaWebsocketException("Instance ${data.instanceName} already exists") }

            try {
                createLock.withLock {
                    creatingInstances = data.instanceName
                    withContext(Dispatchers.IO) {
                        instances[data.instanceName] = createPsqlInstance(user, data.instanceName)
                    }
                    creatingInstances = null
                    globalLogger.info("PostgreSQL instance ${data.instanceName} created for user $user")
                }
            } catch (_: PGPortFullException) {
                throw AkibaWebsocketException("No more ports available")
            } catch (e: IllegalStateException) {
                throw AkibaWebsocketException("Instance ${data.instanceName} initialization failed:\n" +
                        "${e.message}")
            }

            session.sendSerialized(mapOf("msg" to "Instance ${data.instanceName} created"))
        }
    }

    /**
     * Connect to an instance
     *
     * If the instance doesn't exist, return error.
     * If the instance isn't started, start it first and then connect
     */
    object ConnectToInstance: AbstractPostRoute() {
        override val path: String = "/instance/connect"

        override suspend fun handle(ctx: RouteContext): Any? {
            val instanceName = ctx.receive<Map<String, String>>()["instanceName"]
                ?: return HttpStatusCode.BadRequest.description("Instance name not provided")
            val token = ctx.call.request.header(HttpHeaders.Authorization) ?.let { UUID.fromString(it) }
                ?: return HttpStatusCode.Unauthorized.description("No authorization token provided")
            val user = tokens[token]
                ?: return HttpStatusCode.BadRequest.description("Token $token not found")
            instances[instanceName]
                ?: return HttpStatusCode.BadRequest.description("Instance $instanceName not found")

            if (!instanceIsOn(instanceName))
                startInstance(instanceName)

            try {
                tokenSessions.lock(token, user, UserDatabaseSession(instanceName))
            } catch (_: TimedResourceMap.AlreadyLockedException) {
                return HttpStatusCode.Conflict.description("Instance $instanceName is already connected")
            } catch (_: NotOwnedException) {
                return HttpStatusCode.Conflict.description("Instance $instanceName is connected by others")
            }

            return HttpStatusCode.OK.description("Connected to instance $instanceName")
        }
    }

    /**
     * Disconnect from an instance
     *
     * Only disconnect, and don't shut down the instance
     */
    object DisconnectFromInstance: AbstractPostRoute() {
        override val path: String = "/instance/disconnect"

        override suspend fun handle(ctx: RouteContext): Any? {
            val instanceName = ctx.receive<Map<String, String>>()["instanceName"]
                ?: return HttpStatusCode.BadRequest.description("Instance name not provided")
            val token = ctx.call.request.header(HttpHeaders.Authorization) ?.let { UUID.fromString(it) }
                ?: return HttpStatusCode.Unauthorized.description("No authorization token provided")
            val user = tokens[token]
                ?: return HttpStatusCode.BadRequest.description("Token $token not found")
            try {
                tokenSessions.unlock(token, user)
            } catch (_: NotLockedException) {
                return HttpStatusCode.Conflict.description("Instance $instanceName is not locked")
            } catch (_: NotOwnedException) {
                return HttpStatusCode.Conflict.description("Instance $instanceName is used by other users")
            }
            return HttpStatusCode.OK.description("Disconnected from instance $instanceName")
        }
    }

    /**
     * Shutdown an instance
     *
     * If this token is using this instance, unlock it first. After that, if this instance is used by other users,
     * return conflict.
     */
    object ShutdownInstance: AbstractPostRoute() {
        override val path: String = "/instance/shutdown"

        override suspend fun handle(ctx: RouteContext): Any? {
            // Need to provide instance name
            val instanceName = ctx.receive<Map<String, String>>()["instanceName"]
                ?: return HttpStatusCode.BadRequest.description("Instance name not provided")
            // Need to provide a token
            val token = ctx.call.request.header(HttpHeaders.Authorization) ?.let { UUID.fromString(it) }
                ?: return HttpStatusCode.Unauthorized.description("No authorization token provided")
            // This token need to correspond to a user
            val user = tokens[token]
                ?: return HttpStatusCode.BadRequest.description("Token $token not found")
            // This instance need to exist
            instances[instanceName]
                ?: return HttpStatusCode.BadRequest.description("Instance $instanceName not found")
            // The owner of this instance needs to be the user
            if (instances[instanceName]!!.owner != user)
                return HttpStatusCode.BadRequest.description("You are not the owner of instance $instanceName")
            tokenSessions.unlock(token, user)
            if (tokenSessions.instanceIsInUse(instanceName))
                return HttpStatusCode.Conflict.description("Instance $instanceName is still in use")

            shutdownInstance(instanceName)
            return mapOf("token" to token)
        }
    }

    object DeleteInstance: AbstractPostRoute() {
        override val path: String = "/instance/delete"

        override suspend fun handle(ctx: RouteContext): Any? {
            // Need to provide instance name
            val instanceName = ctx.receive<Map<String, String>>()["instanceName"]
                ?: return HttpStatusCode.BadRequest.description("Instance name not provided")
            // Need to provide a token
            val token = ctx.call.request.header(HttpHeaders.Authorization) ?.let { UUID.fromString(it) }
                ?: return HttpStatusCode.Unauthorized.description("No authorization token provided")
            // This token need to correspond to a user
            val user = tokens[token]
                ?: return HttpStatusCode.BadRequest.description("Token $token not found")
            // This instance need to exist
            instances[instanceName]
                ?: return HttpStatusCode.BadRequest.description("Instance $instanceName not found")
            // The owner of this instance needs to be the user
            if (instances[instanceName]!!.owner != user)
                return HttpStatusCode.BadRequest.description("You are not the owner of instance $instanceName")
            // This instance need to be stopped or unused
            if (tokenSessions.instanceIsInUse(instanceName))
                return HttpStatusCode.Conflict.description("Instance $instanceName is still in use")
            // This instance need to be shut down before deletion
            if (instanceIsOn(instanceName))
                shutdownInstance(instanceName)

            try {
                deletePsqlInstanceUnchecked(instanceName)
            } catch (e: Exception) {
                return HttpStatusCode.Conflict.description("Instance $instanceName deletion failed:\n" +
                        "${e.message}")
            }

            instances.remove(instanceName)
            return HttpStatusCode.OK.description("Instance $instanceName deleted")
        }
    }
}