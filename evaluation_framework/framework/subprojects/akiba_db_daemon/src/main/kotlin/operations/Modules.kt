package org.iotsplab.akiba.dbDaemon.operations

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import org.iotsplab.akiba.dbDaemon.AbstractPostRoute
import org.iotsplab.akiba.dbDaemon.DatabaseDaemon.Companion.globalLogger
import org.iotsplab.akiba.dbDaemon.RouteContext
import org.iotsplab.akiba.dbDaemon.dbutil.UserDatabaseSession
import org.iotsplab.akiba.dbDaemon.dbutil.defaultDbTypeAdapters
import org.iotsplab.akiba.dbDaemon.operations.PGInstances.tokens
import org.iotsplab.akiba.dbDaemon.token.TimedResourceMap
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.use

object Modules {

    @Throws(SQLException::class)
    private fun entryExists(connection: Connection, tableName: String, id: Long): Boolean {
        var exists = false
        connection.createStatement().use {
            val result = it.executeQuery("SELECT * FROM $tableName WHERE id = $id")
            exists = result.next()
        }
        return exists
    }

    @Throws(SQLException::class)
    private fun insertEntryIfNotExists(connection: Connection, tableName: String, id: Long) {
        if (!entryExists(connection, tableName, id)) {
            connection.createStatement().use { stmt ->
                val cmd = """
                    INSERT INTO $tableName (id) VALUES ($id)
                """.trimIndent()
                stmt.executeUpdate(cmd)
            }
        }
    }

    // ============================================================
    // -------------------------- ROUTES --------------------------
    // ============================================================

    object CreateModuleTable: AbstractPostRoute() {
        override val path: String = "/module/create_table"

        val supportedDatatype: Set<String> = setOf(
            "integer",
            "bigint",
            "double precision",
            "text",
            "timestamptz",
            "interval",
            "boolean",
            "jsonb",
            "bytea"
        )

        val reservedColumns: Set<String> = setOf(
            "id",
            "start_timestamp",
            "finish_timestamp",
            "execute_time",
            "err_msg"
        )

        data class TableFormat (
            val name: String,
            val columns: Map<String, String>
        )

        // TODO: Use schema or other way to strengthen security level?
        override suspend fun handle(ctx: RouteContext): Any? {
            val tableFormat = ctx.receive<TableFormat>()
            val fa = try {
                fastAccess(ctx)
            } catch (e: FastAccessException) {
                return e.code
            }

            try {
                checkFormatSafety(tableFormat)
            } catch (e: IllegalArgumentException) {
                return HttpStatusCode.BadRequest.description(e.message ?: "Request data invalid")
            }

            if (fa.session.existsTable(tableFormat.name) ||
                fa.session.existsView(tableFormat.name)) {
                return HttpStatusCode.Conflict.description("Table ${tableFormat.name} already exists")
            }

            var cmd = """
                CREATE TABLE ${tableFormat.name} (
                    id                  integer REFERENCES binaries(id)
                                            ON DELETE CASCADE
                                            ON UPDATE CASCADE,
                    start_timestamp     timestamptz,
                    finish_timestamp    timestamptz,
                    execute_time        interval,
                    err_msg             text,

            """.trimIndent()
            tableFormat.columns.forEach { k, v ->
                cmd += ("    $k $v,\n")
            }

            cmd = cmd.removeSuffix(",\n")
            cmd += "\n);"

            fa.session.useDb { conn -> conn.createStatement().use { it.executeUpdate(cmd) } }

            if (!fa.session.existsTable("binaries"))
                return HttpStatusCode.ExpectationFailed.description("Table ${tableFormat.name} creation failed")

            return HttpStatusCode.OK.description("Table ${tableFormat.name} created")
        }

        @Throws(IllegalArgumentException::class)
        private fun checkFormatSafety(tableFormat: TableFormat) {
            if (!tableFormat.name.matches(Regex("^[a-z][a-z0-9_]{0,62}$")))
                throw IllegalArgumentException("Table name '${tableFormat.name}' is not valid")
            for ((columnName, columnType) in tableFormat.columns) {
                if (!columnName.lowercase().matches(Regex("^[a-z][a-z0-9_]{0,62}$")))
                    throw IllegalArgumentException("Column name '$columnName' is not valid")
                if (!supportedDatatype.contains(columnType.lowercase()))
                    throw IllegalArgumentException(
                        "Column type '$columnType' not supported. " +
                            "Supported data type: ${supportedDatatype.joinToString(",")}"
                    )
                if (reservedColumns.contains(columnName))
                    throw IllegalArgumentException("Column name '$columnName' is reserved")
            }
        }
    }

    object CreateView: AbstractPostRoute() {
        override val path: String = "/module/create_view"

        data class ViewRequest (
            val viewName: String,
            val viewSQL: String,
            val overwrite: Boolean = false
        )

        override suspend fun handle(ctx: RouteContext): Any? {
            val req = ctx.receive<ViewRequest>()
            val fa = try {
                fastAccess(ctx)
            } catch (e: FastAccessException) {
                return e.code
            }

            if (fa.session.existsView(req.viewName) && !req.overwrite)
                return HttpStatusCode.Conflict.description("View ${req.viewName} already exists")

            val cmd = "CREATE VIEW ${req.viewName} AS ${req.viewSQL}"

            fa.session.useDb { conn -> conn.createStatement().use { it.executeUpdate(cmd) } }

            return HttpStatusCode.OK.description("View ${req.viewName} created")
        }
    }

    object TableLock: AbstractPostRoute() {
        override val path: String = "/module/lock_table"

        val operationLock: ReentrantLock = ReentrantLock()

        const val LOCK_TABLE_SQL = """
            SELECT pg_advisory_lock(hashtext(?));
        """

        override suspend fun handle(ctx: RouteContext): Any? {
            val tableName = ctx.receive<Map<String, String>>()["tableName"]
                ?: return HttpStatusCode.BadRequest.description("No table name provided")
            val fa = try {
                fastAccess(ctx)
            } catch (e: FastAccessException) {
                return e.code
            }

            if (!fa.session.existsTable(tableName))
                return HttpStatusCode.BadRequest.description("Table $tableName not found")

            try {
                // Try to get a connection that should not exist
                PGInstances.getConnection(tableName, fa.token).let { (conn, code) ->
                    // This table is already locked by this user
                    if (conn != null)
                        throw TimedResourceMap.AlreadyLockedException()
                    // This table is locked by another user
                    else if (code!!.description.contains("locked by other clients"))
                        throw TimedResourceMap.NotOwnedException()
                }

                if (!operationLock.tryLock(3, TimeUnit.SECONDS))
                    return HttpStatusCode.Locked.description("Table $tableName is locked by other clients")

                // Get a new connection from data source in the token session
                val connection = PGInstances.tokenSessions.getResource(fa.token, fa.user)!!
                    .dataSource.connection
                // Execute lock SQL to lock the table in PostgreSQL level
                // Even if akiba database daemon has bugs/vulnerabilities, the table will still be locked
                connection.prepareStatement(LOCK_TABLE_SQL).use {
                    it.setString(1, tableName)
                    it.execute()
                }
                // Add this connection into table sessions and lock this table in akiba level
                PGInstances.tokenSessions
                    .getResource(fa.token, fa.user)!!.tableSessions
                    .lock(tableName, fa.user, connection)
                
                operationLock.unlock()

            } catch (_: TimedResourceMap.AlreadyLockedException) {
                return HttpStatusCode.Conflict.description("Table $tableName is already locked")
            } catch (_: TimedResourceMap.NotOwnedException) {
                return HttpStatusCode.Conflict.description("Table $tableName is locked by other clients")
            }

            return HttpStatusCode.OK.description("Table $tableName locked")
        }
    }

    object TableUnlock: AbstractPostRoute() {
        override val path: String = "/module/unlock_table"

        const val UNLOCK_TABLE_SQL = """
            SELECT pg_advisory_unlock(hashtext(?));
        """

        override suspend fun handle(ctx: RouteContext): Any? {
            val tableName = ctx.receive<Map<String, String>>()["tableName"]
                ?: return HttpStatusCode.BadRequest.description("No table name provided")
            val fa = try {
                fastAccess(ctx)
            } catch (e: FastAccessException) {
                return e.code
            }

            try {
                val connection = PGInstances.getConnection(tableName, fa.token).let {
                    if (it.second != null)
                        return it.second
                    else
                        return@let it.first!!
                }
                connection.prepareStatement(UNLOCK_TABLE_SQL).use {
                    it.setString(1, tableName)
                    it.execute()
                }

                PGInstances.tokenSessions
                    .getResource(fa.token, fa.user)!!.tableSessions
                    .unlock(tableName, fa.user)
            } catch (_: TimedResourceMap.NotLockedException) {
                return HttpStatusCode.Conflict.description("Table $tableName is not locked")
            } catch (_: TimedResourceMap.NotOwnedException) {
                return HttpStatusCode.Conflict.description("Table $tableName is locked by other clients")
            }

            return HttpStatusCode.OK.description("Table $tableName unlocked")
        }
    }

    object UpdateData: AbstractPostRoute() {
        override val path: String = "/module/update"

        private val reservedColumns = setOf(
            "id",
            "start_timestamp",
            "finish_timestamp",
            "execute_time"
        )

        data class UpdateRequest (
            val tableName: String,
            val id: Long,
            val data: Map<String, Any?>
        )

        @Throws(SQLException::class)
        override suspend fun handle(ctx: RouteContext): Any? {
            val req = ctx.receive<UpdateRequest>()
            val fa = try {
                fastAccess(ctx)
            } catch (e: FastAccessException) {
                return e.code
            }

            val connection = PGInstances.getConnection(req.tableName, fa.token) .let {
                it.second ?.let { httpCode -> return httpCode }
                it.first!!
            }

            if (req.data.keys.any { it in reservedColumns })
                return HttpStatusCode.BadRequest.description("Request contains reserved column(s)")

            fa.session.tableSessions.renew(req.tableName, fa.user)
            PGInstances.tokenSessions.renew(fa.token, fa.user)

            insertEntryIfNotExists(connection, req.tableName, req.id)

            req.data.forEach { column, value ->
                // TODO: Need to check the data type every time we update, how to optimize?
                val dataType = fa.session.getDataType(req.tableName, column)
                val adapter = defaultDbTypeAdapters[dataType]
                    ?: throw IllegalArgumentException("Unsupported data type for column $column: $dataType")

                connection.prepareStatement(
                    """
                        UPDATE ${req.tableName} SET $column = ? WHERE id = ?
                    """.trimIndent()
                ).use {
                    adapter.set(it, 1, value)
                    it.setLong(2, req.id)
                    it.executeUpdate()
                }
            }

            return HttpStatusCode.OK.description("Data updated")
        }
    }

    object StartTask: AbstractPostRoute() {
        override val path: String = "/module/start"

        data class StartRequest (
            val tableName: String,
            val id: Long
        )

        // TODO: Should we record tasks undone dynamically?
        override suspend fun handle(ctx: RouteContext): Any? {
            val req = ctx.receive<StartRequest>()
            val fa = try {
                fastAccess(ctx)
            } catch (e: FastAccessException) {
                return e.code
            }

            val connection = PGInstances.getConnection(req.tableName, fa.token) .let {
                it.second ?.let { httpCode -> return httpCode }
                it.first!!
            }

            insertEntryIfNotExists(connection, req.tableName, req.id)

            connection.prepareStatement(
                """
                    UPDATE ${req.tableName} 
                    SET start_timestamp = now(), finish_timestamp = NULL, execute_time = NULL, err_msg = NULL
                    WHERE id = ?
                """.trimIndent()
            ).use {
                it.setLong(1, req.id)
                it.executeUpdate()
            }

            return HttpStatusCode.OK.description("Task started")
        }
    }

    object FinishTask: AbstractPostRoute() {
        override val path: String = "/module/finish"

        override suspend fun handle(ctx: RouteContext): Any? {
            val req = ctx.receive<StartTask.StartRequest>()
            val fa = try {
                fastAccess(ctx)
            } catch (e: FastAccessException) {
                return e.code
            }

            val connection: Connection = PGInstances.getConnection(req.tableName, fa.token) .let {
                it.second ?.let { httpCode -> return httpCode }
                it.first!!
            }

            if (!entryExists(connection, req.tableName, req.id))
                return HttpStatusCode.BadRequest.description("Entry ${req.id} does not exist")

            connection.prepareStatement(
                """
                    UPDATE ${req.tableName} 
                    SET finish_timestamp = now(), execute_time = now() - start_timestamp 
                    WHERE id = ?
                """.trimIndent()
            ).use {
                it.setLong(1, req.id)
                it.executeUpdate()
            }

            return HttpStatusCode.OK.description("Task finished")
        }
    }
}