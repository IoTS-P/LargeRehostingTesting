package org.iotsplab.akiba.tests.configTests

import org.iotsplab.akiba.Main
import org.iotsplab.akiba.managers.ConfigManager
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test

object ConfParserTest1 {
    @Test
    fun testParse() {
        Path.of("/tmp/akiba_test_1.json").writeText(testConfig_1)
        check (Path.of("/tmp/akiba_test_1.json").exists()) {
            "File /tmp/akiba_test_1.json failed to create"
        }

        Main.mainConfigPath = "/tmp/akiba_test_1.json${ConfigManager.KEY_SEPARATOR}/main"
        ConfigManager.config = ConfigManager.loadGlobalConfig()

        assert(ConfigManager.config.general!!.binariesRoot == "/data/all-results-combined")
        assert(ConfigManager.config.withGhidraProject!!.projectRoot == "./ghidra_projects")
        assert(ConfigManager.config.sqlSource.serverIP == "127.0.0.1")
        assert(ConfigManager.config.sqlSource.serverPort == 31777)
        assert(ConfigManager.config.dbImports!!.size == 2)
        assert(ConfigManager.config.dbImports!![0] == "firmxray_results")
        assert(ConfigManager.config.tasks.size == 1)
        assert(ConfigManager.config.tasks[0].consoleLogLevel.name() == "DEBUG")
    }

    val testConfig_1 = """
        {
          "main": {
            "general": {
              "binariesRoot": "/data/all-results-combined",
              "importRoot": "",
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
                "configKey": "/tmp/akiba_test_2a.yaml@.",
                "consoleLogLevel": "debug",
                "fileLogLevel": "debug",
                "timeout": 600
              }
            ]
          }
        }
    """.trimIndent()
}