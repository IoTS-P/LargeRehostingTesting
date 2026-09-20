package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class FirmlineConfig (
    var cmdPrefix: List<String> = listOf("/bin/sh", "-c"),
    var cmdPredo: String = "",
    var firmlineRoot: String? = null,
    // Firmline requires Python3 of 3.10/3.11, earlier or later versions may cause errors
    var pythonRoot: String = "/usr/bin/python3",
    var ghidraHome: String? = null,
    var timeoutForEach: Int = 600
)