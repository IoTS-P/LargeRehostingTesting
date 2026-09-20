package org.iotsplab.akiba.process

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.lang.Register
import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.managers.ProgramManager.autoAnalyzeInTimeout
import org.iotsplab.akiba.managers.WorkspaceManager.project
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.process.structure.ArmcmIVT
import org.iotsplab.akiba.utils.assembly.AsmCodeClearer
import org.iotsplab.akiba.utils.assembly.DisasmHelper
import org.iotsplab.akiba.utils.emulator.StepInEmulator
import java.io.File
import java.math.BigInteger

class CortexEmulator (
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule (
    id = id,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    private lateinit var emulator: StepInEmulator
    private lateinit var ivt: ArmcmIVT
    private lateinit var elfProgram: Program
    private val api: FlatProgramAPI = FlatProgramAPI(prog)

    @Throws(IllegalArgumentException::class)
    override suspend fun startProcess() {
        elfProgram = importELF()

        initEmulator()
        emulator.go()

        project.save(elfProgram)
    }

    suspend fun importELF(): Program {
        logger.info("Importing ELF...")
        val elfProgram = project.importProgram(File(mainConf.binariesRoot)
            .resolve(getTaskData("CONVERTFIRMTOELF_RESULTS.ELF_PATH") as? String
                ?: throw IllegalArgumentException("ELF file not found")))
        autoAnalyzeInTimeout(elfProgram, 180)

        logger.info("Getting IVT...")
        ivt = ArmcmIVT.fromDefined(elfProgram,
            api.toAddr(getTaskData("ARMBASEFINDER_RESULTS.IVT_START") as Long))!!

        val entryFunction = elfProgram.listing.getFunctionAt(ivt.entryPoint!!)
        logger.info("Entry point: ${ivt.entryPoint!!}")

        // Clear ARM codes that is auto generated
        logger.info("Reset handler: ${entryFunction.entryPoint}-${entryFunction.body.maxAddress}")
        AsmCodeClearer(elfProgram).clearCodeStartsWith(entryFunction.entryPoint, entryFunction.body.maxAddress)

        DisasmHelper(elfProgram).disasmFunction(entryFunction.entryPoint, mapOf(
            elfProgram.getRegister("TMode") to 1.toBigInteger()
        ), taskGlobalMonitor)
        project.saveAs(elfProgram, "/", elfProgram.name, true)

        return elfProgram
    }

    fun initEmulator() {
        emulator = object : StepInEmulator(
            program = elfProgram,
            entryPoint = ivt.entryPoint!!,
            stackTop = api.toAddr(ivt.masterStackPointer!!),
            logger = logger,
            options = SUPPORT_SKIP_CALLOTHER
                    or SUPPORT_AUTO_DISASSEMBLE_AFTER_JUMP
                    or SUPPORT_AUTO_DISASSEMBLE_ENTRYPOINT
                    or SUPPORT_DATA_FLOW_ANALYZER,
            maxExecution = 1000000,
            monitor = taskGlobalMonitor
        ) {
            // private val instRepeatMap: HashMap<Address, Pair<Int, Long>> = HashMap()

            override fun beforeStep(addr: Address, presetDisasmRegs: Map<Register, BigInteger>): Int {
                return super.beforeStep(addr, mapOf(
                    elfProgram.getRegister("TMode") to 1.toBigInteger()
                ))
            }
        }
    }
}