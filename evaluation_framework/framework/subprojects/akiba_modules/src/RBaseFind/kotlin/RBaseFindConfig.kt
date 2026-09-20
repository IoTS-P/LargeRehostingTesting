package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class RBaseFindConfig (
    var cmdPrefix: List<String> = listOf("/bin/sh", "-c"),
    var cmdPredo: String = "",
    var rbasefindRoot: String? = null,
    var cargoPath: String = "~/.cargo/bin/cargo",
    var minStrlen: Int = 5,
    var threadNum: Int = 4,
    var baseAlign: Int = 0x100
)