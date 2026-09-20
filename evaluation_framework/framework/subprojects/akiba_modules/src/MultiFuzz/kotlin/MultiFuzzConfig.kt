package org.iotsplab.akiba.process

data class MultiFuzzConfig (
    val multifuzzRoot: String? = null,
    var cargoPath: String = "~/.cargo/bin/cargo",
    var runFor: String = "60m",  // Time format: h, m, s, ...
    var baseAddressSource: String = "arm_base_finder_results.base_address",
    var ivtStartSource: String? = "arm_base_finder_results.ivt_start",
    var doFuzz: Boolean = true,
    var doReplay: Boolean = true,
    var replayThread: Int = 8
)