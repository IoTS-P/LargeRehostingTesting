package org.iotsplab.akiba.managers

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object DefaultDatabaseOperator {

    val dbScope: ExecutorService = Executors.newSingleThreadExecutor()

    @Throws(SQLException::class)
    fun useDb(
        url: String,
        username: String? = null,
        password: String? = null,
        blocked: Boolean = true,
        doing: (Connection) -> Unit
    ) {
        val task = dbScope.submit {
            DriverManager.getConnection(url, username, password).use { conn ->
                doing(conn)
            }
        }
        if (blocked)
            task.get()
    }
}