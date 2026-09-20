package org.iotsplab.akiba.process.iotgs

import ghidra.program.model.address.Address
import org.iotsplab.akiba.utils.memory.MemorySection

open class BasicLoadMetadata (
    var baseAddress: Address,
    var entryPoint: Address,
    var sections: List<MemorySection> = listOf(),
    var initialSP: Address? = null
)