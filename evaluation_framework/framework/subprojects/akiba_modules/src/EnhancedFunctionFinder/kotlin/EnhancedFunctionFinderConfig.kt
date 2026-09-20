package org.iotsplab.akiba.process

import kotlinx.serialization.Serializable

@Serializable
data class EnhancedFunctionFinderConfig(
    var numberOfPreBytes: Int = 8,
    var numberOfInitialBytes: Int = 16,
    var startToNonStartSamplingFactors: Int = 50,
    var maximumNumberOfStarts: Int = 1000,
    var contextRegistersAndValues: Map<String, Long> = mapOf(),
    var includePrecedingAndFollowing: Boolean = false,
    var includeBitFeatures: Boolean = false,
    var minimumFunctionSize: Int = 16
)