package org.iotsplab.akiba.process

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.structure.ArmcmIVT
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.isRegularFile
import kotlin.use

@WithTableColumn("trimmed_sha256", "TEXT")
@WithTableColumn("original_sha256", "TEXT")
@WithTableColumn("base_address", "INTEGER")
@WithTableColumn("entry_valid", "TEXT")
@WithConfigClass(FirmlineBaseCheckerConfig::class)
@IgnoreRuntimeTimeout
class FirmlineBaseChecker (
    id: Int,
    program: Program,
    configPath: String,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    program = program,
    configPath = configPath,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!
    val conf: FirmlineBaseCheckerConfig
        get() = config as FirmlineBaseCheckerConfig
    var baseAddress: Long = -1

    override suspend fun startProcess() {
        conf.firmlineDbPath ?: run {
            logger.error("Firmline database path not specified")
            return
        }

        val firmlineDb = Path.of(conf.firmlineDbPath!!)
        if (!firmlineDb.isRegularFile()) {
            logger.error("Firmline database file not found")
            return
        }

        val dbUrl = "jdbc:sqlite:${firmlineDb.absolutePathString()}"
        logger.info("Working out sha256...")
        val sha256original = sha256sum(File(getMetadata().originalPath))
        val sha256trimmed = getMetadata().processedPath ?.let { sha256sum(File(it)) } ?: sha256original
        updateData(mapOf(
            "trimmed_sha256" to sha256trimmed,
            "original_sha256" to sha256original
        ))

        val findResultTrimmed = findBaseAddress(dbUrl, sha256trimmed)
        when (findResultTrimmed) {
            ENTRY_NOT_FOUND -> {
                logger.warn("Data entry for trimmed binary not found")
                updateErr("Entry not found")
            }
            BASE_ADDRESS_NULL -> {
                logger.warn("Base address for trimmed binary is null in database")
                updateErr("Base null")
            }
            BASE_ADDRESS_NOT_NULL -> {
                logger.info("Data entry for trimmed binary found")
            }
        }

        val findResultOriginal = findBaseAddress(dbUrl, sha256original)
        when (findResultOriginal) {
            ENTRY_NOT_FOUND -> {
                logger.warn("Data entry for original binary not found")
                updateErr("Entry not found")
            }
            BASE_ADDRESS_NULL -> {
                logger.warn("Base address for original binary is null in database")
                updateErr("Base null")
            }
            BASE_ADDRESS_NOT_NULL -> {
                logger.info("Data entry for original binary found")
            }
        }

        if (findResultTrimmed != BASE_ADDRESS_NOT_NULL && findResultOriginal != BASE_ADDRESS_NOT_NULL) {
            if (findResultTrimmed == BASE_ADDRESS_NULL || findResultOriginal == BASE_ADDRESS_NULL)
                updateErr("Base null")
            else
                updateErr("Entry not found")
            failureSign = FAILED
            return
        }

        logger.info("Ready to test base address...")
        val testSuccess: Boolean =
            if (sha256trimmed != sha256original && findResultOriginal == BASE_ADDRESS_NOT_NULL)
                testBaseAddress(baseAddress + prog.memory.blocks.first().start.offset)
            else
                testBaseAddress(baseAddress)

        updateData(mapOf(
            "base_address" to baseAddress,
            "entry_valid" to if (testSuccess) "valid" else "invalid"
        ))
    }

    private fun findBaseAddress(url: String, sha256: String): Int {
        var result = BASE_ADDRESS_NOT_NULL
        DriverManager.getConnection(url).use { conn ->
            conn.prepareStatement(getFirmlineBaseAddressSQL).let { stmt ->
                stmt.setString(1, sha256)
                stmt.executeQuery().let { rs ->
                    if (rs.next()) {
                        baseAddress = rs.getLong("base")
                        if (rs.wasNull()) {
                            result = BASE_ADDRESS_NULL
                            return@use
                        }
                    } else {
                        result = ENTRY_NOT_FOUND
                        return@use
                    }
                }
            }
        }
        return result
    }

    private suspend fun testBaseAddress(base: Long): Boolean {
        val api = FlatProgramAPI(prog)
        val ivt: ArmcmIVT
        val entry: Address = if (Regex("ARM:(LE|BE):32:Cortex").matches(prog.languageID.toString())) {
            ivt = ArmcmIVT.fromAddress(prog, prog.memory.minAddress) ?: run {
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
                masterStackPointer = api.toAddr(ivt.masterStackPointer!!),
                logger,
                monitor = taskGlobalMonitor
            )
        } catch(e: Exception) {
            logger.error("Failed to initialize emulator: ${e.message}")
            return false
        }
        emulator.go()
        logger.info("Emulation valid: ${emulator.startupInfoValid} for ${getMetadata().originalPath}")
        return emulator.startupInfoValid
    }

    companion object {
        private const val ENTRY_NOT_FOUND = 0
        private const val BASE_ADDRESS_NULL = 1
        private const val BASE_ADDRESS_NOT_NULL = 2

        val getFirmlineBaseAddressSQL: String = """
            SELECT base FROM ghidra WHERE sum = ?
        """.trimIndent()

        fun sha256sum(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(8192)
                var len: Int
                while (ins.read(buf).also { len = it } != -1) {
                    digest.update(buf, 0, len)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}