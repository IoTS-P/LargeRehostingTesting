package org.iotsplab.akiba.utils.emulator

import ghidra.app.emulator.DefaultEmulator
import ghidra.app.emulator.EmulatorHelper
import ghidra.pcode.memstate.MemoryFaultHandler
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.lang.RegisterValue
import ghidra.program.model.listing.Program
import ghidra.util.task.TaskMonitor
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.utils.memory.MemoryUtil
import org.iotsplab.akiba.utils.memory.MemoryUtil.Companion.getPageStartUnchecked
import java.io.Closeable
import java.math.BigInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * 模拟器类。
 * 通过 Ghidra 默认模拟器模拟 Ghidra 程序的执行。
 *
 * @property entryPoint 模拟的入口点。
 * @property stackTop 模拟的初始栈顶地址。
 */
open class Emulator(
    protected val program: Program,
    var entryPoint: Address,
    var stackTop: Address,
    protected val logger: Logger? = null
) : Closeable {
    /**
     * 模拟器上下文。
     */
    lateinit var context: EmulatorHelper

    /**
     * 使用的 Ghidra 模拟器
     */
    lateinit var emulator: DefaultEmulator

    /**
     * Ghidra Program API，用于快捷访问。
     */
    lateinit var api: FlatProgramAPI

    /**
     * 默认内存错误处理器：创建导致内存错误的地址并将其视为可访问
     * 在默认处理器中，0 页/最高页的读/写指令是被禁止的。
     */
    protected var mfh: MemoryFaultHandler = object : MemoryFaultHandler {
        @Throws(IllegalStateException::class)
        override fun uninitializedRead(addr: Address, size: Int, buf: ByteArray, bufOffset: Int): Boolean {
            if (addr.addressSpace.isRegisterSpace) {
                context.writeMemory(addr, ByteArray(4) { 0x00.toByte() })
                return true
            }

            check(!MemoryUtil.addrIsInvalid(addr)) { "Got invalid address access: 0x${addr.offset.toString(16)}" }
            val pageIncluded = MemoryUtil.getPagesIncluding(addr, size.toLong())
            var unmappedPage = pageIncluded.first
            while (unmappedPage != pageIncluded.second.add(0x1000)) {
                emulator.memState.getMemoryBank(addr.addressSpace).let {
                    it.setInitialized(unmappedPage.offset, it.pageSize, true)
                    context.writeMemoryValue(unmappedPage, 0x1000, 0)
                    unmappedPage = unmappedPage.add(0x1000)
                }
            }
            return true
        }

        @Throws(IllegalStateException::class)
        override fun unknownAddress(addr: Address, isWrite: Boolean): Boolean {
            check(!MemoryUtil.addrIsInvalid(addr)) { "Got invalid address access: 0x${addr.offset.toString(16)}" }
            val pageIncluded = addr.getPageStartUnchecked()
            context.createMemoryBlockFromMemoryState(
                "ADDED-${pageIncluded.offset.toString(16)}", pageIncluded, 0x1000,
                true, TaskMonitor.DUMMY)
            context.writeMemoryValue(pageIncluded, 0x1000, 0)
            return true
        }
    }

    /**
     * 模拟器初始化。考虑到不同架构可能需要不同的初始化逻辑，在初始化过程中会调用架构特定的初始化函数（如果有）。
     */
    @Throws(UnsupportedOperationException::class)
    open fun initialization() {
        context = EmulatorHelper(program)
        emulator = context.emulator as DefaultEmulator
        api = FlatProgramAPI(program)

        context.memoryFaultHandler = mfh
        archSpecifiedInit[program.languageID.toString()] ?.let { it(context) }
        context.writeRegister(context.stackPointerRegister, stackTop.offset)
        emulator.setExecuteAddress(entryPoint.offset)
    }

    /**
     * 开始模拟执行。
     * 执行单条指令直到完成或发生错误。
     */
    open fun startEmulation() {
        try {
            emulator.executeInstruction(true, TaskMonitor.DUMMY)
        } catch (e: Exception) {
            logger?.error("Emulation aborted: ${e.message}")
        }
    }

    /**
     * 最终化处理。
     * 可在子类中重写以添加清理逻辑。
     */
    open fun finalization() {}

    /**
     * 启动模拟器。
     * 按顺序执行初始化、模拟和最终化步骤。
     */
    open fun go() {
        try {
            initialization()
            startEmulation()
            finalization()
        } catch (e: Exception) {
            logger?.error("Emulation error: ${e.message}")
        }
    }

    override fun close() {}

    companion object {
        private var idLock: ReentrantLock = ReentrantLock()
        private var id = 0

        /**
         * 架构特定的初始化函数映射。
         * 键为架构标识符，值为初始化闭包。
         */
        val archSpecifiedInit: Map<String, (EmulatorHelper) -> Unit> = mapOf(
            "ARM:LE:32:Cortex" to { emu ->
                emu.writeRegister("TB", 1)
                // emu.contextRegister = RegisterValue(emu.language.getRegister("TMode"), 1.toBigInteger())
            }
        )
    }
}