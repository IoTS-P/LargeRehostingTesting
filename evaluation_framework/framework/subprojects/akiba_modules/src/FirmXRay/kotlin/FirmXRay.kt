package org.iotsplab.akiba.process

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.structure.ArmcmIVT
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory

@WithTableColumn("base_address", "BIGINT")
@WithTableColumn("entry_valid", "TEXT")
@WithConfigClass(FirmXRayConfig::class)
@IgnoreRuntimeTimeout
class FirmXRay (
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    configPath = configPath,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    val conf: FirmXRayConfig
        get() = config as FirmXRayConfig
    val firmxrayRoot: Path = Path.of(conf.firmxrayRoot!!)
    val firmxrayOutputDir: Path = firmxrayRoot.resolve("output")

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    override suspend fun startProcess() {
        if (!firmxrayRoot.isDirectory())
            throw IllegalArgumentException("Invalid FirmXRay root directory")

            try {
                val base = runFirmXRay()

                // Added for enhanced FirmXRay, If you want to run original FirmXRay, please comment out the below lines
                // Enhanced FirmXRay: https://github.com/MCUSec/RealworldFirmware/tree/main/FirmXRay
                if (base == -1L) {
                    logger.error("FirmXRay failed to get base address")
                    updateErr("failed")
                    updateData(mapOf("base_address" to null, "entry_valid" to null))
                    failureSign = FAILED
                    return
                }
                // Added ended

                val entryValid = testBaseAddress(base)
                updateData(
                    mapOf(
                        "base_address" to base,
                        "entry_valid" to if (entryValid) "valid" else "invalid"
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to run FirmXRay: ${e.message}")
                updateErr("failed")
                updateData(
                    mapOf("base_address" to null, "entry_valid" to null)
                )
                failureSign = FAILED
            }
    }

    @Throws(IllegalArgumentException::class)
    private suspend fun runFirmXRay(): Long = coroutineScope {
        firmxrayLock.lock()

        try {
            val firmxrayCmd = "cd ${firmxrayRoot.absolutePathString()} && " +
                    "${conf.javaBinPath} -cp out:lib/ghidra.jar:lib/json.jar main.Main " +
                    "${originalFile.absolutePath} Nordic"
            logger.debug("Execute: $firmxrayCmd")
            val builder = ProcessBuilder(*conf.cmdPrefix.toTypedArray(), firmxrayCmd)
                .redirectErrorStream(true)
            val process: Process = builder.start()
            var base: Long? = null

            val outReader = launch {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line ?: continue
                        logger.trace(line)

                        if (line.contains("Base: 0x")) {
                            base = line.substringAfter("Base: 0x").toLong(16)
                        } else if (line.startsWith("Result already exist for ")) {
                            val path = line.substringAfter("Result already exist for ")
                            base = firmxrayOutputDir.resolve(path).toFile().readLines().filter {
                                it.contains("Base: 0x")
                            }.map {
                                it.substringAfter("Base: 0x").toLong(16)
                            }.first()
                        }
                    }
                }
            }

            outReader.start()
            outReader.join()

            base ?: throw IllegalStateException("FirmXRay failed to find base address")
            return@coroutineScope base
        } catch (e: Exception) {
            throw e
        } finally {
            firmxrayLock.unlock()
        }
    }

    private fun testBaseAddress(base: Long): Boolean {
        val api = FlatProgramAPI(prog)
        val entry: Address = if (Regex("ARM:(LE|BE):32:.*").matches(prog.languageID.toString())) {
            val ivt = ArmcmIVT.fromAddress(prog, prog.memory.minAddress) ?: run {
                logger.error("Header is not a valid IVT")
                return false
            }
            ivt.entries[4] ?. let { api.toAddr(it) } ?: run {
                logger.error("Entry point invalid")
                return false
            }
        } else {
            logger.error("Cannot get entry point")
            return false
        }

        val emulator = try {
            StartupDynamicChecker.Companion.CheckerEmulator(
                program = prog,
                baseAddress = api.toAddr(base),
                entryPoint = entry,
                masterStackPointer = api.toAddr(STACK_POINTER),
                logger = logger,
                monitor = taskGlobalMonitor
            )
        } catch(e: Exception) {
            logger.error("Failed to initialize emulator: ${e.message}")
            return false
        }
        emulator.go()
        logger.info("Emulation valid: ${emulator.startupInfoValid} for ${usingFile.name}")
        return emulator.startupInfoValid
    }

    companion object {
        const val STACK_POINTER = 0xFFFF_8000

        // FirmXRay does not support parallel execution, or the FirmXRay program may cause unexpected failures
        val firmxrayLock: ReentrantLock = ReentrantLock()
    }
}