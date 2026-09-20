package org.iotsplab.akiba.tests.configTests

import org.apache.logging.log4j.core.config.Configurator
import org.iotsplab.akiba.Main
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.ConfigManager.mergeConfigs
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test

object ConfParserTest4 {
    @Test
    fun testParse() {
        Path.of("/tmp/akiba_test_4.json").writeText(testConfig_4)
        check (Path.of("/tmp/akiba_test_4.json").exists()) {
            "File /tmp/akiba_test_4.json failed to create"
        }

        Configurator.reconfigure(File("configs/log4j2.xml").toURI())

        Main.mainConfigPath = "/tmp/akiba_test_4.json${ConfigManager.KEY_SEPARATOR}/main"
        ConfigManager.config = ConfigManager.loadGlobalConfig()

        mergeConfigs(outputPath = File("/tmp/akiba_merged.json"))
        println(Path.of("/tmp/akiba_merged.json").readText())
    }

    val testConfig_4 = """
        {
          "metadata": {
            "description": "Akiba Test"
          },
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
                "configKey": "@@/TestModule",
                "consoleLogLevel": "debug",
                "fileLogLevel": "debug",
                "timeout": 600
              }
            ]
          },
          "TestModule": {
            "name": "Akiba",
            "age": 18,
            "department": "CSE",
            "salary": 24000
          }
        }
    """.trimIndent()
}