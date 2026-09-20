package org.iotsplab.akiba.managers

import kotlinx.serialization.Serializable

@Serializable
data class BinaryMetadata(
    val id: Int,
    val originalPath: String,
    val processedPath: String? = null,
    val arch: String? = null,           // Binary arch (x86, ARM, MIPS, ...)
    val format: String? = null,         // Binary format (ELF, Microcode, PE, ...)
    val compilerSpec: String? = null,   // Compiler spec (Visual Studio, eabi, ...)
    val loadProperties: List<ImportManager.FileSegment> = listOf(),
    val checksum: String,               // File checksum
    val processedChecksum: String? = null
)