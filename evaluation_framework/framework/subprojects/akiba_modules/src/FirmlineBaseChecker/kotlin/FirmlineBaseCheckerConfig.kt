package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class FirmlineBaseCheckerConfig(
    var firmlineDbPath: String? = null,
)
