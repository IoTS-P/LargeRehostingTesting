package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class FuzzwareGatewayConfig (
    var cmdPrefix: List<String> = listOf("/bin/sh", "-c"),
    var cmdPredo: String = "",
    var regenerateConfig: Boolean = true,
    var projectRoot: String = "fuzzware-projects",
    var venv: String = "fuzzware",
    var doRebase: Boolean = true
)
