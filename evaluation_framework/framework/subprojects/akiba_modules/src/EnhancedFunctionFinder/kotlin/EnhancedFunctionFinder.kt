package org.iotsplab.akiba.process

import ghidra.machinelearning.functionfinding.FunctionStartRFParams
import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.WithConfigClass

@WithConfigClass(EnhancedFunctionFinderConfig::class)
class EnhancedFunctionFinder(
    configPath: String? = null,
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
) : AkibaModule(
    configPath = configPath,
    defaultConfig = EnhancedFunctionFinderConfig(),
    id = id,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!

    override suspend fun startProcess() {
        val params: FunctionStartRFParams = FunctionStartRFParams(program)
        TODO()
    }
}