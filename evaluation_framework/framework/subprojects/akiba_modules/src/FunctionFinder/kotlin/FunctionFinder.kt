package org.iotsplab.akiba.process

import ghidra.features.base.memsearch.bytesource.AddressableByteSource
import ghidra.features.base.memsearch.bytesource.ProgramByteSource
import ghidra.features.base.memsearch.format.SearchFormat
import ghidra.features.base.memsearch.gui.SearchSettings
import ghidra.features.base.memsearch.matcher.RegExByteMatcher
import ghidra.features.base.memsearch.searcher.MemoryMatch
import ghidra.features.base.memsearch.searcher.MemorySearcher
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.data.DataUtilities
import ghidra.program.model.data.Undefined1DataType
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Program
import ghidra.program.model.mem.MemoryAccessException
import ghidra.program.model.util.CodeUnitInsertionException
import ghidra.util.datastruct.ListAccumulator
import ghidra.util.exception.CancelledException
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.structure.ArmcmIVT
import org.iotsplab.akiba.utils.assembly.AsmCodeClearer
import org.iotsplab.akiba.utils.assembly.DisasmHelper
import org.iotsplab.akiba.utils.assembly.isDirectCall
import org.iotsplab.akiba.utils.function.OrphanCodeKiller.process
import org.iotsplab.akiba.utils.function.allFunctionStarts
import org.iotsplab.akiba.utils.function.defaultFunctionEpilogueMatcher
import org.iotsplab.akiba.utils.memory.EnhancedMemorySearcher
import org.iotsplab.akiba.utils.string.RegexSearcher.Companion.REGEX_MAX_SEARCH_COUNT

/**
 * FunctionFinder: Restore thumb functions that could be missed by the auto-defined functions.
 *
 * Detailed process:
 *  - Search in brute force direct call instructions to define some functions in advance, to avoid defining functions
 *    with wrong entry points to some extent
 *  - Check the memory space between functions and try to disassemble and define new functions in brute force
 *  - Check the beginning and the end of the file to try to restore more functions
 *
 * @author: Hornos3, Hornos3@github.com
 */
class FunctionFinder(
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

    private val restoredFunctions = ArrayList<Function>()
    private val matcher = defaultFunctionEpilogueMatcher

    fun clearDefaultTableData() {
        for (externalSymbol in DEFAULT_IVT_SYMBOLS) {
            val symbol = prog.symbolTable.getExternalSymbol(externalSymbol) ?: continue
            val sa = symbol.address
            try {
                DataUtilities.createData(
                    program, sa, Undefined1DataType.dataType, 1,
                    DataUtilities.ClearDataMode.CLEAR_ALL_CONFLICT_DATA
                )
            } catch (_: CodeUnitInsertionException) {}
        }
    }

    @Throws(IllegalArgumentException::class, CancelledException::class)
    override suspend fun startProcess() {
        // defineFunctions()

        digFunctionByDirectCall()
        taskGlobalMonitor.increment()

        // Trying to restore functions before the first auto-defined function
        if (Regex("ARM:(LE|BE):32:.+").matches(prog.languageID.toString())) {
            val headIvt = ArmcmIVT.fromAddress(prog, prog.memory.minAddress)
            if (headIvt != null && headIvt.entrySize > 0x100 / 4)
                digFunctionsBetween(
                    prog.memory.minAddress.add(headIvt.entrySize * 4),
                    prog.listing.getFunctions(true).next().entryPoint)

            taskGlobalMonitor.increment()
            // We don't know if the file start is the ivt, to avoid data conflict, we need to clear the auto-defined addrs.
            clearDefaultTableData()
        }

        taskGlobalMonitor.increment()

        // collect all function start addresses
        val functionStarts = prog.allFunctionStarts()
        if (functionStarts.isEmpty()) {
            logger.error("No function found in this program")
            failureSign = FAILED
        }

        // start filling holes between functions
        // To prevent the ivt been recognized as codes, we delete the analysis of firmware heads
        // digThumbFunctionsBetween(program.memory.minAddress, functionStarts.first)
        digFunctionsBetween(
            prog.listing.getFunctionAt(functionStarts.last()).body.maxAddress.add(1),
            prog.memory.maxAddress.add(1)
        )

        taskGlobalMonitor.increment()

        for (index in 0 until functionStarts.size - 1) {
            // 2 "adjacent" functions, there may be holes in the middle
            val prevStart = functionStarts[index]
            val nextStart = functionStarts[index + 1]
            val prevFunc = prog.listing.getFunctionAt(prevStart) ?: continue
            val prevEnd = prevFunc.body.maxAddress.add(1)
            logger.debug(
                "Prev Function: {} ({} 0x{} - 0x{})",
                prevFunc.name,
                prevEnd.addressSpace,
                prevFunc.body.minAddress.offset.toString(16),
                prevEnd.offset.toString(16)
            )

            val nextFunc = prog.listing.getFunctionAt(nextStart) ?: continue
            logger.debug(
                "Next Function: {} ({} 0x{} - 0x{})",
                nextFunc.name,
                prevEnd.addressSpace,
                nextStart.offset.toString(16),
                nextFunc.body.maxAddress.add(1).offset.toString(16)
            )

            // skip overlapped functions
            if (nextStart < prevEnd) {
                logger.warn("Function overlap detected")
                continue
            }

            logger.debug(String.format("There is a %#x gap", nextStart.subtract(prevEnd)))

            digFunctionsBetween(prevEnd, nextStart)
            taskGlobalMonitor.checkCancelled()
        }

        if (Regex("ARM:(LE|BE):32:.+").matches(prog.languageID.toString()))
            restoreDeadLoop()

        // Define as many functions as possible
        process(prog)
    }

    private fun defineFunctions() {
        prog.listing.getInstructions(true).forEach { inst ->
            if (prog.listing.getFunctionContaining(inst.address) == null)
                FlatProgramAPI(prog).createFunction(inst.address, null)
        }
    }

    @Throws(CancelledException::class)
    private fun digFunctionByDirectCall() {
        var checkPtr = prog.minAddress
        val helper = DisasmHelper(prog)
        val asmChecker = isDirectCall
        while (checkPtr < prog.maxAddress) {
            taskGlobalMonitor.checkCancelled()

            if (prog.listing.getInstructionContaining(checkPtr) != null) {
                checkPtr = checkPtr.add(2)
                continue
            }
            helper.disasmOne(checkPtr, taskGlobalMonitor) ?.let {
                if (asmChecker.test(it)) {
                    val pointTarget: Address = it.getOpObjects(0)[0] as Address
                    prog.listing.getFunctionContaining(pointTarget) ?: run {
                        helper.disasmFunction(pointTarget, monitor = taskGlobalMonitor) ?.let { func ->
                            logger.debug("Find function at ${pointTarget.addressSpace}:" +
                                    "0x${pointTarget.offset.toString(16)}, " +
                                    "size 0x${func.body.numAddresses.toString(16)}")
                        }
                    }
                } else helper.clearOne(checkPtr)
                checkPtr = checkPtr.add(it.length.toLong())
            } ?: run {
                checkPtr = checkPtr.add(2)
            }
        }
    }

    @Throws(CancelledException::class)
    private fun restoreDeadLoop() {
        val byteSource: AddressableByteSource = ProgramByteSource(prog)
        val searcher = MemorySearcher(byteSource, RegExByteMatcher("(\u00FE\u00E7)|(\u00FF\u00F7\u00FE\u00BF)",
            SearchSettings().withSearchFormat(SearchFormat.REG_EX)),
            prog.memory.allInitializedAddressSet, REGEX_MAX_SEARCH_COUNT)
        val results = ListAccumulator<MemoryMatch>()
        searcher.findAll(results, FlatProgramAPI(prog).monitor)
        val resultList = results.asList()

        val helper = DisasmHelper(prog)
        for (result in resultList) {
            taskGlobalMonitor.checkCancelled()

            val addr = result.address
            val d = prog.listing.getDataContaining(addr)
            if (d != null && d.dataType.toString() != "undefined")
                continue
            if (prog.listing.getFunctionAt(addr) != null)
                continue
            helper.disasmFunction(addr, monitor = taskGlobalMonitor)
            val created = prog.listing.getFunctionContaining(addr) != null
            if (created)
                logger.debug("Found dead loop at {}:0x{}, defined as a function",
                    addr.addressSpace, addr.offset.toString(16))
        }
    }

    fun digFunctionsBetween(start: Address, end: Address) {

        val ems = EnhancedMemorySearcher(prog)
        val helper = DisasmHelper(prog)
        var ptr: Address? = try {
            ems.nextAddressNotZero(start, end, 2)
        } catch (_: Exception) {
            null
        } // This exception should not be triggered

        ptr ?: return
        while (ptr!! < end) {
            val d = prog.listing.getDataContaining(ptr)
            if (d != null && d.dataType.toString() != "undefined") {
                ptr = ptr.add(d.length.toLong())
                try {
                    ptr = ems.nextAddressNot(ptr, end, arrayOf(0, -0x1))
                } catch (_: Exception) {
                    assert(false)
                } // This exception should not be triggered
                continue
            }

            // disassemble and create function
            helper.disasmFunction(ptr, monitor = taskGlobalMonitor)
            val created = prog.listing.getFunctionContaining(ptr) != null
            if (!created) {
                logger.debug("Failed to create function at {}:0x{}", ptr.addressSpace, ptr.offset.toString(16))
                val cc = AsmCodeClearer(prog)
                cc.clearCodeStartsWith(ptr)
                ptr = ptr.add(1)
                continue
            } else {
                val newFunction = prog.listing.getFunctionContaining(ptr)
                // skip 1 byte and functions that doesn't match the function epilogue feature
                if (newFunction.body.numAddresses < 2 || !matcher.test(newFunction)) {
                    logger.debug("Function at ${ptr.addressSpace}:0x${ptr.offset.toString(16)} " +
                            "(0x${newFunction.body.numAddresses.toString(16)}) characteristic mismatched, " +
                            "maybe not a function.")

                    val nextPtr = ptr.add(newFunction.body.numAddresses)
                    prog.listing.removeFunction(ptr)
                    prog.listing.clearCodeUnits(ptr, nextPtr.subtract(1), true)
                    ptr = nextPtr
                } else {
                    logger.debug(
                        "Function created at {}:0x{}-0x{}, length = 0x{}",
                        ptr.addressSpace,
                        ptr.offset.toString(16),
                        newFunction.body.maxAddress.offset.toString(16),
                        newFunction.body.numAddresses.toString(16)
                    )
//                    var undefRange = prog.listing.getUndefinedDataRegionAfter(ptr) ?: return
//
//                    while(undefRange.first < newFunction.body.maxAddress &&
//                        undefRange.first.add(undefRange.second - 1) <= newFunction.body.maxAddress) {
//                        if (undefRange.second >= 2) {
//                            println(undefRange.first.toString())
//                            println(undefRange.second.toString(16))
//                            digFunctionsBetween(undefRange.first, undefRange.first.add(undefRange.second))
//                        }
//                        undefRange = prog.listing.getUndefinedDataRegionAfter(
//                            undefRange.first.add(undefRange.second - 1)) ?: return
//                    }

                    try {
                        ptr = ems.nextAddressNotZero(newFunction.body.maxAddress.add(1), end, 4)
                            ?: return
                    } catch (e: MemoryAccessException) {
                        logger.error("Exception occurred: ${e.message}")
                        return
                    }
                }
            }

            try {
                ptr = ems.nextAddressNot(ptr!!, end, arrayOf(0, -0x1))
            } catch (_: Exception) {
                assert(false)
            } // This exception should not be triggered
        }
    }

    companion object {
        val DEFAULT_IVT_SYMBOLS: Array<String> = arrayOf(
            "Reset", "NMI", "HardFault", "MemManage", "BusFault", "UsageFault", "Reserved1", "Reserved2",
            "Reserved3", "Reserved4", "SVCall", "Reserved5", "Reserved6", "PendSV", "SysTick", "IRQ"
        )
        const val DEFAULT_GAP_CHECK_THRESHOLD: Long = 0x10000
    }
}
