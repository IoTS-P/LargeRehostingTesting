package org.iotsplab.akiba.process.structure

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.data.PointerDataType
import ghidra.program.model.data.StringDataType
import ghidra.program.model.listing.Program
import ghidra.program.model.mem.MemoryAccessException
import org.iotsplab.akiba.process.ARMBaseFinder
import java.util.*
import kotlin.math.abs

class ArmcmIVT private constructor(address: Address, entrySize: Long) {
    var start: Address = address
    var entrySize: Long = 0
        private set
    @JvmField
    val entries: MutableMap<Int, Long> = mutableMapOf() // <entry idx (0, 4, 8, ...), addr value>
    private var program: Program? = null
    private var api: FlatProgramAPI? = null
    var reservedEntriesEmpty: Boolean = true
    var minSize: Int = -1

    init {
        this.entrySize = entrySize
    }

    val uniqueEntrySize: Long
        get() = entries.size.toLong()

    val uniqueEntries: HashMap<Long, Int>
        get() {
            val result = HashMap<Long, Int>()
            for (e in entries.values) {
                result.putIfAbsent(e, 0)
                result[e] = result[e]!! + 1
            }
            return result
        }

    val masterStackPointer: Long?
        get() = program?.memory?.getInt(start)?.toLong()?.shr(1)?.shl(1)
            ?: run {
                require(false) { "Program required to get masterStackPointer" }
                return null
            }

    @get:Throws(MemoryAccessException::class)
    val entryPoint: Address?
        get() {
            return api!!.toAddr(
                program!!.memory
                    .getInt(start.add((4 * IVT_IDX_RESET).toLong())) - 1
            )
        }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Interrupt Vector Table:")
            .append(System.lineSeparator())
            .append("\tStart Address: 0x${start.offset.toString(16)}${System.lineSeparator()}")
            .append("\tEntry Size: ${entrySize}${System.lineSeparator()}")
            .append("\tByte Size: 0x${(entrySize * 4).toString(16)}${System.lineSeparator()}")
            .append("\tValid entries: ${entries.size}:${System.lineSeparator()}")

        for ((counter, v) in entries.values.withIndex()) {
            if (counter % 5 == 0) sb.append("\t")
            sb.append("0x${v.toString(16)} ")
            if (counter % 5 == 4) sb.append(System.lineSeparator())
        }

        return sb.toString()
    }

    fun getLastMatchOffset(result: Map<Long?, Int?>) {
        minSize = entries.map { (offset, value) ->
            if (result.containsKey(value)) offset else -1
        } .filter { it != -1 } .maxOrNull() ?: entrySize.toInt()
    }

    fun adjustWithMatchResult(result: Map<Long?, Int?>, program: Program? = null) {
        val prog = program ?: this.program
        check(prog != null) { "Program required to adjust" }

        entries.toList().sortedBy { (off, _) -> off }.reversed().forEach { (offset, value) ->
            if ((result[value] ?: ARMBaseFinder.ABF_MATCH_FAILED) == ARMBaseFinder.ABF_MATCH_FAILED) {
                entries.remove(offset)
                entrySize--
            } else {
                entrySize = offset / 4L + 1
                return
            }
        }
    }

    fun toStringWithMatchResult(result: Map<Long?, Int?>): String {
        getLastMatchOffset(result)

        var s = """
            Interrupt Vector Table:
                Start Address: 0x${start.offset.toString(16)}
                Entry Size: ${entrySize}${System.lineSeparator()}
                Byte Size: 0x${(entrySize * 4).toString(16)}
                Valid entries: ${entries.size}:
            
        """.trimIndent()

        var indentCounter = 0
        val matchTypeCounter = IntArray(ARMBaseFinder.ABF_MATCH_RESULT_COUNT)
        entries.forEach { (offset, value) ->
            if (indentCounter % 5 == 0)     s += "\t\t"
            var sign = "?"
            if (result.containsKey(value)) {
                when (result[value]) {
                    ARMBaseFinder.ABF_MATCH_FAILED -> sign = "x"
                    ARMBaseFinder.ABF_MATCH_SUCCESS -> sign = "+"
                    ARMBaseFinder.ABF_MATCH_IN_FUNC -> sign = "*"
                }
                matchTypeCounter[result[value]!!]++
            }
            s += "(0x${offset.toString(16)})0x${value.toString(16)}(${sign}) "
            if (indentCounter % 5 == 4)     s += System.lineSeparator()
            indentCounter++
        }

        if (indentCounter % 5 != 0) s += System.lineSeparator()

        s += """
            Success: ${matchTypeCounter[ARMBaseFinder.ABF_MATCH_SUCCESS]}
            Failed: ${matchTypeCounter[ARMBaseFinder.ABF_MATCH_FAILED]}
            In function but not function start: ${matchTypeCounter[ARMBaseFinder.ABF_MATCH_IN_FUNC]}
            
        """.trimIndent()

        return s
    }

    companion object {
        const val IVT_DEFAULT_MAX_SIZE: Int = 0x200 / 4
        const val IVT_DEFAULT_MAX_RANGE_COUNT: Int = 3
        const val IVT_DEFAULT_MAX_RANGE_BIAS: Int = 0x10
        const val IVT_DEFAULT_MIN_VALUE_INCLUDED: Int = 15
        const val IVT_DEFAULT_MAIN_HWORD_RATIO: Double = 0.8

        const val IVT_IDX_RESET: Int = 1
        val reservedOffsets: List<Long> = listOf(7*4, 8*4, 9*4, 10*4, 13*4)

        @JvmStatic
        fun fromAddress(
            program: Program, address: Address,
            maxSize: Int = IVT_DEFAULT_MAX_SIZE,
            maxRangeCount: Int = IVT_DEFAULT_MAX_RANGE_COUNT,
            maxRangeBias: Int = IVT_DEFAULT_MAX_RANGE_BIAS,
            minValueIncluded: Int = IVT_DEFAULT_MIN_VALUE_INCLUDED
        ): ArmcmIVT? {
            if (program.listing.getFunctionContaining(address) != null) return null

            val ivt = ArmcmIVT(address, 0)
            ivt.program = program
            ivt.api = FlatProgramAPI(program)

            // Record the value of higher 2 bytes of possible entries,
            // given that function entries has equal or similar value of higher 2 bytes.
            val recordedHword = TreeMap<Long, Int>()

            var nextAddr = address

            while (true) {
                // check for data type
                if (program.listing.getFunctionContaining(nextAddr) != null) break
                val data = program.listing.getDataContaining(nextAddr)
                if (data != null && data.dataType.isEquivalent(StringDataType.dataType)) break

                var nextVal: Int
                // If memory not mapped, stop iteration
                try {
                    nextVal = program.memory.getInt(nextAddr)
                } catch (_: MemoryAccessException) {
                    break
                }

                // Check the first entry (Master stack pointer), it cannot be too small
                if (nextAddr == address && nextVal < 0x1000)
                    return null

                // skip possible null pointer
                if (nextVal == 0 || nextVal == -0x1) {
                    // null pointer cannot be the first entry of interrupt vector table
                    if (nextAddr == address) {
                        return null
                    }
                    nextAddr = nextAddr.add(4)
                    ++ivt.entrySize
                    continue
                } else if (reservedOffsets.contains(nextAddr.subtract(address)))
                    ivt.reservedEntriesEmpty = false

                // check the range and add the pointer into entry list
                var loopOver = false
                val hw = recordedHword.keys.firstOrNull {
                    v: Long -> abs((v - (nextVal shr 16)).toDouble()) < maxRangeBias }
                hw ?. let {
                    recordedHword[hw] = recordedHword[hw]!! + 1
                } ?: run {
                    if (recordedHword.size < maxRangeCount) recordedHword.put((nextVal shr 16).toLong(), 1)
                    else loopOver = true
                }
                if (loopOver)
                    break

                ivt.entries[nextAddr.subtract(address).toInt()] = ((nextVal shr 1) shl 1).toLong() // clear LSB

                // goto next address and update the table size
                nextAddr = nextAddr.add(4)
                ++ivt.entrySize

                // end the cycle while ceil got
                if (ivt.entrySize >= maxSize) break
            }

            if (ivt.entries.size < minValueIncluded) return null
            // if (recordedHword.values.none { it * 1.0 / ivt.entries.size > IVT_DEFAULT_MAIN_HWORD_RATIO }) return null
            return ivt
        }

        @JvmStatic
        fun fromDefined(program: Program, start: Address): ArmcmIVT? {
            val ivt = ArmcmIVT(start, 0)
            ivt.api = FlatProgramAPI(program)
            ivt.program = program
            var idx = 0
            while (true) {
                val data = program.listing.getDataAt(start)
                if (data != null && data.dataType.isEquivalent(PointerDataType.dataType) && data.length == 4) {
                    ivt.entries[idx * 4] = program.memory.getInt(data.address).toLong()
                    idx++
                } else break
            }
            ivt.entrySize = idx.toLong()
            return ivt
        }
    }
}
