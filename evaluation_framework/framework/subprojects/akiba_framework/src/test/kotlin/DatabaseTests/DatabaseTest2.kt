package org.iotsplab.akiba.tests.DatabaseTests

import org.iotsplab.akiba.client.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test

object DatabaseTest2 {
    @Test
    fun test() {
        val mainConfPath: Path = Path.of("/tmp/akiba_test_conf.json")
        mainConfPath.writeText(testConfig)

        ConfigManager.config =
            ConfigManager.loadGlobalConfig(mainConfPath.absolutePathString() + ConfigManager.KEY_SEPARATOR + "/main")

        try {
            DatabaseClient.login("test", "test")
            DatabaseClient.createInstance("test_instance")
            DatabaseClient.connectToInstance("test_instance")

            DatabaseClient.insertBinary(
                DatabaseClient.InsertData(
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
            DatabaseClient.tableLock("test_table")  // Will fail
        } catch (_: DatabaseClient.DatabaseDaemonException) {
            println("Exception got")
        } finally {
            DatabaseClient.disconnectToInstance("test_instance")
            DatabaseClient.deleteInstance("test_instance")
            DatabaseClient.logout()
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