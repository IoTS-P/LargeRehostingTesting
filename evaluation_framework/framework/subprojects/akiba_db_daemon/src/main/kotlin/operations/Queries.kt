package org.iotsplab.akiba.dbDaemon.operations

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.HttpStatusCode
import org.iotsplab.akiba.dbDaemon.AbstractPostRoute
import org.iotsplab.akiba.dbDaemon.DatabaseDaemon.Companion.globalLogger
import org.iotsplab.akiba.dbDaemon.RouteContext
import java.sql.SQLException

object Queries {

    // ============================================================
    // -------------------------- ROUTES --------------------------
    // ============================================================

    object SelectIdInSQL : AbstractPostRoute() {
        override val path: String = "/get/id/sql"

        init {
            // Considering that receiving SQL commands from user is critically dangerous,
            // this route is disabled on default
            disabled = true
        }

        @Throws(SQLException::class)
        override suspend fun handle(ctx: RouteContext): Any? {
            val data = ctx.receive<Map<String, String>>()["sql"]
                ?: return HttpStatusCode.BadRequest.description("SQL command is not provided")
            val ids: MutableSet<Long> = mutableSetOf()
            val fa = try {
                fastAccess(ctx)
            } catch (e: FastAccessException) {
                return e.code
            }

            fa.session.useDb { conn ->
                globalLogger.debug("SELECT u.id from using_binaries u $data")
                conn.prepareStatement("SELECT u.id from using_binaries u $data").use { stmt ->
                    val rs = stmt.executeQuery()
                    while (rs.next())
                        ids.add(rs.getLong("id"))
                }
            }

            return ids.toList()
        }
    }

    object GetBinaryMetadata : AbstractPostRoute() {
        override val path: String = "/get/metadata"

        const val QUERY_BINARY_SQL: String = """
            SELECT u.original_path original_path, u.arch arch, u.format format, 
               u.compiler_spec compiler_spec, u.checksum checksum
            FROM binaries u
            WHERE u.id = ?
        """

        const val QUERY_PROCESSED_SQL: String = """
            SELECT u.original_path original_path, u.checksum checksum, u.load_properties load_properties
            FROM processed_binaries u
            WHERE u.id = ?
        """

        data class FileSegment(
            val oldOffset: Long,
            val newOffset: Long,
            val length: Long
        )

        @Throws(SQLException::class)
        override suspend fun handle(ctx: RouteContext): Any? {
            val id = ctx.receive<Long>()
            val metadata: MutableMap<String, Any?> = mutableMapOf()
            val fa = try {
                fastAccess(ctx)
            } catch (e: FastAccessException) {
                return e.code
            }

            fa.session.useDb { conn ->
                conn.prepareStatement(QUERY_BINARY_SQL).use { stmt ->
                    stmt.setLong(1, id)
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        metadata["id"] = id
                        metadata["originalPath"] = rs.getString("original_path")
                        metadata["arch"] = rs.getString("arch")
                        metadata["format"] = rs.getString("format")
                        metadata["compilerSpec"] = rs.getString("compiler_spec")
                        metadata["checksum"] = rs.getString("checksum")
                    }
                }
            }

            fa.session.useDb { conn ->
                conn.prepareStatement(QUERY_PROCESSED_SQL).use { stmt ->
                    stmt.setLong(1, id)
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        metadata["processedPath"] = rs.getString("original_path")
                        metadata["processedChecksum"] = rs.getString("checksum")
                        val loadProperties = rs.getString("load_properties")
                        metadata["loadProperties"] =
                            jacksonObjectMapper().readValue<List<FileSegment>>(loadProperties)
                    }
                }
            }

            return metadata
        }
    }

    /**
     * Get module data from module table
     *
     * Data format:
     *
     * ```json
     * {
     *   "results": [
     *     {"name": "flag", "type": "int4", "value": 123},
     *     {"name": "work_done", "type": "bool", "value": true}
     *   ]
     * }
     * ```
     */
    object GetModuleData : AbstractPostRoute() {
        override val path: String = "/get/module_data"

        val reservedColumns = listOf("id", "start_timestamp", "finish_timestamp", "execute_time")

        const val QUERY_SQL: String = """
            SELECT %s FROM %s WHERE id = %d;
        """
        const val QUERY_TYPE_SQL: String = """
            SELECT pg_typeof(%s) FROM (SELECT %s FROM %s LIMIT 1);
        """
        const val QUERY_ALL_COLUMN_TYPES_SQL: String = """
            SELECT column_name, data_type FROM information_schema.columns 
            WHERE table_schema = 'public' AND table_name = '%s';
        """

        data class QueryTarget (
            val tableName: String,
            // Considering that query data from many ids may cause the response too long
            // we only offer this query method which can only query on one id at a time
            val id: Long,
            val columns: List<String>?
        )

        data class ModuleData(val name: String, val type: String, val value: Any?)

        data class QueryResults (var results: MutableList<ModuleData> = mutableListOf())

        @Throws(SQLException::class)
        override suspend fun handle(ctx: RouteContext): Any? {
            val target = ctx.receive<QueryTarget>()
            val fa = try {
                fastAccess(ctx)
            } catch (e: FastAccessException) {
                return e.code
            }

            // TODO: The cost checking tables and columns every time is too expensive, how to optimize?
            if (!fa.session.existsTable(target.tableName) &&
                !fa.session.existsView(target.tableName))
                return HttpStatusCode.NotFound.description("Table or view ${target.tableName} not found")
            if (target.columns?.isNotEmpty() == true) {
                target.columns.forEach {
                    if (it in reservedColumns)
                        return HttpStatusCode.BadRequest.description("Column $it is reserved")
                    if (!fa.session.existsColumn(target.tableName, it))
                        return HttpStatusCode.NotFound.description("Column $it does not exist")
                }
            }

            val result = QueryResults()

            // column names, data types
            val columns: MutableMap<String, String> = mutableMapOf()
            target.columns ?.let {
                it.forEach { columnName ->
                    fa.session.useDb { conn ->
                        conn.prepareStatement(
                            QUERY_TYPE_SQL.format(columnName, columnName, target.tableName)).use { stmt ->
                            val rs = stmt.executeQuery()
                            while (rs.next())
                                columns[columnName] = rs.getString(1)
                        }
                    }
                }
            } ?: run {
                fa.session.useDb { conn ->
                    conn.prepareStatement(
                        QUERY_ALL_COLUMN_TYPES_SQL.format(target.tableName)).use { stmt ->
                        val rs = stmt.executeQuery()
                        while (rs.next()) {
                            val columnName = rs.getString("column_name")
                            if (columnName !in reservedColumns)
                                columns[columnName] = rs.getString("data_type")
                        }
                    }
                }
            }

            // Considering that we have checked the validity of the table and columns,
            // there is no safety issues to execute the joined query
            columns.forEach { column, type ->
                fa.session.useDb { conn ->
                    conn.prepareStatement(QUERY_SQL.format(
                        column,     // Columns
                        target.tableName,   // Table
                        target.id           // ID
                    )).use { stmt ->
                        val rs = stmt.executeQuery()
                        if (rs.next()) {
                            val data = mutableMapOf<String, Any?>()
                            for (i in 1..rs.metaData.columnCount)
                                data[rs.metaData.getColumnName(i)] = rs.getObject(i)
                            result.results.add(ModuleData(column, type, data[column]))
                        }
                    }
                }
            }

            return result
        }
    }
}