package org.iotsplab.akiba.process

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.structure.ArmcmIVT
import org.iotsplab.akiba.utils.DataConsumer
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.FailOnCancelled
import org.iotsplab.akiba.utils.WithConfigClass

/**
 * ExternalDynamicChecker is a process that checks the validity of external-given base address and entry point of a
 * firmware. Need to configure in ExternalDynamicChecker.json. It uses the query sql url given in the main config, and
 * the queryCommand in ExternalDynamicChecker should give the query result with 2/3 columns:
 * - File path (Required)
 * - Base address (Required)
 * - Entry point (Optional, if not offered, will treat the firmware header and try to analyze)
 */
@DataConsumer<String>("base_address")
@DataConsumer<String>("entry_point")
@WithTableColumn("entry_valid", "TEXT")
@WithConfigClass(ExternalDynamicCheckerConfig::class)
@FailOnCancelled
class ExternalDynamicChecker(
    configPath: String,
    id: Int,
    program: Program,
    properties: Map<String, String?>,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
): AkibaModule (
    configPath = configPath,
    defaultConfig = ExternalDynamicCheckerConfig(),
    id = id,
    program = program,
    properties = properties,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    val api = FlatProgramAPI(prog)
    val conf: ExternalDynamicCheckerConfig
        get() = super.config as ExternalDynamicCheckerConfig

    override suspend fun startProcess() {
        val baseAddress = getBaseAddress()
        val entryPoint = getEntryPoint()
        logger.debug("Base: ${baseAddress.offset.toString(16)}, Entry: ${entryPoint.offset.toString(16)}")

        val emulator = try {
            StartupDynamicChecker.Companion.CheckerEmulator(
                program = prog,
                baseAddress,
                entryPoint,
                masterStackPointer = api.toAddr(STACK_POINTER),
                logger,
                monitor = taskGlobalMonitor
            )
        } catch(e: Exception) {
            logger.error("Failed to initialize emulator: ${e.message}")
            failureSign = FAILED
            return
        }
        emulator.go()
        logger.info("Emulation valid: ${emulator.startupInfoValid} for ${usingFile.name}")
        updateData(mapOf("entry_valid" to if (emulator.startupInfoValid) "valid" else "invalid"))
    }

    @Throws(NumberFormatException::class, IllegalStateException::class)
    suspend fun getBaseAddress(): Address {
        val data: String = (getTaskData("base_address") as? String) ?: run {
            throw IllegalStateException("Base address is null")
        }
        return api.toAddr(data.toLong())
    }

    @Throws(NumberFormatException::class, IllegalStateException::class)
    suspend fun getEntryPoint(): Address {
        return (getTaskData("entry_point") as String?) ?. let {
            api.toAddr(it.toLong())
        } ?: run {
            if (Regex("ARM:(LE|BE):32:Cortex").matches(prog.languageID.toString())) {
                val ivt = ArmcmIVT.fromAddress(prog, prog.memory.minAddress) ?: run {
                    throw IllegalStateException("Header is not a valid IVT")
                }
                ivt.entries[4] ?. let { api.toAddr(it) } ?: run {
                    throw IllegalStateException("Entry point invalid")
                }
            } else
                throw IllegalStateException("Cannot get entry point")
        }
    }

    companion object {
        // As long as the stack pointer does not conflict with any codes or data, it's ok to work
        const val STACK_POINTER = 0xFFFF_8000
        const val MAX_EXECUTION = 5000L
    }
}