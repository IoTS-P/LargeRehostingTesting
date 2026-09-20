package org.iotsplab.akiba.process

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import kotlinx.coroutines.coroutineScope
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.iotgs.BasicLoadMetadata
import org.iotsplab.akiba.process.structure.ArmcmIVT
import org.iotsplab.akiba.utils.DataConsumer
import org.iotsplab.akiba.utils.DataProducer
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.FailOnCancelled
import org.iotsplab.akiba.utils.emulator.StepInEmulator
import org.iotsplab.akiba.utils.memory.MemorySection
import org.iotsplab.akiba.utils.memory.MemoryDebris
import org.iotsplab.akiba.utils.memory.MemorySection.Companion.PROT_EXEC
import org.iotsplab.akiba.utils.memory.MemorySection.Companion.PROT_READ
import org.iotsplab.akiba.utils.memory.MemorySection.Companion.PROT_WRITE
import kotlin.io.path.fileSize

/**
 * AddressSpaceAnalyzer: Try to analyze the address space used by firmware through executing from entry point
 *
 * Detailed process:
 *  - Use the step-in emulator to execute from entry point (We must use this emulator)
 *  - Analyze data flows through execution (Focusing on data copies and batch zero-byte fills)
 *  - After the execution, aggregate the results
 */
@DataConsumer<BasicLoadMetadata>("load_metadata")
@DataProducer<BasicLoadMetadata>("load_metadata")
@DataConsumer<ArmcmIVT>("ivt")
@WithTableColumn("data_file_offset", "INTEGER")
@WithTableColumn("data_size", "INTEGER")
@WithTableColumn("data_map_start", "INTEGER")
@FailOnCancelled
class AddressSpaceAnalyzer(
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    private lateinit var startupInfo: BasicLoadMetadata
    private val api = FlatProgramAPI(prog)
    private lateinit var emulator: StepInEmulator
    private val sections: MutableList<MemorySection> = mutableListOf()

    private val baseAddress: Address
        get() = startupInfo.baseAddress
    private val entryPoint: Address
        get() = startupInfo.entryPoint

    override suspend fun startProcess() {
        coroutineScope {
            startupInfo = getTaskData("load_metadata") as BasicLoadMetadata
        }
        api.getFunctionContaining(entryPoint) ?: run {
            logger.error("Reset handler is not valid code, it may be invalid.")
            failureSign = FAILED
            return
        }

        emulator = StepInEmulator(prog, entryPoint, startupInfo.initialSP!!, logger,
            options = StepInEmulator.SUPPORT_DISTINCT_INSTRUCTION_COUNTER or
                    StepInEmulator.SUPPORT_DISTINCT_FUNCTION_COUNTER or
                    StepInEmulator.SUPPORT_SKIP_CALLOTHER or
                    StepInEmulator.SUPPORT_DATA_FLOW_ANALYZER,
            maxExecution = DEFAULT_TOTAL_EXEC_COUNT,
            monitor = taskGlobalMonitor
        )

        // Flash space
        getMetadata().loadProperties.let {
            if (!it.isEmpty())
                it.forEach { section ->
                    sections.add(MemorySection(section.newOffset, section.length, api.toAddr(section.oldOffset),
                        "Program flash", "load", PROT_READ or PROT_WRITE or PROT_EXEC))
                }
            else
                sections.add(MemorySection(0, originalFile.toPath().fileSize(), baseAddress,
                    "Program Flash", "load", PROT_READ or PROT_WRITE or PROT_EXEC))
        }
        // Stack space
        sections.add(MemorySection(
            null, MINIMUM_STACK_SIZE, startupInfo.initialSP!!.subtract(MINIMUM_STACK_SIZE),
            "Stack top page", "stack", PROT_READ or PROT_WRITE
        ))

        testFunctions()
    }

    private suspend fun testFunctions() {
        // Emulate current function, and skip all calls
        emulator.go()
        analyzeInterestingDataflow(emulator.assembledDataflow)
        analyzeInterestingZeroStore(emulator.assembledZeroStore)
        sections.sortBy { it.mapStart.offset }
        finalHandler()

        logger.info("Handled dataflow: ${MemorySection.sectionListString(sections)}")

        // Update section info
        startupInfo.sections = sections
        setTaskData("sections", sections)

        updateResult()
    }

    /**
     * finalHandler: Check the sections got, handle overlapping sections and merge adjacent sections
     */
    @Throws(IllegalStateException::class)
    private fun finalHandler() {
        // Delete sections that copies data that does not exist in the firmware file
        val fileSize = usingFile.toPath().fileSize()
        var idx = 0
        while (idx < sections.size) {
            val sec = sections[idx]
            if (sec.fileOffset == null) {
                idx += 1
                continue
            }
            if (sec.fileOffset!! >= fileSize) {
                logger.info("Removing a section with a file offset that does not exist " +
                        "(${sec.fileOffset!!.toString(16)})")
                sections.removeAt(idx)
            } else idx += 1
        }

        // Find overlapping sections
        // In theory, overlap could only occur in stack and other sections (due to misestimation of stack size)
        // Handle stack section first
        val stackSecID = sections.indices.filter { sections[it].name == "stack" }
        check(stackSecID.size <= 1) { "Stack section is not unique" }
        val stackSection = if (stackSecID.size == 1)
            sections[stackSecID[0]]
        else
            null
        stackSection ?.let { ss ->
            var idx = 0
            while (idx < sections.size) {
                if (sections[idx].name == "stack") {
                    idx += 1
                    continue
                }
                val cur = sections[idx]
                // Overlapping containing stack top is intolerable
                if (ss.mapStart.offset + ss.size > cur.mapStart.offset &&
                    cur.mapStart.offset + cur.size > ss.mapStart.offset + ss.size) {
                    throw IllegalStateException("Stack top overlapped with other sections")
                }
                // Overlaps at stack bottom, remove the previous section directly, because we've already has the minimum
                // size of stack and it cannot be smaller.
                if ((ss.mapStart.offset > cur.mapStart.offset &&
                    ss.mapStart.offset < cur.mapStart.offset + cur.size) ||
                    (ss.mapStart.offset <= cur.mapStart.offset &&
                    ss.mapStart.offset + ss.size > cur.mapStart.offset + cur.mapStart.size)) {
                    logger.info("Remove a section before stack because it's too close to stack")
                    sections.removeAt(idx)
                } else {
                    idx += 1
                }
            }
        }

        // handle other sections
        var overlapGot = true
        while (overlapGot) {
            overlapGot = false
            var idx = 0
            while (idx < sections.size - 1) {
                val cur = sections[idx]
                val next = sections[idx + 1]
                if (cur.mapStart.offset + cur.size <= next.mapStart.offset) {
                    idx += 1
                    continue
                }

                // None of two overlapped section could have file mappings
                if (cur.fileOffset != null || next.fileOffset != null) {
                    if (cur.size == next.size &&
                        cur.fileOffset != null && next.fileOffset != null && cur.fileOffset == next.fileOffset) {
                        sections.removeAt(idx + 1)
                        continue
                    } else {
                        logger.error("Error while handling overlap between ${cur.mapStart.offset.toString(16)}-" +
                                "${(cur.mapStart.offset + cur.size).toString(16)} and " +
                                "${next.mapStart.offset.toString(16)}-" +
                                (next.mapStart.offset + next.size).toString(16)
                        )
                        throw IllegalStateException("Overlapping section has file mapping")
                    }
                }

                overlapGot = true
                // check if they have the same name. If so, merge them. Else, shrink one section with less prot
                if (cur.name == next.name) {
                    logger.info("Merging sections named ${cur.name}")
                    cur.size = next.mapStart.offset + next.size - cur.mapStart.offset
                    cur.prot = cur.prot or next.prot
                    sections.removeAt(idx + 1)
                } else {
                    logger.info("Shrinking a section...")
                    if (cur.prot or next.prot == cur.prot) {
                        // shrink next section
                        next.mapStart = cur.mapStart.add(cur.size)
                        next.size = cur.mapStart.offset + cur.size - next.mapStart.offset
                    } else {
                        // shrink current section
                        cur.size = next.mapStart.offset - cur.mapStart.offset
                    }
                    idx += 1
                }
            }
        }

        // Merge sections that has tiny gaps and with the same name
        var mergeGot = true
        while (mergeGot) {
            mergeGot = false
            var idx = 0
            while (idx < sections.size - 1) {
                val cur = sections[idx]
                val next = sections[idx + 1]
                if (cur.name != next.name || cur.mapStart.offset + cur.size + MAXIMUM_MERGE_GAP < next.mapStart.offset
                    || cur.fileOffset != null || next.fileOffset != null) {
                    idx += 1
                    continue
                }
                mergeGot = true
                logger.info("Merging adjacent sections...")
                cur.size = next.mapStart.offset + next.size - cur.mapStart.offset
                cur.prot = cur.prot or next.prot
                sections.removeAt(idx + 1)
            }
        }
    }

    private suspend fun analyzeInterestingDataflow(flow: Map<MemoryDebris, MemoryDebris>) {
        flow.filter { (src, _) -> src.size > INTERESTING_THRESHOLD }
            .forEach { (src, dst) ->
                // For ARM Cortex, IVT copy
                if (Regex("ARM:(LE|BE):32:Cortex").matches(prog.languageID.toString())) {
                    val ivt = getTaskData("ivt") as ArmcmIVT
                    if (src.address == ivt.start && src.size >= ivt.minSize) {
                        sections.add(MemorySection(
                            ivt.start.subtract(baseAddress), src.size.toLong(), dst.address,
                            "IVT", "isr_vector", PROT_READ))
                    }
                }
                when {
                    else -> {
                        sections.add(MemorySection(
                            src.address.subtract(baseAddress), src.size.toLong(), dst.address,
                            "Copied data", "data", PROT_READ or PROT_WRITE))
                    }
                }
            }
    }

    private fun analyzeInterestingZeroStore(zeroStore: List<MemoryDebris>) {
        zeroStore.filter { it.size > INTERESTING_THRESHOLD }
            .forEach { s ->
                sections.add(MemorySection(null, s.size.toLong(), s.address,
                    "Uninitialized global vars", "bss", PROT_READ or PROT_WRITE))
            }
    }

    private suspend fun updateResult() {
        val dataSection = sections.firstOrNull { it.description == "Copied data" }
        if (dataSection == null)
            return
        updateData(mapOf(
            "data_file_offset" to dataSection.fileOffset,
            "data_size" to dataSection.size,
            "data_map_start" to dataSection.mapStart.offset
        ))

        coroutineScope { setTaskData("sections", sections) }
    }

    companion object {
        const val DEFAULT_TOTAL_EXEC_COUNT: Long = 20000
        const val INTERESTING_THRESHOLD = 0x40
        const val MINIMUM_STACK_SIZE: Long = 0x100
        const val MAXIMUM_MERGE_GAP: Long = 0x8
    }
}