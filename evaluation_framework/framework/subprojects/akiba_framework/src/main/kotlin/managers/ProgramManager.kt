package org.iotsplab.akiba.managers

import ghidra.app.plugin.core.analysis.AutoAnalysisManager
import ghidra.app.util.opinion.*
import ghidra.base.project.GhidraProject
import ghidra.framework.options.Options
import ghidra.program.model.lang.LanguageID
import ghidra.program.model.listing.Program
import ghidra.program.util.GhidraProgramUtilities
import ghidra.util.exception.DuplicateFileException
import ghidra.util.task.ConsoleTaskMonitor
import ghidra.util.task.TaskMonitor
import ghidra.util.task.TimeoutTaskMonitor
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import org.iotsplab.akiba.Main
import org.iotsplab.akiba.client.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager.config
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.managers.ConfigManager.projectConf
import org.iotsplab.akiba.managers.ConfigManager.sqlSource
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.managers.WorkspaceManager.languageProvider
import org.iotsplab.akiba.managers.WorkspaceManager.logRootDir
import org.iotsplab.akiba.managers.WorkspaceManager.projectName
import org.iotsplab.akiba.module.*
import org.iotsplab.akiba.module.AkibaModule.Companion.pascalToSnake
import org.iotsplab.akiba.utils.DoNotCreateTable
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.ProcedureArguments
import org.iotsplab.akiba.utils.WithView
import sun.misc.Signal
import java.io.BufferedInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext
import kotlin.io.path.*

object ProgramManager {
    lateinit var metadata: List<BinaryMetadata>
    private lateinit var taskSemaphore: Semaphore

    private var successTestCount: Int = 0
    val successCount: Int
        get() = successTestCount
    private var failureTestCount: Int = 0
    val failureCount: Int
        get() = failureTestCount

    private var programInitLock: ReentrantLock = ReentrantLock()
    private var programNames: MutableSet<String> = mutableSetOf()

    private lateinit var skipList: MutableList<Int>

    val successDir: Path
        get() = logRootDir.resolve("success")
    val failedDir: Path
        get() = logRootDir.resolve("failed")
    val runtimeErrorDir: Path
        get() = logRootDir.resolve("runtime_error")
    val notFoundDir: Path
        get() = logRootDir.resolve("not_found")

    val programCoroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    // TODO: Use ClassGraph to find all loaders
    private val loaderMap: Map<String, Class<out Loader>> = mapOf(
        "Raw Binary" to BinaryLoader::class.java,
        "Executable and Linking Format (ELF)" to ElfLoader::class.java,
        "Intel Hex" to IntelHexLoader::class.java,
        "Relocatable Object Module Format (OMF)" to OmfLoader::class.java,
        "Object Module Format (OMF-51)" to Omf51Loader::class.java,
        "Portable Executable (PE)" to PeLoader::class.java,
    )

    /**
     * Initialize the program manager, including:
     * 1. Initialize log directories
     * 2. Read metadata of binaries selected
     * 3. Check if all required property is present
     * 4. If it's a restore task, create skip list to skip finished binaries
     */
    fun init(): Boolean {
        // delete incomplete cases and failed cases
        logRootDir.toFile().listFiles { file -> file.isDirectory }
            ?.forEach {
                if (it.name != "failed" && it.name != "success" && it.name != "runtime_error")
                    it.deleteRecursively()
            }

        // create directories for success and failed cases
        try {
            Files.createDirectories(successDir)
            Files.createDirectories(failedDir)
            Files.createDirectories(runtimeErrorDir)
            if (projectConf.mode == "base")
                Files.createDirectories(notFoundDir)
        } catch (e: IOException) {
            globalLogger.error("Failed to create directories for success and failed cases")
            return false
        }

        // If the binary root is a directory, the sqlite path need to be specified
        if (!Files.exists(Path.of(mainConf.binariesRoot))) {
            globalLogger.error("Binary (root) path not exists")
            return false
        }

        // read metadata
        metadata = readMetadata()

        if (metadata.isEmpty()) {
            globalLogger.error("Empty query results, quit immediately")
            return false
        }

        setSkipList()

        return true
    }

    private fun setSkipList() {
        if (Main.restoreFailedOnly) {
            val whiteList = failedDir.toFile().listFiles()?.map {
                it.name.toInt()
            } ?.toMutableList() ?: mutableListOf()
            skipList = metadata.map { it.id }.filter { !whiteList.contains(it) }.toMutableList()
        } else if (Main.restoreErrorOnly) {
            val whiteList = runtimeErrorDir.toFile().listFiles()?.map {
                it.name.toInt()
            }?.toMutableList() ?: mutableListOf()
            skipList = metadata.map { it.id }.filter { !whiteList.contains(it) }.toMutableList()
        } else {
            skipList = successDir.toFile().listFiles()?.map {
                it.name.toInt()
            }?.toMutableList() ?: let {
                globalLogger.warn("No success directory found, ignored restore config")
                mutableListOf()
            }
            skipList.addAll(failedDir.toFile().listFiles()?.map {
                it.name.toInt()
            } ?: mutableListOf())
            skipList.addAll(runtimeErrorDir.toFile().listFiles()?.map {
                it.name.toInt()
            } ?: mutableListOf())
        }
    }

    /**
     * Read metadata from database, all the properties are read from the latter columns of the query result
     * @return A list of BinaryMetadata
     */
    private fun readMetadata(): List<BinaryMetadata> {
        lateinit var data: List<BinaryMetadata>

        val propertyFile = logRootDir.resolve("properties.json")
        if (propertyFile.exists()) {
            return try {
                Json.decodeFromString<List<BinaryMetadata>>(propertyFile.toFile().readText())
            } catch (_: Exception) {
                throw RuntimeException("Failed to deserialize properties.json")
            }
        }

        // for single file
        if (Files.isRegularFile(Path.of(mainConf.binariesRoot))) {
            data = listOf(BinaryMetadata(
                            -1,
                            Path.of(mainConf.binariesRoot).absolutePathString(),
                            mainConf.processor,
                            null, null,
                            checksum = getFileMD5Checksum(Path.of(mainConf.binariesRoot))
                        ))
        } else {
            // not a single file, ready to read the database and check the validity of binary paths
            DatabaseClient.enableRoute("/get/id/sql")
            val ids = DatabaseClient.getIdInSQL(sqlSource.constraint)
            DatabaseClient.disableRoute("/get/id/sql")

            val metadata = ids.map { DatabaseClient.getMetadata(it) }
            data = metadata
        }

        globalLogger.info(String.format("Loaded %s binary paths", data.size))

        // write those paths into log file
        if (!propertyFile.exists()) {
            propertyFile.createFile()
            propertyFile.writeText(Json.encodeToString(data))
        }

        return data
    }

    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun startProcess(project: GhidraProject) = runBlocking {
        createTablesAndViews()

        taskSemaphore = Semaphore(mainConf.threads)
        val limitedDispatcher = Dispatchers.Default.limitedParallelism(mainConf.threads)

        // Send heartbeat pack every 30 seconds
        val heartbeatJob = launch {
            while (isActive) {
                delay(30000)
                DatabaseClient.sendHeartbeat()
            }
        }

        val jobs = metadata.map { p ->
            programCoroutineScope.launch(limitedDispatcher) {
                withContext(coroutineContext + ModuleContext(p) + GlobalContext) {
                    taskSemaphore.acquire()
                    try {
                        workOnBinary(p, project)
                    } catch (e: Exception) {
                        globalLogger.error("Error while processing file #${p.id}: ${e.message}")
                        e.printStackTrace()
                    } finally {
                        taskSemaphore.release()
                    }
                }
            }
        }

        jobs.forEach { it.join() }

        heartbeatJob.cancelAndJoin()

        DatabaseClient.lockedTables.toList().forEach {
            try {
                DatabaseClient.tableUnlock(it)
            } catch (e: Exception) {
                globalLogger.error("Failed to unlock table $it: ${e.message}")
                throw IllegalStateException("Failed to unlock table $it")
            }
        }
    }

    @Throws(DatabaseClient.DatabaseDaemonException::class)
    private fun createTablesAndViews() {
        config.tasks.forEach { task ->
            @Suppress("UNCHECKED_CAST")
            val taskClass = (task.mainClass as Class<AkibaModule>)

            // Try to create table, if it already exists, ignore
            if (taskClass.annotations.none { it is DoNotCreateTable }) {
                val tableName = task.tableName
                    ?: "${pascalToSnake(task.mainClassName.split(".").last())}_results"
                val columns = taskClass.kotlin.annotations
                    .filter { it is WithTableColumn }
                    .map { it as WithTableColumn }
                    .associate { it.name to it.type }

                try {
                    DatabaseClient.createModuleTable(tableName, columns)
                } catch (e: DatabaseClient.DatabaseDaemonException) {
                    if (e.statusCode == HttpStatusCode.Conflict)
                        globalLogger.warn(e.statusMsg)
                    else {
                        globalLogger.error("Error while creating table $tableName: ${e.statusMsg}")
                        throw e
                    }
                }

                // Lock this table
                try {
                    DatabaseClient.tableLock(tableName)
                } catch (e: DatabaseClient.DatabaseDaemonException) {
                    if (e.statusCode == HttpStatusCode.Conflict)
                        globalLogger.warn(e.statusMsg)
                    else {
                        globalLogger.error("Failed to lock table $tableName: ${e.statusMsg}")
                        throw e
                    }
                }
            }

            // Try to create views, if it already exists or causes errors, ignore (don't throw exceptions)
            taskClass.annotations
                .filter { it is WithView }
                .map { it as WithView }
                .associate { it.viewName to it.creationSql }
                .forEach { viewName, viewSQL ->
                    try {
                        DatabaseClient.createView(viewName, viewSQL)
                    } catch (e: DatabaseClient.DatabaseDaemonException) {
                        if (e.statusCode == HttpStatusCode.Conflict)
                            globalLogger.warn(e.statusMsg)
                        else {
                            globalLogger.error("Error while creating view ${viewName}: ${e.statusMsg}")
                        }
                    }
                }
        }
    }

    /**
     * Process binaries at the specified path
     *
     * This function is responsible for importing a binary into the project, analyzing it, and updating the database
     * with the analysis results
     * It skips files larger than 10MB to avoid processing overly large files
     *
     * @param metadata Binary file metadata
     * @param project Ghidra project in which to import and analyze the binary
     */
    @Throws(IllegalStateException::class, Exception::class)
    private suspend fun workOnBinary(metadata: BinaryMetadata, project: GhidraProject) {
        // memoryLock()
        // val path = Path.of(metadata.processedPath ?: metadata.originalPath)
        val path = Path.of(config.general!!.binariesRoot, "processed/${metadata.id}.bin").let {
            if (it.exists()) it
            else Path.of(config.general!!.binariesRoot, "original/${metadata.id}.bin")
        } .let {
            if (it.exists()) it
            else throw IllegalStateException("File ${it.fileName} does not exist")
        }

        val logDir = Path.of("logs/$projectName/${metadata.id}")

        // Skip ids
        if (skipList.contains(metadata.id)) {
            // Success automatically
            globalLogger.info("Case ${metadata.id} found, skipped")
            successTestCount++
            return
        }

        if (logDir.notExists())
            logDir.createDirectories()

        // Import the program into the project
        var program: Program? = null
        var programIsCreated = false
        if (!projectConf.noCreateProgram) {
            val programName = "${metadata.id}-${path.fileName}"

            // In restore mode, previous program may have some errors that is not suitable for use, need to delete them
            if (Main.restore != null || config.withGhidraProject!!.deletePreviousProgram) {
                project.projectData.rootFolder.files.firstOrNull { it.name.startsWith("${metadata.id}-") } ?.let {
                    try {
                        globalLogger.info("Found program ${it.name} in restore mode, deleting...")
                        it.delete()
                    } catch (e: Exception) {
                        globalLogger.error("Failed to delete program ${it.name}: ${e.message}")
                    }
                }
            }

            // If we can find the program, use existing
            project.projectData.rootFolder.files.firstOrNull { it.name.startsWith("${metadata.id}-") } ?.let {
                globalLogger.info("Found program $programName in ghidra project")
                program = project.openProgram("/", it.name, false)
            } ?: run {
                globalLogger.info("Program $programName not found")
                if (listOf("fork").contains(projectConf.mode)) {
                    globalLogger.error("Unable to find program in the project, skipped.")
                    try {
                        logDir.moveTo(notFoundDir.resolve(logDir.fileName))
                    } catch (_: Exception) {}
                    return
                } else {
                    programInitLock.withLock {
                        program = (
                                if (metadata.arch == "n/a" || metadata.arch == null)
                                    tryCreateProgramWithoutLang(project, path)
                                else
                                    createProgramWithLang(
                                        metadata.id, project, path, LanguageID(metadata.arch),
                                        metadata.format ?: "Raw Binary"
                                    )
                                ) ?: run {
                            logDir.moveTo(runtimeErrorDir.resolve(logDir.fileName))
                            throw IllegalStateException("Failed to create program")
                        }
                    }
                    programIsCreated = true

                    // If load properties is set, need to adjust the offset first
                    // We just need to do this once
                    applyLoadProperties(program!!, metadata.loadProperties)
                }
            }

            // Check if Ghidra can auto-detect the format of the file, if the file is a known format, we won't need to
            // calculate the base address and entry point
            globalLogger.info("File format: ${program!!.executableFormat}")
            if (SKIP_ANALYSIS_FORMATS.contains(program.executableFormat)) {
                globalLogger.info("Ghidra detected a unique file format that don't need to be analyzed, skipped")
                return
            }
        }

        // Import data from the database with given ID
        importDbData(metadata.id)

        var failed = false

        try {
            for (procedure: ProcedureArguments in config.tasks) {
                globalLogger.info("Running ${procedure.mainClass!!.name} on #${metadata.id}")
                val arguments = hashMapOf(
                    "configPath" to procedure.configKey,
                    "id" to metadata.id,
                    "program" to if (!projectConf.noCreateProgram) program else null,
                    "consoleLogLevel" to procedure.consoleLogLevel,
                    "fileLogLevel" to procedure.fileLogLevel,
                    "tableName" to procedure.tableName
                )
                if (ProcedureManager.invokeProcedure(
                        path, procedure, arguments, coroutineContext[ModuleContext.Key]!!)) {
                    failed = true
                    break
                }
                globalLogger.info("Finished ${procedure.mainClass!!.name} on #${metadata.id}")
            }

            // Save the program
            if (!projectConf.noCreateProgram && projectConf.saveProject) {
                globalLogger.info("Saving program ${program!!.name}")
                if (programIsCreated)
                    project.saveAs(program, "/", program.name, true)
                else {
                    project.save(program)
                    project.close(program)
                }
            }

            if (!failed) {
                successTestCount++
                logDir.moveTo(successDir.resolve(logDir.fileName))
            }
        } catch (e: NoSuchMethodError) {
            globalLogger.error("Fatal error, there exists a method not found. It may be caused by incorrect module" +
                    "dependencies or incorrect module definitions. Check your module code.")
            e.printStackTrace()
            Signal.raise(Signal("INT"))
        } catch (e: DuplicateFileException) {
            globalLogger.error("duplicated file found: ${e.message}")
        } catch (e: Exception) {     // If we reach here, it means that something bad happens.
            globalLogger.error(
                "Exception occurred while running #${metadata.id}: ${e.message} (${e.javaClass.simpleName})")
            e.printStackTrace()
        }
    }

    private fun applyLoadProperties(program: Program, data: List<ImportManager.FileSegment>?) {
        if (data == null || data.isEmpty())
            return

        assert(program.memory.blocks.size == 1)
        var block = program.memory.blocks[0]
        val addressSpace = program.addressFactory.defaultAddressSpace
        data.forEachIndexed { idx, it ->
            // When we reached the last one, we don't need to split anymore
            if (idx == data.indices.last)
                return@forEachIndexed
            program.memory.split(block, addressSpace.getAddress(it.newOffset + it.length))
            block = program.memory.blocks.last()
        }
        data.reversed().forEach {
            val b = program.memory.getBlock(addressSpace.getAddress(it.newOffset))
            globalLogger.info(
                "Moving ${b.start.offset.toString(16)}(${b.size}) to ${it.oldOffset.toString(16)}")
            program.memory.moveBlock(b, addressSpace.getAddress(it.oldOffset), TaskMonitor.DUMMY)
        }
    }

    @Throws(IllegalArgumentException::class, DatabaseClient.DatabaseDaemonException::class)
    private suspend fun importDbData(id: Int) {
        config.dbImports?.forEach { entry ->
            val (table, column) = entry.split(".").let {
                if (it.size != 2)
                    throw IllegalArgumentException("Invalid db import key: $it")
                it[0] to if (it[1] == "*") null else listOf(it[1])
            }

            val data = DatabaseClient.getModuleData(id.toLong(), table, column)

            for (k in data.keys) {
                coroutineContext[ModuleContext.Key]!!.data["$table.$k"] = data[k]
            }
        }
    }

    /**
     * Create a program with language info
     * @param id: File ID in database
     * @param project: The project to create program in
     * @param path: The path to the binary file
     * @param arch: The architecture of MCU
     * @param format: File format (unused for now)
     * @return A nullable program, null if failed to create a program
     * @throws Exception In some cases, a malformed file may cause IOException from `project.importProgram`
     */
    @Throws(Exception::class)
    private fun createProgramWithLang(
        id: Int,
        project: GhidraProject,
        path: Path,
        arch: LanguageID,
        format: String
    ): Program? {
        val lang = languageProvider.getLanguage(arch) ?: run {
            globalLogger.error("Failed to get language ${arch.idAsString}")
            failureTestCount++
            return null
        }

        if (!loaderMap.keys.contains(format)) {
            globalLogger.error("Unsupported format: $format")
            return null
        }

        // If we specify loader, the program cannot be initialized, and `initializeProgram` is a private method
        val program: Program = project.importProgram(
            path.toFile(), lang, null)
            ?: run {
                globalLogger.error("Failed to create program for $path")
                failureTestCount++
                return null
            }

        program.name = "${id}-${path.fileName}"
        programNames.add(program.name)
        return program
    }

    fun tryCreateProgramWithAutoDetect(project: GhidraProject, path: Path): Program? {
        return try {
            project.importProgram(path.toFile()) ?. let {
                // If not raw binary, it seems Ghidra has identified the format
                if (it.executableFormat != "Raw Binary") it else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Create a program without language info (trying different language and select the one that has most functions)
     * @param project: The project to create program in, if null, will create a temporary project and will only return
     *                 the language recognized
     * @param path: The path to the binary file
     * @return A nullable program, null if failed to create a program
     */
    fun tryCreateProgramWithoutLang(project: GhidraProject?, path: Path,
                                    loadProperties: List<ImportManager.FileSegment> = listOf()): Program? {
        val tempProject = if (project == null) {
            GhidraProject.createProject(
                Path.of(projectConf.projectRoot).toAbsolutePath().toString(),
                "temp-${UUID.randomUUID()}", false
            )
        } else null
        val candidates: MutableSet<Pair<String, Int>> = sortedSetOf(comparator = compareByDescending { it.second })
        for(lang: String in GUESSED_PRIMARY_LEVEL_ARCHES) {
            globalLogger.debug("Trying $lang......")
            val language = languageProvider.getLanguage(LanguageID(lang))
            var tempProgram: Program

            try {
                tempProgram = project?.importProgram(path.toFile(), language, null)
                    ?: tempProject?.importProgram(path.toFile(), language, null)
                    ?: continue
            } catch (e: Exception) {
                // TODO: In some very rare cases, Ghidra may throw an exception here because
                //       Ghidra got a wrong binary format, how to handle it?
                globalLogger.error("Failed to create program for $path with $lang: ${e.message}")
                return null
//                globalLogger.error("Failed to create program for $path with $lang: ${e.message}, trying raw binary...")
//                tempProgram = project?.importProgram(path.toFile(), language, null)
//                    ?: tempProject?.importProgram(path.toFile(), language, null)
//                    ?: continue
            }

            if (tempProgram.executableFormat == "Raw Binary")
                applyLoadProperties(tempProgram, loadProperties)

            autoAnalyzeInTimeout(tempProgram, mainConf.autoAnalysisTimeout)    // Will wait here
            val functionsGot = tempProgram.functionManager.functionCount
            if (functionsGot < FUNCTION_COUNT_THRESHOLD)
                globalLogger.warn("$lang disassembled fewer than 10 functions, skipped")
            else {
                candidates.add(lang to functionsGot)
                globalLogger.info("$lang disassembled $functionsGot functions")
            }
            tempProject?.close(tempProgram) ?: project?.close(tempProgram)
        }
        val selected = candidates.firstOrNull() ?: run {
            globalLogger.error("Failed to find an architecture that can disassemble more than $FUNCTION_COUNT_THRESHOLD" +
                    " functions, it may be executable files with very some rare arch or not be an executable file")
            tempProject?.close()
            return null
        }
        globalLogger.info("The ${selected.first} got the most functions (${selected.second}), set as architecture")
        tempProject?.close()
        val lang = languageProvider.getLanguage(LanguageID(selected.first))
        val ret = project?.importProgram(path.toFile(), lang, null)
        tempProject?.close()
        return ret
    }

    @OptIn(ExperimentalStdlibApi::class)
    @JvmStatic
    fun getFileMD5Checksum(path: Path): String {
        val digest = MessageDigest.getInstance("MD5")
        val inputStream = BufferedInputStream(path.inputStream())
        val buffer = ByteArray(8192)
        var bytesRead: Int

        inputStream.use { input ->
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest().toHexString()
    }

    /**
     * Auto analyze the program in timeout (seconds)
     */
    @Throws(InterruptedException::class)
    fun autoAnalyzeInTimeout(program: Program, timeout: Int,
                             options: Options = initializeAnalyzeOptions(program),
                             timeoutHandler: (() -> Unit)? = null) {
        // Auto analysis
        // The managers has a map to store, to avoid ConcurrentModificationException, we cannot let
        // `getAnalysisManager` and `reAnalyzeAll` to run in parallel threads at the same time
        val aam: AutoAnalysisManager = AutoAnalysisManager.getAnalysisManager(program)

        aam.initializeOptions(options)

        aam.reAnalyzeAll(null)
        val monitor = TimeoutTaskMonitor.timeoutIn(
            if (timeout > 0) timeout.toLong() else Int.MAX_VALUE.toLong(), TimeUnit.SECONDS)
        aam.startAnalysis(monitor)
        aam.cancelQueuedTasks()
        GhidraProgramUtilities.markProgramAnalyzed(program)     // mark this program as auto-analyzed

        if (monitor.didTimeout()) {
            if (timeoutHandler != null)
                timeoutHandler()
            return
        }
    }

    private fun initializeAnalyzeOptions(program: Program): Options {
        val options = program.getOptions("Analyzers")
        extraAnalyzerOptions.let {
            it.forEach { (optionName, optionValue) ->
                check(options.contains(optionName)) { "Option $optionName not found in analyzer options" }
                check(options.getType(optionName).isCompatible(optionValue)) {
                    "Option $optionName value type $optionValue unmatched"
                }
                options.putObject(optionName, optionValue)
            }
        }

        return options
    }

    val GUESSED_PRIMARY_LEVEL_ARCHES: List<String> = listOf(
        "AARCH64:LE:64:v8A", "AARCH64:BE:64:v8A",
        "ARM:LE:32:v8T", "ARM:BE:32:v8T",
        "MIPS:LE:32:R6", "MIPS:BE:32:R6",
        "MIPS:LE:64:R6", "MIPS:BE:64:R6",
        "PowerPC:LE:32:default", "PowerPC:BE:32:default",
        "PowerPC:LE:64:default", "PowerPC:BE:64:default",
        "RISCV:LE:32:RV32GC", "RISCV:LE:64:RV64GC",
        "x86:LE:32:default", "x86:LE:64:default",
        "Xtensa:LE:32:default", "Xtensa:BE:32:default"
    )

    val SKIP_ANALYSIS_FORMATS: List<String> = listOf(
        // "Executable and Linking Format (ELF)"
    )

    const val FUNCTION_COUNT_THRESHOLD: Int = 30

    // <Option name, Option value>
    private val extraAnalyzerOptions: Map<String, Any> = mapOf(
        "Aggressive Instruction Finder" to true
    )
}