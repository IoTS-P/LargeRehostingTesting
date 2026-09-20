package org.iotsplab.akiba.subcommands

import org.iotsplab.akiba.client.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.utils.SqlSource
import picocli.CommandLine
import kotlin.system.exitProcess

@CommandLine.Command(
    name = "instance-backup",
    mixinStandardHelpOptions = true,
    description = ["Backup an instance"]
)
object BackupInstance: Runnable {
    @CommandLine.Option(
    names = ["-i", "--instance"],
    description = ["Name of the instance"],
    required = true
    )
    lateinit var instanceName: String

    @CommandLine.Option(
        names = ["-t", "--type"],
        description = ["Type of backup"],
        required = true
    )
    lateinit var backupType: String

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
        names = ["-a", "--alias"],
        description = ["Alias of the backup"]
    )
    var alias: String? = null

    @CommandLine.Option(
        names = ["-d", "--description"],
        description = ["Description of the backup"]
    )
    var description: String? = null

    override fun run() {
        ConfigManager.config = Configs(sqlSource = SqlSource(serverIP = host, serverPort = port))
        if (backupType !in listOf("full", "incr")) {
            println("Invalid option, -t / --type must be 'full' / 'incr'")
            exitProcess(1)
        }

        DatabaseClient.urlHeader = "http://$host:$port"

        if (!DatabaseClient.testConnection())
            println("Cannot connect to database daemon")

        if (password == null) {
            print("Enter password:")
            password = System.console().readPassword().joinToString("")
        }

        DatabaseClient.login(user, password!!)
        DatabaseClient.createBackup(backupType == "full", instanceName, alias, description)
    }
}