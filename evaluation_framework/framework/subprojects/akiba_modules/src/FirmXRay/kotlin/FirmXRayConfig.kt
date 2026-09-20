package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class FirmXRayConfig(
    var cmdPrefix: List<String> = listOf("/bin/sh", "-c"),
    var firmxrayRoot: String? = null,
    var javaBinPath: String = "/bin/java"
)
