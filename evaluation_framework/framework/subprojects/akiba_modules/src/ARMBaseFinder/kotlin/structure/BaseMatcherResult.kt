package org.iotsplab.akiba.process.structure

import ghidra.program.model.address.Address
import org.iotsplab.akiba.process.structure.ArmcmIVT.Companion.fromAddress

class BaseMatcherResult : Comparable<BaseMatcherResult> {
    @JvmField
    var offset: Long
    lateinit var ivtOffset: Address
    @JvmField
    var matchedFuncs: ArrayList<Long>
    var maxMatchedRatio: Double = 0.0
    var maxScore: Int = 0
    var ivtSize: Long = 0

    constructor(offset: Long, matchedFuncs: ArrayList<Long>) {
        this.offset = offset
        this.matchedFuncs = matchedFuncs
    }

    constructor(
        offset: Long, ivtOffset: Address, matchedFuncs: ArrayList<Long>,
        maxScore: Int
    ) : this(offset, matchedFuncs) {
        this.ivtOffset = ivtOffset // IVT memory address
        this.maxScore = maxScore // Some address may repeat
    }

    constructor(
        offset: Long, ivtOffset: Address, matchedFuncs: ArrayList<Long>,
        maxScore: Int, maxMatchedRatio: Double, ivtSize: Long
    ) : this(offset, ivtOffset, matchedFuncs, maxScore) {
        this.maxMatchedRatio = maxMatchedRatio
        this.ivtSize = ivtSize
    }

    override fun compareTo(other: BaseMatcherResult): Int {
        return if (this.maxScore != other.maxScore) maxScore - other.maxScore
        else if (matchedFuncs.size != other.matchedFuncs.size) matchedFuncs.size - other.matchedFuncs.size
        else if (this.offset != other.offset) (this.offset - other.offset).toInt()
        else -(ivtOffset.subtract(other.ivtOffset)).toInt() // we prefer smaller offset
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(
            String.format(
                "%#010x (IVT offset %#010x), score ${maxScore}, ${matchedFuncs.size} matches: \n",
                offset, ivtOffset.offset
            )
        )
        for (i in matchedFuncs.indices) {
            val addr = matchedFuncs[i]
            if (i % 5 == 0) sb.append("\t")
            sb.append(String.format("%#x ", addr))
            if (i % 5 == 4) sb.append("\n")
        }
        sb.append("\n")
        return sb.toString()
    }
}
