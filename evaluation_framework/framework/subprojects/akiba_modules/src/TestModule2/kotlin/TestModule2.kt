package org.iotsplab.akiba.process

import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.PureDependency

@PureDependency
class TestModule2 : AkibaModule() {
    companion object {
        fun test() {
            println("TestModule2")
        }
    }
}