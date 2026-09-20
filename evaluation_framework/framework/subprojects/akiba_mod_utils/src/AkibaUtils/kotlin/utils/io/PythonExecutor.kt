package org.iotsplab.akiba.utils.io

import org.iotsplab.akiba.Main
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Python 执行器工具类。
 * 提供在虚拟环境中创建和执行 Python 脚本的功能。
 */
object PythonExecutor{
    /**
     * 创建默认的 Python 虚拟环境。
     * 使用 `python3 -m venv` 命令在全局配置的路径上创建虚拟环境。
     */
    fun createDefaultVenv() {
        val process = ProcessBuilder(
            "python3", "-m", "venv", Main.globalVenv
        ).redirectErrorStream(true).start()
        process.waitFor()
    }

    // Todo: 添加异步输出捕获
    /**
     * 执行 Python 脚本文件。
     * 使用虚拟环境中的 Python 解释器运行指定的脚本文件。
     *
     * @param pythonPath Python 解释器路径，默认为虚拟环境中的 python3。
     * @param scriptPath 要执行的 Python 脚本路径。
     * @param args 传递给脚本的命令行参数列表。
     * @param timeout 超时时间（秒），0 表示不限制。
     * @return 脚本的标准输出内容。
     */
    fun executeFile(
        pythonPath: Path = Path.of("${Main.globalVenv}/bin/python3"),
        scriptPath: Path,
        args: List<String> = emptyList(),
        timeout: Long = 0
    ): String {
        val process = ProcessBuilder(
            "timeout", timeout.toString(), pythonPath.toString(), scriptPath.toString(), *args.toTypedArray())
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        return process.inputStream.bufferedReader().readText()
    }

    // Todo: 添加异步输出捕获
    /**
     * 执行 Python 代码字符串。
     * 使用虚拟环境中的 Python 解释器运行指定的代码字符串。
     *
     * @param pythonPath Python 解释器路径，默认为虚拟环境中的 python3。
     * @param code 要执行的 Python 代码字符串。
     * @param args 传递给脚本的命令行参数列表。
     * @param timeout 超时时间（秒），0 表示不限制。
     * @return 脚本的标准输出内容。
     */
    fun executeCode(
        pythonPath: Path = Path.of("${Main.globalVenv}/bin/python3"),
        code: String,
        args: List<String> = emptyList(),
        timeout: Long = 0
    ): String {
        val process = ProcessBuilder(
            "timeout", timeout.toString(), pythonPath.toString(), "-c", code, *args.toTypedArray())
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        return process.inputStream.bufferedReader().readText()
    }

    // Todo: 添加异步输出捕获
    /**
     * 执行 Python 可执行文件。
     * 运行虚拟环境中的 Python 可执行工具（如 pip、pytest 等）。
     *
     * @param path 可执行文件名称（相对于虚拟环境的 bin 目录）。
     * @param args 传递给可执行文件的命令行参数列表。
     * @param timeout 超时时间（秒），0 表示不限制。
     * @return 可执行文件的标准输出内容。
     */
    fun executePyExe(
        path: String,
        args: List<String> = emptyList(),
        timeout: Long = 0
    ): String {
        val process = ProcessBuilder(
            "timeout", timeout.toString(), "${Main.globalVenv}/bin/$path", *args.toTypedArray()
        ).redirectErrorStream(true).start()
        process.waitFor()
        return process.inputStream.bufferedReader().readText()
    }
}