package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class HoedurFuzzConfig (
    var hoedurRoot: String? = null,
    var cargoPath: String = "~/.cargo/bin/cargo",
    var maxTimeoutMinutes: String = "60m",  // Time format: h, m, s, ...
    var crashArchiveRoot: String = "hoedur-fuzz-output",
    var hoedurLogConfigPath: String? = null,
    var baseAddressSource: String = "arm_base_finder_results.base_address",
    var ivtStartSource: String? = "arm_base_finder_results.ivt_start"
)