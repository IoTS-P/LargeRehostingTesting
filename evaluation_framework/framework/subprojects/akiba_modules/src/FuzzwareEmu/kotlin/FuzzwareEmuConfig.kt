package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class FuzzwareEmuConfig (
    var traceFunc: Boolean = false,     // WARNING: Opening this option may cause output file very large!!!
    var traceMemory: Boolean = false,   // WARNING: Opening this option may cause output file very large!!!
    var timeout: Int = 180,
    var maxBlockExecuted: Int = 3000000
)