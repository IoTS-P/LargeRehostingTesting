package org.iotsplab.akiba.tests.DatabaseTests

import org.iotsplab.akiba.client.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager.KEY_SEPARATOR
import org.iotsplab.akiba.managers.ConfigManager.config
import org.iotsplab.akiba.managers.ConfigManager.loadGlobalConfig
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test

object DatabaseTest1 {
    @Test
    fun test() {
        val mainConfPath: Path = Path.of("/tmp/akiba_test_conf.json")
        mainConfPath.writeText(testConfig)

        config = loadGlobalConfig(mainConfPath.absolutePathString() + KEY_SEPARATOR + "/main")

        try {
            DatabaseClient.login("test", "test")
            DatabaseClient.createInstance("test_instance")

            DatabaseClient.connectToInstance("test_instance")

            DatabaseClient.insertBinary(DatabaseClient.InsertData(
                "test/original.bin",
                "test/processed.bin",
                "00000000000000000000000000000000",
                "11111111111111111111111111111111",
                12345678,
                1234567,
                "[]",
                "n/a",
                "n/a",
                "n/a"
            ))
            println(DatabaseClient.getMetadata(1))

            DatabaseClient.createModuleTable("test_table", mapOf(
                "name" to "text",
                "age" to "integer",
            ))
            DatabaseClient.tableLock("test_table")
            DatabaseClient.startTask("test_table", 1)
            DatabaseClient.updateData("test_table", 1, mapOf(
                "name" to "test_name",
                "age" to 18
            ))
            DatabaseClient.finishTask("test_table", 1)
            DatabaseClient.tableUnlock("test_table")

            println(DatabaseClient.getModuleData(1, "test_table"))

            DatabaseClient.disconnectToInstance("test_instance")
            DatabaseClient.deleteInstance("test_instance")
            DatabaseClient.logout()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val testConfig = """
        {
          "metadata": {
            "description": "Akiba Test"
          },
          "main": {
            "username": "akiba",
            "password": "akiba",
            "usingInstance": "firmware_dataset",
            "general": {
              "binariesRoot": "/tmp/binaries",
              "importRoot": "./src/test/resources/binary_examples",
              "processor": "n/a",
              "autoAnalysisTimeout": 600,
              "threads": 1
            },
            "withGhidraProject": {
              "projectRoot": "./ghidra_projects",
              "name": "akiba_test_1",
              "mode": "new",
              "forkTo": null,
              "continueLog": null,
              "overwriteLog": true,
              "saveProject": true,
              "noCreateProgram": false
            },
            "sqlSource": {
              "serverIP": "127.0.0.1",
              "serverPort": "31777",
              "useSnapshot": "current",
              "constraint": "",
              "disableUpdate": false
            },
            "dbImports": [],
            "tasks": []
          }
        }
    """.trimIndent()
}