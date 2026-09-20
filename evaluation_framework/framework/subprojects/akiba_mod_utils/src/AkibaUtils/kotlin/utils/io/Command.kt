package org.iotsplab.akiba.utils.io

import java.nio.file.Path

/**
 * 命令执行工具类。
 * 提供在 Linux 系统上检查和执行 shell 命令的功能。
 */
object Command {
    /**
     * 检查命令是否存在。
     * 通过 `which` 命令查找指定的可执行文件是否在系统 PATH 中。
     *
     * @param command 要查找的命令名称。
     * @return 如果命令存在则返回 true，否则返回 false。
     */
    fun exists(command: String): Boolean {
        val process = ProcessBuilder("which", command)
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()
        return exitCode == 0
    }

    /**
     * 查找命令的完整路径。
     * 通过 `which` 命令获取指定可执行文件的绝对路径。
     *
     * @param command 要查找的命令名称。
     * @return 命令的完整路径，如果不存在则返回 null。
     */
    fun which(command: String): String? {
        val process = ProcessBuilder("which", command)
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()
        return if (exitCode == 0) {
            process.inputStream.bufferedReader().readLine()
        } else {
            null
        }
    }

    /**
     * 执行 shell 命令。
     * 支持在执行前加载环境变量文件。
     *
     * @param command 要执行的命令。
     * @param envSource 可选的环境变量文件路径列表，会在执行命令前通过 source 加载。
     * @return 包含退出码和标准输出的对。
     */
    fun execute(command: String, envSource: List<Path> = listOf()): Pair<Int, String> {
        val cmd = if (envSource.isEmpty()) command
                  else envSource.joinToString(" && ") { "source $it" } + " && $command"
        val process = ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true).start()
        process.waitFor()
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }
}