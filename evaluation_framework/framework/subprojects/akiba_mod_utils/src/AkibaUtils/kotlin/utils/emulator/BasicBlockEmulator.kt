package org.iotsplab.akiba.utils.emulator

import ghidra.app.decompiler.DecompInterface
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import ghidra.util.task.TaskMonitor
import org.apache.logging.log4j.Logger

/**
 * 基本块模拟器。
 * 通过在每个基本块的开始和结束处中断来模拟程序执行。
 *
 * 基本块通过由高级 P-code 组成的高级函数获取。
 *
 * 在此类中，我们提供了一组处理器来控制模拟器的行为，你可以继承此类并重写
 * `blockStartHandler` 和 `blockEndHandler` 以便在基本块开始/结束时执行一些操作。
 */
open class BasicBlockEmulator(
    program: Program,
    entryPoint: Address,
    stackTop: Address,
    logger: Logger? = null,
    protected var maxExecution: Int = Int.MAX_VALUE     // 执行的基本块数量
) : Emulator(program, entryPoint, stackTop, logger) {
    /**
     * 执行的基本块数量。
     */
    protected var blockExecuted: Int = 0

    /**
     * 已执行的函数集合。
     */
    protected val funcExecuted: HashSet<Address> = hashSetOf()

    /**
     * 动态存储所有基本块结束的地址，并作为断点使用。
     */
    protected val endBreakpoints: HashSet<Address> = hashSetOf()

    /**
     * 动态存储所有基本块开始的地址，并作为断点使用。
     */
    protected val startBreakpoints: HashSet<Address> = hashSetOf()

    /**
     * 反编译接口实例。
     */
    protected val decompInterface: DecompInterface = getDecompiler()

    /**
     * 获取反编译器实例。
     *
     * @return 配置好的反编译器对象。
     * @throws IllegalStateException 如果无法打开程序进行反编译。
     */
    private fun getDecompiler(): DecompInterface {
        val decompInterface = DecompInterface()
        decompInterface.toggleSyntaxTree(true)
        if (!decompInterface.openProgram(program))
            throw IllegalStateException("ERROR: Failed to open program: ${decompInterface.lastMessage}")
        return decompInterface
    }

    /**
     * 基本块开始处理器。
     * 在每个基本块开始执行时调用，可被子类重写以添加自定义行为。
     *
     * @throws Exception 如果处理过程中发生错误。
     */
    @Throws(Exception::class)
    open fun blockStartHandler() {
        logger?.trace("Block start handler")
    }

    /**
     * 基本块结束处理器。
     * 在每个基本块执行结束时调用，可被子类重写以添加自定义行为。
     *
     * @throws Exception 如果处理过程中发生错误。
     */
    @Throws(Exception::class)
    open fun blockEndHandler() {
        logger?.trace("Block end handler")
    }

    override fun startEmulation() {
        try {
            while (blockExecuted < maxExecution) {
                logger?.trace("Break at {}", context.pc)
                if (startBreakpoints.contains(context.pc))
                    blockStartHandler()
                val currentFunc = api.getFunctionContaining(context.pc)
                    ?: throw IllegalStateException("Orphan code detected at ${context.pc}")
                // 我们动态添加断点，当执行新函数时，为所有基本块添加断点
                if (!funcExecuted.contains(currentFunc.entryPoint)) {
                    funcExecuted.add(currentFunc.entryPoint)
                    val highFunc = decompInterface.decompileFunction(
                        currentFunc, DECOMPILE_TIMEOUT, TaskMonitor.DUMMY).highFunction
                    highFunc.basicBlocks.forEach { block ->
                        // 为所有基本块的开始设置断点
                        if (!startBreakpoints.contains(block.start)) {
                            startBreakpoints.add(block.start)
                            logger?.debug("Added breakpoint at block start: {}", block.start)
                            // 避免重复断点
                            if (!endBreakpoints.contains(block.start))
                                context.setBreakpoint(block.start)
                        }
                        // 为所有基本块的结束设置断点
                        if (!endBreakpoints.contains(block.stop)) {
                            endBreakpoints.add(block.stop)
                            logger?.debug("Added breakpoint at block end: {}", block.stop)
                            // 避免重复断点
                            if (!startBreakpoints.contains(block.stop))
                                context.setBreakpoint(block.stop)
                        }
                    }
                }
                if(!context.run(TaskMonitor.DUMMY)) {
                    logger?.error("Error detected: ${context.lastError}")
                    break
                }
                if (endBreakpoints.contains(context.pc)) {
                    logger?.trace("A block is executed to its end")
                    blockExecuted++
                }
                if (endBreakpoints.contains(context.pc))
                    blockEndHandler()
            }
        } catch (e: Exception) {
            logger?.error("Emulation aborted: ${e.message}")
        }
    }

    companion object {
        /**
         * 反编译超时时间（秒）。
         */
        const val DECOMPILE_TIMEOUT: Int = 60
    }
}