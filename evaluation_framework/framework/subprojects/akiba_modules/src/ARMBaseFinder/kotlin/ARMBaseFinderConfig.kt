package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class ARMBaseFinderConfig(
    var totalMatchThreshold: Int = 10,
    var totalMatchRatioThreshold: Double = 0.7,
    var uniqueMatchThreshold: Int = 10,
    var scoreThreshold: Int = 5,
    var compulsoryBaseAlignment: Int = 0x100,
    var compulsoryIVTAlignment: Int = 0x100,

    var checkTopMatchesCount: Int = 5,    // Check the top results of base addresses and entry points through emulation
    var tryDifferentAlignment: Boolean = false,
    var matchThreadNumber: Int = 1,
    var compulsoryCheckForEntry: Boolean = false
)

@Serializable
data class BaseMatcherConfig(
    var totalMatchThreshold: Int = 10,
    var totalMatchRatioThreshold: Double = 0.7,
    var uniqueMatchThreshold: Int = 10,
    var scoreThreshold: Int = 5,
    var compulsoryBaseAlignment: Int = 0x100,
    var compulsoryIVTAlignment: Int = 0x100
)