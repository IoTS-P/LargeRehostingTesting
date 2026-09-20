package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class FuzzwareStatConfig (
    var projectRoot: String = "fuzzware-projects",
    var rebaseSource: String? = null
)