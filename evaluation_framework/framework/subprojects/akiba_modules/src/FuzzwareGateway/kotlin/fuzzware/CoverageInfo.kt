package org.iotsplab.akiba.process.fuzzware

data class CoverageInfo (
    val foundSymbols: MutableList<Pair<Long, String>> = mutableListOf(),
    val notFoundSymbols: MutableList<Pair<Long, String>> = mutableListOf(),
    // Key: address, Value: Pair<symbol name, offset>
    val foundBasicBlocks: MutableList<Pair<Long, Pair<String, Long>>> = mutableListOf()
)