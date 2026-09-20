package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class ExternalDynamicCheckerConfig(
    var updateCommand: String = "UPDATE FIRMWARE_INFO SET BASE_VALID_DYNAMIC = '%s' WHERE ID = %d"
)