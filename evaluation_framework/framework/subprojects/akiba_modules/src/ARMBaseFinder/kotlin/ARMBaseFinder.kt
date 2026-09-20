package org.iotsplab.akiba.process

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.address.AddressOutOfBoundsException
import ghidra.program.model.data.DataUtilities
import ghidra.program.model.data.PointerDataType
import ghidra.program.model.lang.Register
import ghidra.program.model.listing.Program
import ghidra.util.exception.NotFoundException
import kotlinx.coroutines.*
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.iotgs.BasicLoadMetadata
import org.iotsplab.akiba.process.structure.ArmcmIVT
import org.iotsplab.akiba.process.structure.ArmcmIVT.Companion.fromAddress
import org.iotsplab.akiba.process.structure.BaseMatcher
import org.iotsplab.akiba.process.structure.BaseMatcherResult
import org.iotsplab.akiba.utils.DataProducer
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.assembly.DisasmHelper
import org.iotsplab.akiba.utils.emulator.StepInEmulator
import org.iotsplab.akiba.utils.function.allFunctionStarts
import org.iotsplab.akiba.utils.memory.EnhancedMemorySearcher
import org.iotsplab.akiba.utils.memory.MemoryUtil
import org.iotsplab.akiba.utils.reference.isValidCodeTarget
import java.math.BigInteger
import java.nio.file.Path
import java.util.*
import java.util.concurrent.*
import kotlin.io.path.fileSize
import kotlin.math.ceil

/**
 * ARMBaseFinder: Finder for base addresses and entry points of ARM Cortex-M firmware bin files
 * Former processes requirements: HTTPServer, ProgramServer, EntryFinder
 *
 * Detailed process:
 *  - Find and get all possible Interrupt Vector Table (IVT) in bin file
 *  - Match those IVT with all defined function entry points
 *  - Filter the match results with multiple filters
 *  - Choose the best one and update database
 *
 * @author: Hornos3, Hornos3@github.com
 */
@DataProducer<ArmcmIVT>("ivt")
@WithTableColumn("base_address", "INTEGER")
@WithTableColumn("entry_point", "INTEGER")
@WithTableColumn("ivt_start", "INTEGER")
@WithTableColumn("emulation_score", "INTEGER")
@WithConfigClass(ARMBaseFinderConfig::class)
class ARMBaseFinder(
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    configPath = configPath,
    defaultConfig =
        ARMBaseFinderConfig(
            totalMatchThreshold = 10,
            totalMatchRatioThreshold = 0.7,
            uniqueMatchThreshold = 10,
            scoreThreshold = 5,
            compulsoryBaseAlignment = 0x100,
            compulsoryIVTAlignment = 0x100,
            tryDifferentAlignment = false,
            matchThreadNumber = 1,
            compulsoryCheckForEntry = false
        ),
    id = id,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    private val conf: ARMBaseFinderConfig
        get() = config as ARMBaseFinderConfig
    private var ivt: ArmcmIVT? = null
    private var baseAddress: Address? = null
    private var entryPoint: Address? = null
    private val api: FlatProgramAPI = FlatProgramAPI(program)
    private var matchResults = ConcurrentSkipListSet { baseMatcherResult: BaseMatcherResult, t1: BaseMatcherResult ->
        -baseMatcherResult.compareTo(t1)
    } // sort in desc order
    private var interestingResults = ConcurrentSkipListSet {
        baseMatcherResult: BaseMatcherResult, t1: BaseMatcherResult -> -baseMatcherResult.compareTo(t1)
    } // sort in desc order
    private var finalResult: BaseMatcherResult? = null

    private val tryDifferentAlignment: Boolean
        get() = conf.tryDifferentAlignment
    private val matchThreadNumber: Int
        get() = conf.matchThreadNumber
    private val matcherThresholds: BaseMatcherConfig
        get() = run {
            BaseMatcherConfig(
                totalMatchThreshold = conf.totalMatchThreshold,
                totalMatchRatioThreshold = conf.totalMatchRatioThreshold,
                uniqueMatchThreshold = conf.uniqueMatchThreshold,
                scoreThreshold = conf.scoreThreshold,
                compulsoryBaseAlignment = conf.compulsoryBaseAlignment,
                compulsoryIVTAlignment = conf.compulsoryIVTAlignment
            )
        }


//    constructor(configPath: String? = null, program: Program, searchAllAlignments: Boolean, id: Int) :
//            this(configPath, program, id) {
//        this.searchAllAlignments = searchAllAlignments
//    }
//
//    private constructor(configPath: String? = null, program: Program, ivt:
//                        ARMInterruptVectorTable, baseAddress: Address, id: Int)
//            : this(configPath, program, id) {
//        this.ivt = ivt
//        this.baseAddress = baseAddress
//        this.entryPoint = ivt.entryPoint
//    }

    val maxMatches: ArrayList<BaseMatcherResult>
        get() {
            val mostMatchedCount = matchResults.first().matchedFuncs.size
            val count =
                matchResults.stream().filter { r: BaseMatcherResult -> r.matchedFuncs.size == mostMatchedCount }
                    .count().toInt()
            val ret = ArrayList<BaseMatcherResult>()
            val ri: Iterator<BaseMatcherResult> = matchResults.iterator()
            for (i in 0 until count) ret.add(ri.next())
            return ret
        }

    private val matchResultByAddress: TreeMap<Long?, Int?>
        get() {
            val res =
                TreeMap<Long?, Int?>()
            for (addrInIvt in ivt!!.entries.values) {
                try {
                    val target = addrInIvt - baseAddress!!.offset
                    if (prog.listing.getFunctionAt(api.toAddr(target)) != null) res[addrInIvt] = ABF_MATCH_SUCCESS
                    else if (prog.listing.getFunctionContaining(api.toAddr(target)) != null) res[addrInIvt] =
                        ABF_MATCH_IN_FUNC
                    else res[addrInIvt] = ABF_MATCH_FAILED
                } catch (_: AddressOutOfBoundsException) { }
            }
            return res
        }

    fun toPrettyString(): String {
        baseAddress ?: return "Not analysed yet"
        ivt ?: return "Not analysed yet"

        return "----------------------------------------" + System.lineSeparator() +
            "Base address: ${baseAddress!!.addressSpace}:0x${baseAddress!!.offset.toString(16)}" +
            System.lineSeparator() +
            "Entry point: ${entryPoint!!.addressSpace}:0x${entryPoint!!.offset.toString(16)}" +
            System.lineSeparator() +
            ivt!!.toStringWithMatchResult(matchResultByAddress) +
            "----------------------------------------" + System.lineSeparator()
    }

    @Throws(Exception::class)
    override suspend fun startProcess() {
        // Get start addresses of all functions
        val functionStarts = TreeSet(TreeSet(prog.allFunctionStarts()).map { it.offset })
        logger.info("Got ${functionStarts.size} function start addresses")
        val ems = EnhancedMemorySearcher(prog)

        // Find the optimal IVT candidate in the whole address space
        val overallAddressMap = TreeMap<Address, HashSet<Long>>()
        val ivtList: MutableList<ArmcmIVT> = mutableListOf()
        for (alignRec in 0..3) {
            if (!tryDifferentAlignment && alignRec != 0) continue

            var ptr = prog.memory.minAddress.add(alignRec.toLong())
            ptr = ems.nextAddressNot(ptr, arrayOf(0, -0x1))

            // Walk through all memory space and get all IVTs
            logger.info("Getting all IVTs in offset $alignRec...")
            while (ptr < prog.memory.maxAddress) {
                if (ptr.offset % matcherThresholds.compulsoryIVTAlignment != 0L) {
                    ptr = ptr.add(4)
                    continue
                }
                val addrMaxBias = Path.of(getMetadata().processedPath ?: getMetadata().originalPath).fileSize()
                val ivt = fromAddress(prog, ptr,
                    maxRangeBias = ceil(addrMaxBias * 1.0 / 0x10000).toInt() + 1)
                ivt ?. let { ivtList.add(ivt) }
                ptr = ptr.add(4)
            }

            // collect all entries
            logger.info("Collecting IVT entries in offset $alignRec...")
            val ivtEntryAddresses: MutableSet<Address> = TreeSet()
            for (ivt in ivtList)
                for (i in 0..<ivt.entrySize)
                    ivtEntryAddresses.add(ivt.start.add(i * 4))

            // collect overall subtraction map
            logger.info("Working out overall address map in offset $alignRec...")

            for (e in ivtEntryAddresses) {
                overallAddressMap[e] = HashSet()
                var addr = prog.memory.getInt(e)
                if ((addr and 1) or 0 == 1) addr = addr xor 1
                for (f in functionStarts) {
                    val sub = addr - f
                    overallAddressMap[e]!!.add(sub)
                }
            }
        }

        // Match for this ivt
        logger.info("Start to match...")

        val dispatcher = ForkJoinPool(matchThreadNumber).asCoroutineDispatcher()
        coroutineScope {
            ivtList.map { ivt -> async(dispatcher) {
                val matcher = BaseMatcher(null, functionStarts, prog, overallAddressMap)
                matcher.setIvt(ivt)
                val currentResult = matcher.matchPossibleResult(
                    matcherThresholds.totalMatchThreshold,
                    matcherThresholds.totalMatchRatioThreshold,
                    matcherThresholds.uniqueMatchThreshold,
                    matcherThresholds.scoreThreshold,
                    matcherThresholds.compulsoryBaseAlignment,
                    memoryAddressBias = (ivt.start.offset % 4).toInt()
                )
                currentResult ?. let { matchResults.addAll(it) }
            } }.awaitAll()
        }

        logger.info("Match completed")

        distillResults()

        val sortedResults = ConcurrentSkipListSet {
            baseMatcherResult: BaseMatcherResult, t1: BaseMatcherResult ->
                -baseMatcherResult.compareTo(t1)
        }
        sortedResults.addAll( matchResults.filter { r ->
            r.matchedFuncs.size >= matcherThresholds.totalMatchThreshold &&
            r.maxMatchedRatio >= matcherThresholds.totalMatchRatioThreshold &&
            r.matchedFuncs.distinct().size >= matcherThresholds.uniqueMatchThreshold &&
            r.maxScore >= matcherThresholds.scoreThreshold &&
            r.offset % matcherThresholds.compulsoryBaseAlignment == 0.toLong()
        } .toList())
        matchResults = sortedResults

        var emuCheckResults: List<Pair<BaseMatcherResult, Pair<Long, Int>>> =
            matchResults.take(conf.checkTopMatchesCount).map {
                it to checkResultThroughEmulation(it)
            }.filter { it.second.first != -1L }

        if (emuCheckResults.isEmpty()) {
            logger.error("Failed to find any candidate interrupt vector table, loosen your criteria and try again?")
            updateFailureResult()
            failureSign = FAILED
            return
        }

        emuCheckResults = emuCheckResults.sortedBy { (_, s) -> -s.second }

        logger.info("Top matches: ")
        emuCheckResults.forEachIndexed { idx, res ->
            logger.info("#${idx + 1}: score ${res.second.second}, " +
                    "base 0x${res.first.offset.toString(16)}, entry 0x${res.second.first.toString(16)}, " +
                    "IVT offset 0x${res.first.ivtOffset.offset.toString(16)}")
        }

        finalResult = emuCheckResults.first().first

//        // If there exist multiple match results that has the same count of matched function, let the user choose
//        val mostMatchedCount = matchResults.first().matchedFuncs.size
//        val hardToChooseCount = matchResults.stream()
//            .filter { r: BaseMatcherResult -> r.matchedFuncs.size == mostMatchedCount }.count().toInt()
//        // Base address and ivt address that aligned to page size is privileged
//        val results = matchResults.take(hardToChooseCount)
//        finalResult = finalFilter(results)  // Use the best aligned result, to avoid user input

        ivt = fromAddress(prog, finalResult!!.ivtOffset) ?: run {
            logger.error("Failed to find any candidate interrupt vector table, loosen your criteria and try again?")
            updateFailureResult()
            failureSign = FAILED
            return
        }
        baseAddress = api.toAddr(finalResult!!.offset)
        entryPoint = ivt!!.entryPoint

        ivt!!.adjustWithMatchResult(matchResultByAddress)

        logger.info(String.format("Analyse result for %s:", usingFile.name))
        logger.info(toPrettyString())

        updateData(mapOf("emulation_score" to emuCheckResults.first().second.second.toLong()))

        applyBase()

        updateSuccessResult()
    }

    private fun distillResults() {
        // Remove redundant results, for same offset, only the best match result remains
        val washedResults = ConcurrentSkipListSet { baseMatcherResult: BaseMatcherResult, t1: BaseMatcherResult? ->
            -baseMatcherResult.compareTo(
                t1!!
            )
        }
        // Because our results are already sorted by match counts, so we don't need to compare the matches
        // But we still need to collect equally best matches
        for (result in matchResults) {
            if (washedResults.stream()
                    .noneMatch { r: BaseMatcherResult ->
                            r.ivtOffset <= result.ivtOffset && result.ivtOffset < r.ivtOffset.add(r.ivtSize) &&
                            r.offset == result.offset && r.matchedFuncs.size >= result.matchedFuncs.size
                        }
            ) washedResults.add(result)
            if (washedResults.size == MAX_WASHED_DATA_COUNT) break
        }

        // Remove absolutely wrong result, like address overflow and invalid entry point
        washedResults.removeIf { r: BaseMatcherResult ->
            val entry = fromAddress(prog, r.ivtOffset)?.entryPoint ?: return@removeIf true
            var addressOverflow = false
            var targetFuncAddr: Address? = null
            try {
                prog.maxAddress.add(r.offset and 0xFFFFFFFFL)
                targetFuncAddr = entry.subtract(r.offset)
            } catch (_: AddressOutOfBoundsException) {
                addressOverflow = true
            }
            addressOverflow || (prog.listing.getFunctionContaining(targetFuncAddr) == null
                    && r.maxScore < THRESHOLD_TO_IGNORE_UNDEFINED_ENTRYPOINT)
        }

        matchResults = if ((config as ARMBaseFinderConfig).compulsoryCheckForEntry)
            interestingResults
        else
            washedResults
    }

    private fun checkResultThroughEmulation(result: BaseMatcherResult): Pair<Long, Int> {
        try {
            val ivt: ArmcmIVT = fromAddress(prog, result.ivtOffset)!!
            logger.info("Trying base ${result.offset.toString(16)}, entry ${ivt.entryPoint!!.offset.toString(16)}")
            val emu = object: StepInEmulator(
                program = prog,
                entryPoint = ivt.entryPoint!!,
                stackTop = api.toAddr(ivt.masterStackPointer!!),
                logger = logger,
                options = SUPPORT_DISTINCT_INSTRUCTION_COUNTER or
                        SUPPORT_DISTINCT_FUNCTION_COUNTER or
                        SUPPORT_SKIP_CALLOTHER or
                        SUPPORT_AUTO_DISASSEMBLE_AFTER_JUMP or
                        SUPPORT_AUTO_DISASSEMBLE_ENTRYPOINT or
                        SUPPORT_DATA_FLOW_ANALYZER,
                maxExecution = TOTAL_EXECUTION_THRESHOLD,
                monitor = taskGlobalMonitor
            ) {
                var score: Int = 0
                val baseAddress: Address = api.toAddr(result.offset)

                @Throws(IllegalArgumentException::class)
                override fun initialization() {
                    MemoryUtil.moveAllBlocksInOffset(program.memory, baseAddress.offset)
                    if (!program.memory.contains(entryPoint))
                        throw IllegalArgumentException("Entry point is not in memory")
                    super.initialization()
                }

                override fun beforeStep(addr: Address, presetDisasmRegs: Map<Register, BigInteger>): Int {
                    val superBehavior = super.beforeStep(addr, presetDisasmRegs)
                    if (superBehavior != NEXT_BEHAVIOR_NORMAL)
                        return superBehavior
                    return NEXT_BEHAVIOR_NORMAL
                }

                override fun finalization() {
                    logger?.info("Executed ${distinctInstExecuted.size} distinct instructions")
                    logger?.info("Executed ${distinctFunctionExecuted.size} distinct functions")

                    score = distinctInstExecuted.size + distinctFunctionExecuted.size * 5
                    if (instExecuted < 50000)
                        score -= 50
                    logger?.info("Score: $score")

                    MemoryUtil.moveAllBlocksInOffset(program.memory, -baseAddress.offset)
                }
            }
            emu.go()
            return ivt.entryPoint!!.offset to emu.score
        } catch (e: Exception) {
            logger.error("Failed to check result through emulation: ${e.message}")
            return -1L to Int.MIN_VALUE
        }
    }

    private fun finalFilter(results: List<BaseMatcherResult>): BaseMatcherResult {
        // Use low-bit to calculate alignment conveniently
        results.sortedBy { r -> (r.offset and -r.offset) * (r.ivtOffset.offset and -r.ivtOffset.offset) } .reversed()
        return results.first()
    }

    fun topMatchesPrettyString(): String {
        return topMatchesPrettyString(SHOW_TOP_COUNT)
    }

    private fun topMatchesPrettyString(count: Int): String {
        val ri: Iterator<BaseMatcherResult> = matchResults.iterator()
        val sb = StringBuilder()
        for (i in 0 until count) {
            sb.append("\t${i+1}:\n")
            if (!ri.hasNext()) break
            val n = ri.next()
            sb.append(n.toString())
            sb.append("\n")
        }

        return sb.toString()
    }

    private fun applyBase(): Boolean {
        // The start address of ivt is the value before memory movement, set ivt entries into pointers
        baseAddress ?: return false
        entryPoint ?: return false

        val ivtStart = ivt?.start ?: return false
        ivt?.let { it.start = it.start.add(baseAddress!!.offset) }

        for (i in 0 ..< ivt!!.entrySize) {
            val p = ivtStart.add(i * 4)
            try {
                // All conflict data in ivt is invalid, eliminate them
                DataUtilities.createData(
                    program, p, PointerDataType.dataType, 4,
                    DataUtilities.ClearDataMode.CLEAR_ALL_CONFLICT_DATA
                )
            } catch (e: Exception) {
                logger.warn("Failed to apply interrupt vector table: ${e.message}")
            }
        }

        // Move the memory and define more functions that could be ignored before the base address is confirmed
        try {
            MemoryUtil.moveAllBlocksInOffset(prog.memory, baseAddress!!.offset)
            restoreFunctionsByReference()
        } catch (e: Exception) {
            logger.error("Failed to move block: ")
            logger.error(e.message)
            return false
        }

        // If entry point does not define any function, define it
        prog.listing.getFunctionAt(entryPoint) ?: run {
            DisasmHelper(prog).disasmFunction(entryPoint!!, monitor = taskGlobalMonitor) ?: run {
                logger.warn("Failed to define entry point function")
            }
        }

        return true
    }

    private fun restoreFunctionsByReference() {
        prog.memory.getAddresses(true).forEach {
            if (api.getInstructionContaining(it) != null)
                return@forEach
            // Find if there is call generated by dead address reference, and define them as functions
            if (prog.referenceManager.getReferencesTo(it).any{ ref -> ref.isValidCodeTarget() }) {
                logger.info("Found possible function start: ${it.addressSpace.name}:${it.offset.toString(16)}")
                DisasmHelper(prog).disasmFunction(it, monitor = taskGlobalMonitor)
            }
        }
    }

    override suspend fun timeoutHandler() {
        if (failureSign == SUCCESS)
            return

        // If we got timeout, then use file header as IVT (if file header seems a valid one)
        logger.warn("Trying to check header as IVT...")
        ivt = fromAddress(prog, prog.memory.minAddress)
        val matcher = BaseMatcher(ivt ?: run {
            logger.error("Analysis failed")
            updateFailureResult()
            failureSign = FAILED
            return
        }, TreeSet(TreeSet(prog.allFunctionStarts()).map { it.offset }), prog, null)

        val headerResult = matcher.matchPossibleResult()
        headerResult ?. let {
            logger.warn("The header looks like an IVT, use it")
            baseAddress = api.toAddr(headerResult.first().offset)
            entryPoint = ivt!!.entryPoint
            applyBase()
            logger.info(String.format("Analyse result for %s:", usingFile.name))
            logger.info(toPrettyString())
            updateSuccessResult()
        } ?: run {
            logger.error("Analysis failed")
            updateFailureResult()
            failureSign = FAILED
        }
    }

    @Throws(NotFoundException::class)
    private suspend fun updateSuccessResult() {
        updateData(mapOf(
            "base_address" to (baseAddress?.offset
                ?: throw NotFoundException("Failed to get base address")),
            "entry_point" to (entryPoint?.offset
              ?: throw NotFoundException("Failed to get entry point")),
            "ivt_start" to ivt!!.start.offset
        ))

        coroutineScope {
            setTaskData("load_metadata", BasicLoadMetadata(
                    baseAddress!!,
                    entryPoint!!,
                    initialSP = api.toAddr(ivt!!.masterStackPointer!!)
                )
            )
            setTaskData("ivt", ivt)
        }
    }

    private fun updateFailureResult() {
        updateErr("No valid base and entry found")
    }

    override fun close() {
        super.close()
        matchResults.clear()
    }

    companion object {
        const val SHOW_TOP_COUNT: Int = 5
        const val MAX_WASHED_DATA_COUNT: Int = 3000
        const val THRESHOLD_TO_IGNORE_UNDEFINED_ENTRYPOINT: Int = 120

        const val ABF_MATCH_FAILED: Int = 0
        const val ABF_MATCH_SUCCESS: Int = 1
        const val ABF_MATCH_IN_FUNC: Int = 2
        const val ABF_MATCH_RESULT_COUNT: Int = 3

        const val TOTAL_EXECUTION_THRESHOLD = 50000L
    }
}
