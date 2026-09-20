package org.iotsplab.akiba.process

import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.DoNotCreateTable
import org.iotsplab.akiba.utils.WithConfigClass

@WithConfigClass(TestModuleConfig::class)
@DoNotCreateTable
class TestModule (
    configPath: String,
): AkibaModule(
    configPath = configPath,
    defaultConfig = "1.0.0",
    id = -1,
    program = null
) {
    val conf: TestModuleConfig
        get() = config as TestModuleConfig

    override suspend fun startProcess() {
        println("Hello, ${conf.name}")
        println("I am ${conf.age} years old")
        println("I work in ${conf.department} department")
        println("My salary is ${conf.salary}")

        TestModule2.test()
    }
}