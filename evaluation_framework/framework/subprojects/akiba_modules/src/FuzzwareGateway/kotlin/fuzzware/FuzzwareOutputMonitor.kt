package org.iotsplab.akiba.process.fuzzware

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Logger

class FuzzwareOutputMonitor (
    private val process: Process,
    private val monitorInterrupts: Boolean = false,
    private val logger: Logger
) {
    private var errorMsg: MutableList<String> = mutableListOf()
    // Address of interrupt function entrances
    private val interruptStack: MutableList<Long> = mutableListOf()
    private var errRegOutputFlag: Boolean = false
    private var errRegisterContext: MutableMap<String, Long> = mutableMapOf()
    private var coveredFunctions: MutableSet<Long> = mutableSetOf()
    private var coveredBasicBlocks: MutableSet<Long> = mutableSetOf()
    val errRegContext: Map<String, Long>
        get() = errRegisterContext

    private val regValueRegex = Regex("([0-9a-z_]+): 0x([0-9a-f]+)")

    private data class CallInfo (
        val callTarget: Long,
        val lr: Long            // If the call is an interruption, will be a negative value
    )

    private data class BasicBlockInfo (
        val addr: Long,
        val lr: Long
    )

    fun getInterruptStack(): List<Long> = interruptStack
    fun getErrMsgList(): List<String> = errorMsg
    fun getErrMsg(): String? = if (errorMsg.isEmpty()) errorMsg.joinToString("\n") else null
    fun getFunctionCoverage(allFuncList: Collection<Long>): Double {
        val augmentedFuncList = allFuncList.toMutableSet()
        augmentedFuncList.addAll(coveredFunctions.filter { !augmentedFuncList.contains(it) })
        return coveredFunctions.size.toDouble() / augmentedFuncList.size
    }
    fun getBasicBlockCoverage(allBasicBlocks: Collection<Long>): Double {
        val augmentedBasicBlocks = allBasicBlocks.toMutableSet()
        augmentedBasicBlocks.addAll(coveredBasicBlocks.filter { !augmentedBasicBlocks.contains(it) })
        return coveredBasicBlocks.size.toDouble() / augmentedBasicBlocks.size
    }

    @Throws(IllegalStateException::class)
    suspend fun monitorTillExit() = coroutineScope {
        val outReader = launch {
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line ?: continue
                    logger.trace(line)
                    getCallInfo(line) ?. let { coveredFunctions.add(it.callTarget) }

                    if (errRegOutputFlag) {
                        if (regValueRegex.matches(line)) {
                            val match = regValueRegex.find(line)!!.groupValues
                            errRegisterContext[match[1]] = match[2].toLong(16)
                        } else
                            errRegOutputFlag = false
                    }

                    if (isErrLine(line)) {
                        logger.warn("Fuzzware error: $line")
                        errorMsg.add(line)
                    } else if (line.contains("Exit without crash")) {
                        logger.info("Fuzzware exited without crash")
                        errorMsg.add("exit without crash")
                    } else if (line.startsWith("==== UC Reg state ====")) {
                        errRegOutputFlag = true
                    }

                    if (monitorInterrupts) {
                        getCallInfo(line) ?. let {
                            if (it.lr == INTERRUPT_LR)
                                interruptStack.add(it.callTarget)
                        } ?: run {
                            getBasicBlockInfo(line) ?. let {
                                if (it.lr == INTERRUPT_EXIT_BLOCK) {
                                    if (interruptStack.isEmpty())
                                        throw IllegalStateException("Interrupt state error")
                                    else
                                        interruptStack.removeLast()
                                }
                            }
                        }
                    }
                }
            }
        }

        outReader.start()
        outReader.join()
    }

    private fun isErrLine(line: String): Boolean {
        return line.contains("INVALID READ") ||
               line.contains("INVALID Write") ||
               line.contains("Invalid memory read") ||
               line.contains("Invalid memory write") ||
               line.contains("INVALID FETCH") ||
               line.contains("Execution failed with error code") ||
               line.contains("Emulation stopped using just the prefix input") ||
               line.contains("ERROR:emulator:")
    }

    private fun isCallIndicator(line: String): Boolean {
        return line.startsWith("Calling function: ")
    }

    private fun getCallInfo(line: String): CallInfo? {
        if (!isCallIndicator(line))
            return null
        val regex = Regex("Calling function: .+\\(PC.+0x([0-9a-f]+), LR.+0x([0-9a-f]+)\\)")
        return regex.find(line)!!.let {
            val match = it.groupValues
            CallInfo(
                callTarget = match[1].toLong(16),
                lr = match[2].toLong(16)
            )
        }
    }

    private fun isBasicBlockIndicator(line: String): Boolean {
        return line.startsWith("Basic Block: addr= ")
    }

    private fun getBasicBlockInfo(line: String): BasicBlockInfo? {
        if (!isBasicBlockIndicator(line))
            return null
        val regex = Regex("Basic Block: addr= 0x([0-9a-f]+) \\(lr=0x([0-9a-f]+)\\)")
        return regex.find(line)!!.let {
            val match = it.groupValues
            coveredBasicBlocks.add(match[1].toLong(16))
            BasicBlockInfo(
                addr = match[1].toLong(16),
                lr = match[2].toLong(16)
            )
        }
    }

    companion object {
        const val INTERRUPT_LR: Long = 0xfffffff9
        const val INTERRUPT_EXIT_BLOCK: Long = 0xfffffff8
    }
}
