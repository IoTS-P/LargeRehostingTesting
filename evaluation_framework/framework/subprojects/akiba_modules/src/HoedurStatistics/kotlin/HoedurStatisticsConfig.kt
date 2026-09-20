package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class HoedurStatisticsConfig (
    var cmdPrefix: List<String> = listOf("/bin/sh", "-c"),
    var cmdPredo: String = "",
    var cargoPath: String = "~/.cargo/bin/cargo",
    var hoedurRoot: String? = null,
    var pythonPath: String = "/bin/python3",
    var crashArchiveRoot: String = "hoedur-fuzz-output",
    var hoedurLogConfigPath: String? = null,
    var rebaseSource: String? = null
)