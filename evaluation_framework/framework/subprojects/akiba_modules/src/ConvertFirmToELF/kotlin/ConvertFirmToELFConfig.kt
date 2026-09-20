package org.iotsplab.akiba.process

data class ConvertFirmToELFConfig (
    var useSource: String = "existing"  // “existing” / "firmxray"
)