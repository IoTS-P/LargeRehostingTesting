package org.iotsplab.akiba.dbDaemon.operations

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import org.iotsplab.akiba.dbDaemon.AbstractPostRoute
import org.iotsplab.akiba.dbDaemon.RouteContext
import org.iotsplab.akiba.dbDaemon.dbutil.UserDatabaseSession
import org.iotsplab.akiba.dbDaemon.operations.PGInstances.tokens
import java.sql.SQLException
import java.util.UUID

object Insertions {
    const val DATABASE_INSERT_BINARY_COMMAND = """
        INSERT INTO binaries 
        (original_path, checksum, size, arch, format, compiler_spec) 
        VALUES (?, ?, ?, ?, ?, ?)
    """
    const val DATABASE_INSERT_PROCESSED_BINARY_COMMAND = """
        INSERT INTO processed_binaries 
        (original_path, checksum, size, arch, format, compiler_spec, load_properties, id) 
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """

    // ============================================================
    // -------------------------- ROUTES --------------------------
    // ============================================================

    object MD5Exists : AbstractPostRoute() {
        override val path: String = "/insert/check_md5"

        override suspend fun handle(ctx: RouteContext): Any? {
            val md5 = ctx.receive<String>()
            val fa = try {
                fastAccess(ctx)
            } catch (e: FastAccessException) {
                return e.code
            }

            return fa.session.md5List.contains(md5)
        }
    }

    object InsertBinary : AbstractPostRoute() {
        data class InsertData(
            val originalPath: String,
            val processedPath: String? = null,
            val checksum: String,
            val processedChecksum: String? = null,
            val size: Long,
            val processedSize: Long = -1,
            val loadProperties: String? = null,
            val arch: String,
            val format: String,
            val compilerSpec: String,
        )

        override val path: String = "/insert/insert_bin"

        @Throws(SQLException::class)
        override suspend fun handle(ctx: RouteContext): Any? {
            val data = ctx.receive<InsertData>()
            val fa = try {
                fastAccess(ctx)
            } catch (e: FastAccessException) {
                return e.code
            }

            fa.session.useDb { conn ->
                conn.prepareStatement(DATABASE_INSERT_BINARY_COMMAND).use {
                    it.setString(1, data.originalPath)
                    it.setString(2, data.checksum)
                    it.setLong(3, data.size)
                    it.setString(4, data.arch)
                    it.setString(5, data.format)
                    it.setString(6, data.compilerSpec)
                    it.executeUpdate()
                }
            }
            fa.session.md5List.add(data.checksum)

            val insertedId = fa.session.getLastInsertId()

            if (data.processedPath != null && data.processedChecksum != null && data.loadProperties != null) {
                fa.session.useDb { conn ->
                    conn.prepareStatement(DATABASE_INSERT_PROCESSED_BINARY_COMMAND).use {
                        it.setString(1, data.processedPath)
                        it.setString(2, data.processedChecksum)
                        it.setLong(3, data.processedSize)
                        it.setString(4, data.arch)
                        it.setString(5, data.format)
                        it.setString(6, data.compilerSpec)
                        val obj = org.postgresql.util.PGobject().apply {
                            type = "jsonb"
                            this.value = data.loadProperties
                        }
                        it.setObject(7, obj)
                        it.setLong(8, insertedId)
                        it.executeUpdate()
                    }
                }
                fa.session.md5List.add(data.processedChecksum)
            } else if (!(data.processedPath == null && data.processedChecksum == null && data.loadProperties == null))
                return HttpStatusCode.BadRequest.description("Invalid processed binary data")

            return insertedId
        }
    }
}