package org.iotsplab.akiba.utils.assembly

import ghidra.app.cmd.register.SetRegisterCmd
import ghidra.app.plugin.core.analysis.AutoAnalysisManager
import ghidra.program.disassemble.Disassembler
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.address.AddressSet
import ghidra.program.model.lang.Register
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Instruction
import ghidra.program.model.listing.Program
import ghidra.util.task.TaskMonitor
import java.math.BigInteger

/**
 * 反汇编辅助类。
 * 提供单条指令反汇编和函数级别反汇编的功能，支持寄存器预设。
 *
 * @param program Ghidra 程序对象。
 */
class DisasmHelper(private val program: Program) {
    private val api = FlatProgramAPI(program)
    private val aam: AutoAnalysisManager = AutoAnalysisManager.getAnalysisManager(program)

    /**
     * 反汇编单条指令。
     *
     * @param address 要反汇编的地址。
     * @param monitor 任务监视器。
     * @return 反汇编得到的指令对象，如果失败则返回 null。
     */
    fun disasmOne(address: Address, monitor: TaskMonitor): Instruction? {
        val disassembler = Disassembler.getDisassembler(program, monitor, null)
        val targetSet = AddressSet(address)
        disassembler.disassemble(address, targetSet, false)
        return program.listing.getInstructionContaining(address)
    }

    /**
     * 反汇编函数。
     * 从指定地址开始反汇编并创建函数，支持预设寄存器值以指导反汇编过程。
     *
     * @param start 函数起始地址。
     * @param presetRegisterValues 预设寄存器值映射。某些架构需要控制寄存器值来指导反汇编，
     *        如 ARM Cortex-M 的 TMode 寄存器用于确定是否使用 Thumb 模式。
     * @param monitor 任务监视器。
     * @return 创建的函数对象，如果失败则返回 null。
     */
    fun disasmFunction(start: Address,
                       presetRegisterValues: Map<Register, BigInteger> = mapOf(),
                       monitor: TaskMonitor
    ): Function? {
        presetRegisterValues.forEach { k, v ->
            SetRegisterCmd(k, start, start.add(1), v).applyTo(program)
        }

        aam.disassemble(start)
        aam.initializeOptions()
        aam.createFunction(start, true)

        aam.startAnalysis(monitor)
        aam.waitForAnalysis(null, monitor)
        aam.cancelQueuedTasks()

        val functionGot = program.listing.getFunctionContaining(start)
        if (functionGot != null) {
            return functionGot
        } else {
            AsmCodeClearer(program).clearCodeStartsWith(start)
            return null
        }
    }

    /**
     * 清除单条指令。
     * 清除指定地址处的反汇编代码。
     *
     * @param address 要清除的指令地址。
     */
    fun clearOne(address: Address) {
        AsmCodeClearer(program).clearCodeStartsWith(address, address.add(1))
    }
}