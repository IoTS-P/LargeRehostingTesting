package org.iotsplab.akiba.process.structure

import ghidra.program.model.address.Address
import ghidra.program.model.address.AddressOutOfBoundsException
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.module.ModuleContext
import org.iotsplab.akiba.process.ProgramServer
import org.iotsplab.akiba.utils.function.allFunctionStarts
import java.util.*
import kotlin.coroutines.coroutineContext
import kotlin.math.max

class BaseMatcher(private var ivt: ArmcmIVT?, functionStarts: TreeSet<Long>? = null,
                  program: Program, private val overallSubMap: Map<Address, Set<Long>>? = null) {

    private var functionStarts = functionStarts ?: TreeSet(program.allFunctionStarts()).map { it.offset }
    private var addressMap = HashMap<Long, Int>()

    private val entries: List<Long>?
        get() = ivt?.entries?.values?.toList()

    private val ivtStart: Address?
        get() = ivt ?.start

    fun setIvt(ivt: ArmcmIVT) {
        this.ivt = ivt
        updateAddressMap()
    }

    private fun updateAddressMap() {
        this.addressMap = getAddressMap() ?: return
    }

    private fun getAddressMap(): HashMap<Long, Int>? {
        return getAddressMap(this.ivt ?: return null)
    }

    private fun getAddressMap(ivt: ArmcmIVT): HashMap<Long, Int>? {
        overallSubMap ?: run { return null }
        val addressMap = HashMap<Long, Int>()
        var a = ivt.start
        for (i in 0..<ivt.entrySize) {
            for (entry in overallSubMap[a]!!) {
                addressMap.putIfAbsent(entry, 0)
                addressMap[entry] = addressMap[entry]!! + 1
            }
            a = a.add(4)
        }
        return addressMap
    }

    suspend fun matchPossibleResult(totalMatchThreshold: Int = BASE_MATCHER_DEFAULT_THRESHOLD,
                                    totalMatchRatioThreshold: Double = BASE_MATCHER_DEFAULT_MATCHED_RATIO_THRESHOLD,
                                    uniqueMatchThreshold: Int = BASE_MATCHER_DEFAULT_UNIQUE_THRESHOLD,
                                    scoreThreshold: Int = BASE_MATCHER_DEFAULT_SCORE_THRESHOLD,
                                    compulsoryAlignment: Int = BASE_MATCHER_FORCE_ALIGNMENT,
                                    inputIvt: ArmcmIVT? = null,
                                    memoryAddressBias: Int = 0)
    : TreeSet<BaseMatcherResult>? {
        val results = TreeSet{ baseMatcherResult: BaseMatcherResult, t1: BaseMatcherResult ->
            -baseMatcherResult.compareTo(t1)
        } // sort in desc order

        // O(n^2)
        // E.g: 0x8000100 is in inputIvt, there is a function starts at 0x100
        // offset = 0x100 - 0x8000100 = -0x8000000
        // 0x8000200 (one_entry) is also in inputIvt, test whether there is a function starting at 0x200
        // one_entry + (-0x8000000) = 0x200
        // If (one_entry + offset) triggers underflow, then the value mustn't be a valid address

        // If we pre-calculated the overallSubMap, we can directly use it
        overallSubMap ?: run {
            // Fill the hashmap
            for (entry in entries!!) {
                for (funcStart in functionStarts) {
                    val offset = entry - funcStart
                    addressMap.putIfAbsent(offset, 0)
                    addressMap[offset] = addressMap[offset]!! + 1
                }
            }
        }

        val amap = inputIvt ?. let { getAddressMap(inputIvt) } ?: addressMap

        // Remove useless offsets
        amap.entries.removeIf { entry: Map.Entry<Long, Int> -> entry.value < totalMatchThreshold }
        amap.entries.removeIf { entry: Map.Entry<Long, Int> ->
            (entry.key - memoryAddressBias) % compulsoryAlignment != 0.toLong() }

        // Build results of different offsets
        val mspLowerBound = functionStarts.first()
        val mspUpperBound = functionStarts.last()
        for (offset in amap.keys) {
            val containMap: MutableMap<Long, Int> = TreeMap()
            val matched = ArrayList<Long>()
            var unmatchedCount = 0
            var score = 0
            var maxScore = 0
            var maxMatchedRatio = 0.0
            var punishment = 0
            // For the reward of matching reserved entries
            if (inputIvt?.reservedEntriesEmpty ?: ivt!!.reservedEntriesEmpty)   score += 75
            // For the reward of correct alignment of stack pointer
            if ((inputIvt?.masterStackPointer ?: ivt!!.masterStackPointer!!) % 0x1000L == 0L)   score += 30
            ivt = inputIvt ?: ivt!!
            for (ivtEntry in (ivt!!.entries.toList().sortedBy { it.first }.map { it.second }) ) {
                try {
                   val bias = ivtEntry - offset
                    // masterStackPointer cannot be inside a function
                    if (ivtEntry == ivt!!.masterStackPointer && bias >= mspLowerBound && bias <= mspUpperBound)
                        return null
                    if (functionStarts.contains(bias)) {
                        matched.add(ivtEntry)
                        score += 2
                    } else {
                        val targetFunction = coroutineContext[ModuleContext.Key]!!.call(
                            ProgramServer::getFunctionContaining, bias)
                        if (targetFunction != null) {
                            val funcStart = coroutineContext[ModuleContext.Key]!!.call(
                                ProgramServer::getFunctionStart, targetFunction) as Long        // Use it as key
                            if (containMap.containsKey(funcStart)) {
                                containMap[funcStart] = containMap[funcStart]!! + 1
                            } else containMap[funcStart] = 1
                            score += 2 - containMap[funcStart]!!
                            unmatchedCount++
                        } else {
                            score -= punishment++
                            unmatchedCount++
                        }
                    }   // We need stricter punishment for unmatched ones
                } catch (ignored: AddressOutOfBoundsException) { unmatchedCount++ }
                maxScore = max(score.toDouble(), maxScore.toDouble()).toInt()
                // Start to calculate the matched ratio when we got enough matches, to avoid sharp changes
                if (matched.size >= totalMatchThreshold)
                    maxMatchedRatio = max(maxMatchedRatio,
                        matched.size.toDouble() / (matched.size + unmatchedCount))
            }
            if (matched.distinct().size >= uniqueMatchThreshold && maxScore >= scoreThreshold &&
                maxMatchedRatio >= totalMatchRatioThreshold)
                results.add(BaseMatcherResult(offset, inputIvt?.start ?: ivt!!.start,
                    matched, maxScore, maxMatchedRatio, (inputIvt?.entrySize ?: ivt!!.entrySize) * 4))
        }

        return if (results.isEmpty()) null else results
    }

    companion object {
        const val BASE_MATCHER_DEFAULT_THRESHOLD: Int = 10
        const val BASE_MATCHER_DEFAULT_MATCHED_RATIO_THRESHOLD: Double = 0.7
        const val BASE_MATCHER_DEFAULT_UNIQUE_THRESHOLD: Int = 10
        const val BASE_MATCHER_DEFAULT_SCORE_THRESHOLD: Int = 5
        const val BASE_MATCHER_FORCE_ALIGNMENT: Int = 0x100
    }
}
