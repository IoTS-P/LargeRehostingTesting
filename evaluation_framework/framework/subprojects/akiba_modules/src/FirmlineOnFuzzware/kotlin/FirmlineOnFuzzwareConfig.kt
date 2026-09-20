package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class FirmlineOnFuzzwareConfig(
    var maxTimeout: String = "00:00:10:00", // 10 minutes by default
    var otherArguments: String = "",
    var withGDMA: Boolean = false
)
