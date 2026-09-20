package org.iotsplab.akiba.tests.configTests

import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.core.config.Configurator
import org.iotsplab.akiba.Main
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.module.AkibaModule
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import java.io.File
import java.io.PrintStream

object ConfParserTest2 {
    @Test
    fun testParse() = runBlocking {
        Path.of("/tmp/akiba_test_2.json").writeText(testConfig_2)
        check (Path.of("/tmp/akiba_test_2.json").exists()) {
            "File /tmp/akiba_test_2.json failed to create"
        }
        Path.of("/tmp/akiba_test_2a.json").writeText(moduleConfig)
        check (Path.of("/tmp/akiba_test_2a.json").exists()) {
            "File /tmp/akiba_test_2a.json failed to create"
        }

        Configurator.reconfigure(File("configs/log4j2.xml").toURI())

        Main.mainConfigPath = "/tmp/akiba_test_2.json${ConfigManager.KEY_SEPARATOR}/main"
        ConfigManager.config = ConfigManager.loadGlobalConfig()

        assert(ConfigManager.config.general!!.binariesRoot == "/data/all-results-combined")
        assert(ConfigManager.config.withGhidraProject!!.projectRoot == "./ghidra_projects")
        assert(ConfigManager.config.sqlSource.serverIP == "127.0.0.1")
        assert(ConfigManager.config.dbImports!!.size == 2)
        assert(ConfigManager.config.dbImports!![0] == "firmxray_results")
        assert(ConfigManager.config.tasks.size == 1)

        WorkspaceManager.projectName = "test"

        val task = ConfigManager.config.tasks[0]
        val constructor = task.mainClass?.kotlin!!.primaryConstructor!!
        val instance = constructor.call("/tmp/akiba_test_2a.json") as AkibaModule

        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)
        System.setOut(printStream)

        instance.startProcess()

        val capturedOutput = outputStream.toString().split("\n")
        assert(capturedOutput[0] == "Hello, Akiba")
        assert(capturedOutput[1] == "I am 18 years old")
        assert(capturedOutput[2] == "I work in CSE department")
        assert(capturedOutput[3] == "My salary is 24000.0")
        assert(capturedOutput[4] == "TestModule2")
    }

    val testConfig_2 = """
        {
          "main": {
            "general": {
              "binariesRoot": "/data/all-results-combined",
              "processor": "n/a",
              "autoAnalysisTimeout": 600,
              "threads": 1
            },
            "withGhidraProject": {
              "projectRoot": "./ghidra_projects",
              "name": "analyzed_base_2",
              "mode": "new",
              "forkTo": null,
              "continueLog": null,
              "overwriteProject": false,
              "overwriteLog": true,
              "saveProject": true,
              "noCreateProgram": false
            },
            "sqlSource": {
              "serverIP": "127.0.0.1",
              "serverPort": "31777",
              "useSnapshot": "current",
              "constraint": "WHERE u.FORMAT = 'Raw Binary'",
              "disableUpdate": false
            },
            "dbImports": [
              "firmxray_results",
              "convert_firm_to_elf_results"
            ],
            "tasks": [
              {
                "mainClassName": "org.iotsplab.akiba.process.TestModule",
                "configKey": "/tmp/akiba_test_3a.json@.",
                "consoleLogLevel": "debug",
                "fileLogLevel": "debug",
                "timeout": 600
              }
            ]
          }
        }
    """.trimIndent()

    val moduleConfig = """
        {
          "name": "Akiba",
          "age": 18,
          "department": "CSE",
          "salary": 24000
        }
    """.trimIndent()
}