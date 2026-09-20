package org.iotsplab.akiba.dbDaemon.dbutil

import org.iotsplab.akiba.dbDaemon.DatabaseDaemon
import org.iotsplab.akiba.dbDaemon.operations.PGInstances
import org.iotsplab.akiba.dbDaemon.token.ResourceData
import org.iotsplab.akiba.dbDaemon.token.TimedResourceMap
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.use

class UserDatabaseSession (val instanceName: String) {
    // PostgreSQL data source
    val dataSource: PGSimpleDataSource
    // Opened tables
    val tableSessions: TableSessionMap = TableSessionMap()

    val dbScope: ExecutorService = Executors.newSingleThreadExecutor()

    val md5List: MutableSet<String> = mutableSetOf()

    val snapshotManager: BackupManager

    init {
        val instanceMetadata: PGInstances.InstanceMetadata = PGInstances.instances[instanceName]
            ?: throw IllegalArgumentException("Instance $instanceName not found")

        val url = "jdbc:postgresql://127.0.0.1:${instanceMetadata.port}/$instanceName"
        this@UserDatabaseSession.dataSource = PGSimpleDataSource().apply {
            setURL(url)
            user = instanceMetadata.owner
            password = null
        }
        dataSource.connection?.let { DatabaseDaemon.Companion.globalLogger.info("Local database connected") }
            ?: run {
                DatabaseDaemon.Companion.globalLogger.error("Local database connection failed: $instanceName")
                throw SQLException("Database connection failed")
            }

        useDb { conn ->
            conn.createStatement().use {
                val rs = it.executeQuery(DATABASE_GET_ALL_MD5_COMMAND)
                while (rs.next()) {
                    md5List.add(rs.getString(1))
                }
            }
        }

        snapshotManager = BackupManager(instanceName)
    }

    /**
     * Table session map: Map storing connections of tables of a user
     *
     * - Generic `K` (`String`) = Table name
     * - Generic `V` (`Connection`) = Table connection
     * - Generic `R` (`String`) = Owner username
     *
     * Sessions of different tokens are separated, so the owner is all the same
     */
    class TableSessionMap: TimedResourceMap<String, Connection, String>(
        Duration.ofSeconds(DatabaseDaemon.Companion.config.maxLockWaitingTime.toLong())
    ) {
        override fun releaseHook(key: String, resource: Pair<ResourceData<String>, Connection?>, owner: String) {
            resource.second!!.close()
        }

        fun unlockAllOfOwner(owner: String) {
            for (resource in resourcesOwned.keys) {
                if (resourcesOwned[resource]?.first?.owner == owner)
                    unlock(resource, owner)
            }
        }

        fun renewAll() {
            resourcesOwned.keys.forEach {
                renew(it, resourcesOwned[it]!!.first.owner)
            }
        }
    }

    @Throws(SQLException::class)
    fun useDb (
        blocked: Boolean = true,
        doing: (Connection) -> Unit
    ) {
        val task = dbScope.submit {
            dataSource.connection.use { conn ->
                doing(conn)
            }
        }
        if (blocked)
            task.get()
    }

    @Throws(SQLException::class)
    fun existsTable(tableName: String): Boolean {
        var ret = false
        useDb { conn ->
            conn.prepareStatement(CHECK_TABLE_EXISTS_COMMAND).use { stmt ->
                stmt.setString(1, tableName)
                val rs = stmt.executeQuery()
                ret = rs.next()
            }
        }
        return ret
    }

    @Throws(SQLException::class)
    fun existsView(viewName: String): Boolean {
        var ret = false
        useDb { conn ->
            conn.prepareStatement(CHECK_VIEW_EXISTS_COMMAND).use { stmt ->
                stmt.setString(1, viewName)
                val rs = stmt.executeQuery()
                ret = rs.next()
            }
        }
        return ret
    }

    @Throws(SQLException::class)
    fun existsColumn(tableName: String, columnName: String): Boolean {
        var ret = false
        useDb { conn ->
            conn.prepareStatement(CHECK_COLUMN_EXISTS_COMMAND).use {
                it.setString(1, tableName)
                it.setString(2, columnName)
                it.executeQuery().use { rs ->
                    ret = rs.next()
                }
            }
        }
        return ret
    }

    @Throws(SQLException::class)
    fun getDataType(table: String, column: String): String? {
        var ret: String? = null
        useDb { conn ->
            conn.prepareStatement(GET_DATA_TYPE_COMMAND).use {
                it.setString(1, table)
                it.setString(2, column)
                val rs = it.executeQuery()
                if (rs.next()) {
                    assert(rs.getString(1) == column)
                    ret = rs.getString(2)
                }
            }
        }
        return ret
    }

    @Throws(SQLException::class)
    fun getLastInsertId(): Long {
        var ret: Long = -1
        useDb { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT max(id) FROM binaries;").use { rs ->
                    if (rs.next()) {
                        ret = rs.getInt(1).toLong()
                    } else
                        throw SQLException("error while obtaining last inserted id")
                }
            }
        }
        return ret
    }

    companion object {
        const val CHECK_TABLE_EXISTS_COMMAND: String = """
            SELECT relname FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE relname = ? AND n.nspname = 'public' AND c.relkind = 'r'
        """

        const val CHECK_VIEW_EXISTS_COMMAND: String = """
            SELECT relname FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = ? AND n.nspname = 'public' AND c.relkind = 'v'
        """

        const val CHECK_COLUMN_EXISTS_COMMAND: String = """
            SELECT column_name,
                   data_type
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name   = ?
              AND column_name  = ?
            ORDER BY ordinal_position;
        """


        const val GET_DATA_TYPE_COMMAND: String = """
            SELECT column_name,
                   data_type
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name   = ?
              AND column_name  = ?
            ORDER BY ordinal_position;
        """

        const val DATABASE_GET_ALL_MD5_COMMAND = """
            SELECT checksum FROM binaries UNION SELECT checksum FROM processed_binaries
        """
    }
}