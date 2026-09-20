package org.iotsplab.akiba.process

import ghidra.program.model.address.Address
import ghidra.program.model.address.AddressIterator
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.TaskInterface

/**
 * ProgramServer: A tool class for speeding up api calls.
 *
 * Mechanism: The Ghidra API `getFunctionContaining` is time-consuming, so use a preset map to speed up the search.
 *
 * @author: Hornos3, Hornos3@github.com
 */
@IgnoreRuntimeTimeout
class ProgramServer (
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
    private val funcAddrMap: HashMap<Long, Function> = HashMap()
    private val funcStartMap: HashMap<Function, Long> = HashMap()

    // You must auto-analyze the program before calling this method !!!
    override suspend fun startProcess() {
        // create hashmap for functions
        logger.info("Creating function map...")
        val addressIterator: AddressIterator = prog.memory.getAddresses(true)
        while (addressIterator.hasNext()) {
            val addr: Address = addressIterator.next()
            val function = prog.listing.getFunctionContaining(addr)
            funcAddrMap[addr.offset] = function
            function ?. let { funcStartMap[it] = it.entryPoint.offset }
        }
        logger.info("Function map created.")
    }

    @TaskInterface
    fun getFunctionContaining(address: Long): Function? {
        return funcAddrMap[address]
    }

    @TaskInterface
    fun getFunctionStart(function: Function): Long {
        return funcStartMap[function]!!
    }
}