//package org.iotsplab.akiba.utils.io
//
//import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
//import java.util.*
//import kotlin.system.exitProcess
//
//object IOUtil {
//    private var inputBuf = 0
//    private var Logger = globalLogger
//
//    @JvmStatic
//    fun inputIntInRange(min: Int, max: Int, defaultValue: Int, maxWaitTime: Int): Int {
//        if (maxWaitTime == 0) return inputIntRange(min, max)
//
//        val inputThread = Thread {
//            inputIntRange(min, max)
//        }
//
//        inputThread.start()
//
//        try {
//            inputThread.join((maxWaitTime * 1000).toLong())
//        } catch (e: InterruptedException) {
//            Logger.error("Error while waiting for input:")
//            Logger.error(e.message)
//            exitProcess(-1)
//        }
//
//        if (inputThread.isAlive) {
//            Logger.warn(String.format("Input no response, choose default value %d", defaultValue))
//            return defaultValue
//        } else {
//            return inputBuf
//        }
//    }
//
//    private fun inputInt(): Int {
//        val scanner = Scanner(System.`in`)
//        while (true) {
//            try {
//                inputBuf = scanner.nextInt()
//            } catch (e: Exception) {
//                Logger.error("Failed to input int value: ")
//                Logger.error(e.message)
//                continue
//            }
//            return inputBuf
//        }
//    }
//
//    private fun inputIntRange(min: Int, max: Int): Int {
//        while (true) {
//            try {
//                inputBuf = inputInt()
//                if (inputBuf < min || inputBuf >= max) {
//                    Logger.warn("Invalid int input, please input again")
//                    continue
//                }
//            } catch (e: Exception) {
//                Logger.error("Failed to input int value: ")
//                Logger.error(e.message)
//                continue
//            }
//            return inputBuf
//        }
//    }
//}
