package org.iotsplab.akiba.subcommands

import org.iotsplab.akiba.client.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.utils.SqlSource
import picocli.CommandLine

@CommandLine.Command(
    name = "instance-restore",
    mixinStandardHelpOptions = true,
    description = ["Restore to a backup of a new instance"]
)
object RestoreInstance: Runnable {
    @CommandLine.Option(
        names = ["-n", "--name"],
        description = ["Name of the instance"],
        required = true
    )
    lateinit var name: String

    @CommandLine.Option(
        names = ["-u", "--user"],
        description = ["User name of akiba"],
        required = true
    )
    lateinit var user: String

    @CommandLine.Option(
        names = ["-P", "--password"],
        description = ["Password of akiba user"]
    )
    var password: String? = null

    @CommandLine.Option(
        names = ["-H", "--host"],
        description = ["Host of the database daemon"]
    )
    var host: String = "127.0.0.1"

    @CommandLine.Option(
        names = ["-p", "--port"],
        description = ["Port of the database daemon"]
    )
    var port: Int = 31777

    @CommandLine.Option(
        names = ["-l", "--label"],
        description = ["Label or alias of the backup"],
        required = true
    )
    lateinit var label: String

    override fun run() {
        ConfigManager.config = Configs(sqlSource = SqlSource(serverIP = host, serverPort = port))
        DatabaseClient.urlHeader = "http://$host:$port"

        if (!DatabaseClient.testConnection())
            println("Cannot connect to database daemon")

        if (password == null) {
            print("Enter password:")
            password = System.console().readPassword().joinToString("")
        }

        DatabaseClient.login(user, password!!)
        DatabaseClient.restoreBackup(name, label)
    }
}