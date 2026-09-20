package org.iotsplab.akiba.dbDaemon

data class Configs (
    // Database log level
    val consoleLogLevel: String = "INFO",
    val fileLogLevel: String = "DEBUG",
    // Server port
    val serverPort: Int = 31777,
    // Maximum time to wait for a lock (seconds), default at 1 hour.
    // If the server didn't receive a response from a client within this time, it will release the lock
    val maxLockWaitingTime: Int = 3600,
    // Maximum time to wait for a token (seconds), default at 2 hours.
    // If the server didn't receive a response from a client within this time, it will release the token
    val maxTokenWaitingTime: Int = 7200,

    // Postgres bin directory, the directory that contains pg_basebackup, pg_dump, pg_restore, etc
    val pgBinDirectory: String = "/usr/bin",
    // Backup directory
    val backupRoot: String = "backups",
    // Postgres instance directory
    val instanceRoot: String = "psql_instances",
    // The JSON file containing local instance information
    // NOTE: You CANNOT specify '~/.akiba/instances.json' which will be regarded as a relative path!
    val instanceMapFile: String = System.getProperty("user.home") + "/.akiba/instances.json"
)