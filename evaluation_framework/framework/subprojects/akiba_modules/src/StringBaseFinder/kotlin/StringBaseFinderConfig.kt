package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable
import org.iotsplab.akiba.process.StringBaseFinder.Companion.DEFAULT_DECOMPILE_THREAD_NUMBER
import org.iotsplab.akiba.process.StringBaseFinder.Companion.DEFAULT_SUBTRACTION_MAP_THRESHOLD

@Serializable
data class StringBaseFinderConfig(
    var subtractionMapThreshold: Double = DEFAULT_SUBTRACTION_MAP_THRESHOLD,
    var decompileThreadNumber: Int = DEFAULT_DECOMPILE_THREAD_NUMBER,
    var scalarClusterEpsilon: Long = 0x40000,   // 4MiB, should be enough for most cases
    var pythonPath: String = "/bin/python3"
)