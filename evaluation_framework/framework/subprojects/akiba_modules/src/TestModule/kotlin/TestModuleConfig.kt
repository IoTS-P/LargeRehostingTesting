package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class TestModuleConfig (
    var name: String = "Foo",
    var age: Int = 35,
    var department: String = "IT",
    var salary: Double = 24000.0
)