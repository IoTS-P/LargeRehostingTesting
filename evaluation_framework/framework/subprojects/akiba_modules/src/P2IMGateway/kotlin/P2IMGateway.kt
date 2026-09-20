package org.iotsplab.akiba.process

import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.DataProducer
import org.iotsplab.akiba.utils.DoNotCreateTable
import org.iotsplab.akiba.utils.IgnoreRuntimeTimeout
import org.iotsplab.akiba.utils.WithConfigClass
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

data class P2IMGatewayConfig(
    val p2imRoot: String = "/data/hongyuan/p2im",
    val objdumpPath: String = "/usr/bin/arm-none-eabi-objdump",
    val projectRoot: String = "p2im_fuzzing_proj"
)

@DataProducer<P2IMGatewayConfig>("p2im_basic_conf")
@WithConfigClass(P2IMGatewayConfig::class)
@DoNotCreateTable
@IgnoreRuntimeTimeout
class P2IMGateway(
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule(
    id = id,
    configPath = configPath,
    defaultConfig = P2IMGatewayConfig(),
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    val conf: P2IMGatewayConfig
        get() = config as P2IMGatewayConfig

    override suspend fun startProcess() {
        setTaskData("p2im_basic_conf", conf)


        val elfPathStr = getTaskData("convert_elf_results.elf_path") as? String
        if (elfPathStr == null) {
            logger.error("ELF path not found! Make sure ConvertFirmToELF ran successfully and dbImports is configured.")
            failureSign = FAILED
            return
        }
        val originalPath = Path.of(mainConf.binariesRoot, elfPathStr)

        val rootDir = Path.of(mainConf.binariesRoot, conf.projectRoot, id.toString())
        if (!rootDir.exists()) {
            rootDir.createDirectories()
        }
        setTaskData("p2im_working_dir", rootDir.absolutePathString())

        var guessedBoard = "NUCLEO-F103RB"
        var guessedMcu = "STM32F103RB"
        
        val stringTable = ghidra.program.util.DefinedDataIterator.definedStrings(prog)
        for (str in stringTable) {
            val value = str.value.toString().uppercase()

            if (value.contains("STM32F4")) {
                guessedBoard = "STM32F429I-Discovery"
                guessedMcu = "STM32F429ZI"
                break
            } 
            else if (value.contains("SAM3X8E") || value.contains("ARDUINO")) {
                guessedBoard = "Arduino-Due"
                guessedMcu = "SAM3X8E"
                break
            } 
            else if (value.contains("MK64F") || value.contains("K64F") || value.contains("FRDM")) {
                guessedBoard = "FRDM-K64F"
                guessedMcu = "MK64FN1M0VLL12"
                break
            }
        }
        logger.info("Inferred MCU: $guessedMcu, Board: $guessedBoard for binary $id")
        setTaskData("p2im_guessed_mcu", guessedMcu)

        val fwName = "fw_${id}"
        val elfTargetPath = rootDir.resolve("$fwName.elf")
        originalPath.copyTo(elfTargetPath, overwrite = true)

        val seedSource = Path.of(conf.p2imRoot, "fuzzing", "templates", "seeds")
        val seedDest = rootDir.resolve("inputs")
        if (!seedDest.exists()) {
            ProcessBuilder("cp", "-r", seedSource.absolutePathString(), seedDest.absolutePathString())
                .start().waitFor()
        }

        val configPath = rootDir.resolve("fuzz.cfg")
        val configContent = """
            [DEFAULT]
            base        = ${conf.p2imRoot}
            program     = $fwName
            run         = $id
            working_dir = ${rootDir.absolutePathString()}

            [afl]
            bin         = %(base)s/afl/afl-fuzz
            timeout     = 150+
            input       = %(working_dir)s/inputs
            output      = %(working_dir)s/outputs

            [cov]
            count_hang  = True
            bbl_cov_read_sz = 20000000
            timeout     = 1

            [qemu]
            bin         = %(base)s/qemu/src/qemu.git/gnuarmeclipse-softmmu/qemu-system-gnuarmeclipse
            log         = unimp,guest_errors,int

            [program]
            board       = $guessedBoard
            mcu         = $guessedMcu
            img         = %(working_dir)s/$fwName.elf

            [model]
            retry_num   = 3
            peri_addr_range = 512
            objdump     = ${conf.objdumpPath}
            bin         = %(base)s/model_instantiation/me.py
            log_file    = %(working_dir)s/me.log
        """.trimIndent()
        
        configPath.writeText(configContent)
        logger.info("Generated P2IM config at: ${configPath.absolutePathString()}")
    }
}