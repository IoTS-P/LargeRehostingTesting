package org.iotsplab.akiba.client.database

import org.iotsplab.akiba.managers.ConfigManager.config
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.postgresql.ds.PGSimpleDataSource

object LocalCacheDatabase {
    var cacheAvailable: Boolean = false
    var dataSource: PGSimpleDataSource? = null

    init {
        if (config.sqlSource.useLocalCache != null) {
            try {
                dataSource = PGSimpleDataSource().apply {
                    setUrl("jdbc:postgresql:///${config.sqlSource.useLocalCache}")
                    user = "akiba"
                }
                if (dataSource != null) {
                    dataSource!!.connection
                    cacheAvailable = true
                } else
                    cacheAvailable = false
            } catch (e: Exception) {
                globalLogger.error("Local cache database initialization failed: ${e.message} (${e.javaClass.name})")
                globalLogger.error(e.stackTraceToString())
            }
        }
    }

    const val CHECK_TABLE_EXISTS_COMMAND: String = """
        SELECT relname FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE relname = ? AND n.nspname = 'public' AND c.relkind = 'r'
    """

    private fun tableExists(tableName: String): Boolean {
        var ret = false
        dataSource!!.connection.use { conn ->
            conn.prepareStatement(CHECK_TABLE_EXISTS_COMMAND).use { stmt ->
                stmt.setString(1, tableName)
                val rs = stmt.executeQuery()
                ret = rs.next()
            }
        }
        return ret
    }

    @Throws(IllegalStateException::class)
    fun createTable(tableName: String, columns: Map<String, String>) {
        if (!cacheAvailable || tableExists(tableName))
            return

        var cmd = """
            CREATE TABLE $tableName (
                id                  integer REFERENCES binaries(id)
                                        ON DELETE CASCADE
                                        ON UPDATE CASCADE,
                start_timestamp     timestamptz,
                finish_timestamp    timestamptz,
                execute_time        interval,
                err_msg             text,

        """.trimIndent()
        columns.forEach { k, v ->
            cmd += ("    $k $v,\n")
        }

        cmd = cmd.removeSuffix(",\n")
        cmd += "\n);"

        dataSource!!.connection.use { conn -> conn.createStatement().use { it.executeUpdate(cmd) } }
        check (!tableExists(tableName)) { "Table $tableName creation failed" }
    }

    @Throws(IllegalStateException::class)
    fun updateData(tableName: String, id: Long, data: Map<String, Any?>) {
        if (!cacheAvailable)
            return
        // One update may not affect all columns, so we cannot imply the table structure by given data.
        // If the table does not exist, we must report an exception
        check(tableExists(tableName)) { "Table $tableName does not exist" }


    }
}