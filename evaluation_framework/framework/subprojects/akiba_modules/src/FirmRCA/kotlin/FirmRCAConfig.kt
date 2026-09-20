package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class FirmRCAConfig (
    var firmRCARoot: String? = null,
    var firmRCAPythonVenvRoot: String? = null,
    // FirmRCA always generate a lot of outputs, if you need to run many inputs, better set it to true
    // to avoid filling up your disks
    var deleteLogOnFinish: Boolean = true,
    // Some output files from FirmRCA's Fuzzware may be also big (like memac.bin). If your test cases has a number
    // of larger than 10000, you may want to set this to true to avoid filling up your disks. Once this is set to
    // true, logs will also be deleted no matter what `deleteLogOnFinish` is set to
    var deleteAllFilesOnFinish: Boolean = true,
    var classifiedMode: Boolean = true,     // Test before classifying the test cases to reduce the number of them
    var timeoutForEach: Int = 1800,       // seconds
    var specifiedTestCases: List<String>? = null,
    var maximumInsn: Int = 100000,      // Max instruction to backward taint analysis. If 0 or negative, no limit
    var baseAddressSource: String = "ARMBASEFINDER_RESULTS.BASE_ADDRESS"
)