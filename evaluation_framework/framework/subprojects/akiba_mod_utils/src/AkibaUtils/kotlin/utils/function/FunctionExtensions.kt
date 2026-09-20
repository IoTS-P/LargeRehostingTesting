package org.iotsplab.akiba.utils.function

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.address.AddressOutOfBoundsException
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Instruction
import ghidra.program.model.listing.Program
import ghidra.util.task.TaskMonitor
import org.iotsplab.akiba.utils.assembly.DisasmHelper
import org.iotsplab.akiba.utils.assembly.isGenericJump

/**
 * 获取函数中的所有指令。
 * 从函数入口点开始遍历，直到函数体的最大地址。
 *
 * @return 包含函数中所有指令的列表。
 */
fun Function.allInstructions(): ArrayList<Instruction> {
    val instructions = ArrayList<Instruction>()
    var ptr = entryPoint
    val currentProgram = program

    while (ptr < body.maxAddress) {
        val nextInst = currentProgram.listing.getInstructionAt(ptr) ?: return instructions
        instructions.add(nextInst)
        ptr = ptr.add(nextInst.length.toLong())
    }

    return instructions
}

/**
 * 获取程序中的所有函数起始地址。
 *
 * @return 包含所有函数入口点地址的可变列表。
 */
fun Program.allFunctionStarts(): MutableList<Address> {
    val functionStarts = mutableListOf<Address>()
    functionManager.getFunctions(true).forEach {
        it ?.let { functionStarts.add(it.entryPoint) }
    }
    return functionStarts
}

/**
 * 获取函数中所有指令的字符串表示。
 * 每条指令包含地址和反汇编文本。
 *
 * @return 包含所有指令字符串的拼接结果。
 */
fun Function.allInstructionsString(): String {
    val program = program
    val instIter = program.listing.getInstructions(body.minAddress, true)
    var ret = ""
    while (instIter.hasNext()) {
        val instruction = instIter.next()
        if (!body.contains(instruction.address)) break
        ret += "${instruction.address}: $instruction${System.lineSeparator()}"
    }
    return ret
}

/**
 * 获取函数调用的所有目标地址。
 * 包括直接调用的函数和 thunk 函数的目标。
 *
 * @param offset 地址偏移量，用于调整 thunk 函数的目标地址计算。
 * @param monitor 任务监视器。
 * @return 包含所有调用目标地址的列表。
 */
fun Function.allCallingTargets(offset: Long = 0, monitor: TaskMonitor): List<Address> {
    val descendants = getCalledFunctions(monitor).map { it.entryPoint }.toMutableSet()
    try {
        if (isThunk) {
            val thunkTarget = getThunkedFunction(false)
            val address = FlatProgramAPI(program)
                .toAddr(thunkTarget.name.split("_").last().toInt(16))
                .subtract(offset)
            if (program.listing.getFunctionAt(address) != null
                || DisasmHelper(program).disasmFunction(address, monitor = monitor) != null)
                descendants.add(address)
        }
    } catch (_: AddressOutOfBoundsException) {}
    return descendants.toList()
}

/**
 * 获取函数中的所有基本块起始地址。
 * 包括函数入口点、所有跳转指令的地址以及被引用到的指令地址。
 *
 * @return 包含所有基本块起始地址的列表。
 */
fun Function.allBasicBlockStarts(): List<Address> {
    val set = mutableSetOf<Address>(entryPoint)
    val rm = program.referenceManager

    // Add all jumps
    allInstructions().forEach {
        if (isGenericJump.test(it))
            set.add(it.address)
    }

    // Some jumps may target to non-jump instructions, they are also block starts
    set.addAll(allInstructions().filter { rm.hasReferencesTo(it.address) }.map { it.address })

    return set.toList()
}