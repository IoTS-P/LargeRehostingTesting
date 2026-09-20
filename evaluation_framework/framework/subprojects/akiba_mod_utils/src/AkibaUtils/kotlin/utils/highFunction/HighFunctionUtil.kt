package org.iotsplab.akiba.utils.highFunction

import ghidra.app.decompiler.DecompInterface
import ghidra.program.model.listing.Program

/**
 * 高级函数工具类。
 * 提供用于获取和配置反编译器的实用方法。
 */
object HighFunctionUtil {
    /**
     * 获取默认配置的反编译器。
     * 启用语法树、参数度量和 C 代码输出功能。
     *
     * @param program 要反编译的 Ghidra 程序。
     * @return 配置好的反编译器对象。
     * @throws IllegalStateException 如果无法打开程序进行反编译。
     */
    fun getDefaultDecompiler(program: Program): DecompInterface {
        val decompInterface = DecompInterface()
        decompInterface.toggleSyntaxTree(true)
        decompInterface.toggleParamMeasures(true)
        decompInterface.toggleCCode(true)
        if (!decompInterface.openProgram(program))
            throw IllegalStateException("ERROR: Failed to open program: ${decompInterface.lastMessage}")
        return decompInterface
    }
}