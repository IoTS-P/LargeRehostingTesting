import java.util.jar.JarInputStream

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "org.iotsplab.akiba.process"   // Change this to your own group

repositories {
    mavenCentral()
}

val PublicConfiguration by configurations.register("Public")

data class ModuleMetadata(
    val moduleName: String,         // All codes should be located in `src/$moduleName/`
    val mainClassPath: String,      // Full class path of main class
    val authors: List<String>,
    val version: String,
    val briefDescription: String
) {
    val doc: Map<String, String>   // Key: Documentation language, Value: Documentation
    val configuration by configurations.register(moduleName)

    init {
        val d = mutableMapOf<String, String>()
        for ((lang, docDir) in ModuleMetadata.docDir) {
            val docFile = projectDir.resolve("$docDir/$moduleName.md")
            if (docFile.exists())
                d[lang] = docFile.readText()
        }
        doc = d
    }

    companion object {
        val docDir = mapOf(
            "en" to "usages",
            "zh" to "usages_zh"
        )
    }
}

/***********************************************************************************************************************
 * MODULE DEFINITION START, DO NOT CHANGE CODES OUTSIDE THIS BLOCK
 ***********************************************************************************************************************/

// Append your module here
val localModules = listOf(         // Register module metadata here: authors, version, brief description
    ModuleMetadata(
        moduleName = "AddressSpaceAnalyzer",
        mainClassPath = "org.iotsplab.akiba.process.AddressSpaceAnalyzer",
        authors = listOf("Hornos3"),
        version = "1.2",
        briefDescription = "Firmware address space resolver"
    ),
    ModuleMetadata(
        moduleName = "ArchChecker",
        mainClassPath = "org.iotsplab.akiba.process.ArchChecker",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Check if the arch of a binary file is right"
    ),
    ModuleMetadata(
        moduleName = "ARMBaseFinder",
        mainClassPath = "org.iotsplab.akiba.process.ARMBaseFinder",
        authors = listOf("Hornos3"),
        version = "1.3",
        briefDescription = "Find the base address of firmware files"
    ),
    ModuleMetadata(
        moduleName = "ConvertFirmToELF",
        mainClassPath = "org.iotsplab.akiba.process.ConvertFirmToELF",
        authors = listOf("Hornos3"),
        version = "1.2",
        briefDescription = "Convert firmware bin files to ELF files"
    ),
    ModuleMetadata(           // DEPRECATED
        moduleName = "CortexEmulator",
        mainClassPath = "org.iotsplab.akiba.process.CortexEmulator",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Ghidra emulator for ARM Cortex-M binary files"
    ),
    ModuleMetadata(
        moduleName = "EnhancedFunctionFinder",
        mainClassPath = "org.iotsplab.akiba.process.EnhancedFunctionFinder",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Function finder using Ghidra's machine-learning methods"
    ),
    ModuleMetadata(
        moduleName = "Entropy",
        mainClassPath = "org.iotsplab.akiba.process.Entropy",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Get the entropy of a file"
    ),
    ModuleMetadata(
        moduleName = "EntryFinder",
        mainClassPath = "org.iotsplab.akiba.process.EntryFinder",
        authors = listOf("Hornos3"),
        version = "1.1",
        briefDescription = "Try to find the entry point of a firmware file"
    ),
    ModuleMetadata(
        moduleName = "ExternalDynamicChecker",
        mainClassPath = "org.iotsplab.akiba.process.ExternalDynamicChecker",
        authors = listOf("Hornos3"),
        version = "1.1",
        briefDescription = "Check the validity of entry points from external sources"
    ),
    ModuleMetadata(
        moduleName = "Firmline",
        mainClassPath = "org.iotsplab.akiba.process.Firmline",
        authors = listOf("Hornos3"),
        version = "1.1",
        briefDescription = "Firmline's runner, a static analysis tool of firmware files"
    ),
    ModuleMetadata(
        moduleName = "FirmlineBaseChecker",
        mainClassPath = "org.iotsplab.akiba.process.FirmlineBaseChecker",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Check if base addresses got from Firmline is right"
    ),
    ModuleMetadata(
        moduleName = "FirmlineOnFuzzware",
        mainClassPath = "org.iotsplab.akiba.process.FirmlineOnFuzzware",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Run fuzzing tools Fuzzware using Firmline's data"
    ),
    ModuleMetadata(
        moduleName = "FirmlineOnFuzzwareReplay",
        mainClassPath = "org.iotsplab.akiba.process.FirmlineOnFuzzwareReplay",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Run fuzzing tools Fuzzware to replay crash inputs from fuzz instances using Firmline's data"
    ),
    ModuleMetadata(
        moduleName = "FirmRCA",
        mainClassPath = "org.iotsplab.akiba.process.FirmRCA",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "FirmRCA's runner, a post-fuzzing analysis tool to locate core reasons of crashes"
    ),
    ModuleMetadata(
        moduleName = "FirmXRay",
        mainClassPath = "org.iotsplab.akiba.process.FirmXRay",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "FirmXRay's runner, a static analysis tool to get base addresses of firmware files"
    ),
    ModuleMetadata(
        moduleName = "FirmXRayOnFuzzware",
        mainClassPath = "org.iotsplab.akiba.process.FirmXRayOnFuzzware",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Run fuzzing tools Fuzzware using FirmXRay's data"
    ),
    ModuleMetadata(
        moduleName = "FuzzwareAdmissionTest",
        mainClassPath = "org.iotsplab.akiba.process.FuzzwareAdmissionTest",
        authors = listOf(),
        version = "1.0",
        briefDescription = ""
    ),
    ModuleMetadata(
        moduleName = "HoedurAdmissionTest",
        mainClassPath = "org.iotsplab.akiba.process.HoedurAdmissionTest",
        authors = listOf(),
        version = "1.0",
        briefDescription = ""
    ),
    ModuleMetadata(
        moduleName = "MultiFuzzAdmissionTest",
        mainClassPath = "org.iotsplab.akiba.process.MultiFuzzAdmissionTest",
        authors = listOf(),
        version = "1.0",
        briefDescription = ""
    ),
    ModuleMetadata(
        moduleName = "AidFuzzerAdmissionTest",
        mainClassPath = "org.iotsplab.akiba.process.AidFuzzerAdmissionTest",
        authors = listOf(),
        version = "1.0",
        briefDescription = ""
    ),
    ModuleMetadata(
        moduleName = "P2IMGateway",
        mainClassPath = "org.iotsplab.akiba.process.P2IMGateway",
        authors = listOf(),
        version = "1.0",
        briefDescription = ""
    ),
    ModuleMetadata(
        moduleName = "P2IMRunner",
        mainClassPath = "org.iotsplab.akiba.process.P2IMRunner",
        authors = listOf(),
        version = "1.0",
        briefDescription = ""
    ),
    ModuleMetadata(
        moduleName = "FirmXRayOnFuzzwareReplay",
        mainClassPath = "org.iotsplab.akiba.process.FirmXRayOnFuzzwareReplay",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Run fuzzing tools Fuzzware to replay crash inputs from fuzz instances using FirmXRay's data"
    ),
    ModuleMetadata(
        moduleName = "FunctionFinder",
        mainClassPath = "org.iotsplab.akiba.process.FunctionFinder",
        authors = listOf("Hornos3"),
        version = "1.2",
        briefDescription = "Find and define functions that may be missed by Ghidra auto-analysis processes"
    ),
    ModuleMetadata(
        moduleName = "FuzzwareEmu",
        mainClassPath = "org.iotsplab.akiba.process.FuzzwareEmu",
        authors = listOf("Hornos3"),
        version = "1.1",
        briefDescription = "Run emulation from Fuzzware to emulate a firmware file"
    ),
    ModuleMetadata(
        moduleName = "FuzzwareGateway",
        mainClassPath = "org.iotsplab.akiba.process.FuzzwareGateway",
        authors = listOf("Hornos3"),
        version = "1.1",
        briefDescription = "Generate Fuzzware configs and do some other required works before fuzzing"
    ),
    ModuleMetadata(
        moduleName = "FuzzwarePipeline",
        mainClassPath = "org.iotsplab.akiba.process.FuzzwarePipeline",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Run Fuzzware fuzzing, a firmware fuzzing tool"
    ),
    ModuleMetadata(
        moduleName = "FuzzwareReplay",
        mainClassPath = "org.iotsplab.akiba.process.FuzzwareReplay",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Run Fuzzware replay to replay crash inputs from fuzz instances"
    ),
    ModuleMetadata(
        moduleName = "FuzzwareStat",
        mainClassPath = "org.iotsplab.akiba.process.FuzzwareStat",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Get status information of fuzzing instances of Fuzzware"
    ),
    ModuleMetadata(
        moduleName = "HasRTOS",
        mainClassPath = "org.iotsplab.akiba.process.HasRTOS",
        authors = listOf("Hornos3"),
        version = "1.1",
        briefDescription = "Confirm whether an ARM Cortex-M firmware file has an RTOS or not"
    ),
    ModuleMetadata(
        moduleName = "HoedurFuzz",
        mainClassPath = "org.iotsplab.akiba.process.HoedurFuzz",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Run Hoedur fuzzing, a firmware fuzzing tool"
    ),
    ModuleMetadata(
        moduleName = "HoedurStatistics",
        mainClassPath = "org.iotsplab.akiba.process.HoedurStatistics",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Get status information of fuzzing instances of Hoedur"
    ),
    ModuleMetadata(
        moduleName = "IoTGeneralStructures",
        mainClassPath = "org.iotsplab.akiba.process.IoTGeneralStructures",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Contains definitions of classes that are often used in firmware analysis"
    ),
    ModuleMetadata(
        moduleName = "MultiFuzz",
        mainClassPath = "org.iotsplab.akiba.process.MultiFuzz",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Run Multifuzz fuzzing, a firmware fuzzing tool"
    ),
    ModuleMetadata(
        moduleName = "ProgramInitialization",
        mainClassPath = "org.iotsplab.akiba.process.ProgramInitialization",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Initialize a Ghidra program through Ghidra's auto-analysis"
    ),
    ModuleMetadata(
        moduleName = "ProgramServer",
        mainClassPath = "org.iotsplab.akiba.process.ProgramServer",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Help find functions through addresses more quickly"
    ),
    ModuleMetadata(
        moduleName = "RBaseFind",
        mainClassPath = "org.iotsplab.akiba.process.RBaseFind",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "rbasefind runner, a rust tool to find base addresses of firmware files"
    ),
    ModuleMetadata(
        moduleName = "StartupDynamicChecker",
        mainClassPath = "org.iotsplab.akiba.process.StartupDynamicChecker",
        authors = listOf("Hornos3"),
        version = "1.1",
        briefDescription = "Check the validity of the base address and the entry point of a firmware file"
    ),
    ModuleMetadata(
        moduleName = "StringAdder",
        mainClassPath = "org.iotsplab.akiba.process.StringAdder",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Find and define strings that may be missed by Ghidra auto-analysis"
    ),
    ModuleMetadata(
        moduleName = "StringBaseFinder",
        mainClassPath = "org.iotsplab.akiba.process.StringBaseFinder",
        authors = listOf("Hornos3"),
        version = "1.1",
        briefDescription = "Find the base address of a firmware file through string addresses matching"
    ),
)

val lc: Map<String, Configuration> = localModules.associate {
    it.moduleName to it.configuration
}

// Modules that are deprecated and will not be built
val deprecatedModules: List<String> = listOf(
    "CortexEmulator"
)

// Modules that are under development and will not be built
val underDevelopmentModules = listOf(
    "EnhancedFunctionFinder"
)

// Add your dependencies here
dependencies {
    // Module-specified dependencies
    (lc["ConvertFirmToELF"]!!)("net.fornwall:jelf:0.9.0")
    (lc["FirmlineBaseChecker"]!!)("org.xerial:sqlite-jdbc:3.51.1.0")
    (lc["FuzzwareReplay"]!!)("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    (lc["HoedurStatistics"]!!)("com.github.luben:zstd-jni:1.4.9-2")
    (lc["HoedurStatistics"]!!)("org.yaml:snakeyaml:2.4")
    (lc["HoedurStatistics"]!!)("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")

    // You can also add other modules as dependencies
    (lc["AddressSpaceAnalyzer"]!!)(moduleDependency(listOf("ARMBaseFinder", "IoTGeneralStructures")))
    (lc["ArchChecker"]!!)(moduleDependency(listOf("ProgramInitialization")))
    (lc["ARMBaseFinder"]!!)(moduleDependency(listOf("IoTGeneralStructures", "ProgramServer", "StartupDynamicChecker")))
    (lc["ConvertFirmToELF"]!!)(moduleDependency(listOf("IoTGeneralStructures")))
    (lc["CortexEmulator"]!!)(moduleDependency(listOf("ARMBaseFinder")))
    (lc["ExternalDynamicChecker"]!!)(moduleDependency(listOf("ARMBaseFinder", "StartupDynamicChecker")))
    (lc["FirmlineBaseChecker"]!!)(moduleDependency(listOf("ARMBaseFinder", "StartupDynamicChecker")))
    (lc["FirmlineOnFuzzware"]!!)(moduleDependency(listOf("FuzzwareGateway", "FuzzwarePipeline")))
    (lc["FirmlineOnFuzzwareReplay"]!!)(moduleDependency(listOf("FuzzwareGateway")))
    (lc["FirmRCA"]!!)(moduleDependency(listOf("FuzzwareGateway", "FuzzwareReplay")))
    (lc["FirmXRay"]!!)(moduleDependency(listOf("ARMBaseFinder", "StartupDynamicChecker")))
    (lc["FirmXRayOnFuzzware"]!!)(moduleDependency(listOf("FuzzwareGateway", "FuzzwarePipeline")))
    (lc["FuzzwareAdmissionTest"]!!)(moduleDependency(listOf("FuzzwareGateway")))
    (lc["HoedurAdmissionTest"]!!)(moduleDependency(listOf("FuzzwareGateway")))
    (lc["MultiFuzzAdmissionTest"]!!)(moduleDependency(listOf("FuzzwareGateway")))
    (lc["AidFuzzerAdmissionTest"]!!)(moduleDependency(listOf("FuzzwareGateway")))
    (lc["P2IMRunner"]!!)(moduleDependency(listOf("P2IMGateway")))
    (lc["FirmXRayOnFuzzwareReplay"]!!)(moduleDependency(listOf("FuzzwareGateway")))
    (lc["FunctionFinder"]!!)(moduleDependency(listOf("ARMBaseFinder")))
    (lc["FuzzwareEmu"]!!)(moduleDependency(listOf("FuzzwareGateway")))
    (lc["FuzzwareGateway"]!!)(moduleDependency(listOf("ARMBaseFinder")))
    (lc["FuzzwarePipeline"]!!)(moduleDependency(listOf("FuzzwareGateway")))
    (lc["FuzzwareReplay"]!!)(moduleDependency(listOf("FuzzwareGateway")))
    (lc["HoedurFuzz"]!!)(moduleDependency(listOf("FuzzwareGateway")))
    (lc["MultiFuzz"]!!)(moduleDependency(listOf("FuzzwareGateway", "HoedurFuzz")))
    (lc["StartupDynamicChecker"]!!)(moduleDependency(listOf("IoTGeneralStructures")))

    // public dependencies
    PublicConfiguration("org.apache.logging.log4j:log4j-api:2.24.3")
    PublicConfiguration("org.apache.logging.log4j:log4j-core:2.24.3")
    PublicConfiguration("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    PublicConfiguration(project(":akiba_framework"))
    PublicConfiguration(fileTree(mapOf("dir" to "modules", "include" to listOf("*.jar"))))
    PublicConfiguration("io.ktor:ktor-server:3.1.3")
    PublicConfiguration("io.ktor:ktor-server-netty:3.1.3")
    testImplementation(kotlin("test"))
}

// If there are any finalize tasks in some modules, add them here
val finalizeTasks: Map<String, Jar.() -> Unit> = mapOf(
    // Add additional tasks here, like copying files
    "ConvertFirmToELF" to {
        from(
            project.rootDir.resolve("build/resources/ConvertFirmToELF/ELFBuilder/cmake-build-debug/ELFBuilder")) {
            into("/")
        }
    }
)

/***********************************************************************************************************************
 * MODULE DEFINITION END
 ***********************************************************************************************************************/


fun moduleDependency(modules: List<String>): ConfigurableFileCollection {
    return files(modules.map { name ->
        val metadata = localModules.first { it.moduleName == name }
        "build/libs/amod-$name-${metadata.version}.jar"
    }.toTypedArray())
}

localModules.forEach { module ->
    val globalGroup = group

    module.configuration.extendsFrom(configurations["Public"])

    sourceSets.create(module.moduleName) {
        kotlin.srcDir("src/${module.moduleName}/kotlin")

        compileClasspath += module.configuration
        runtimeClasspath += module.configuration
    }

    // Exclude modules that are under development
    if (underDevelopmentModules.firstOrNull { it == module.moduleName } == null
        && deprecatedModules.firstOrNull { it == module.moduleName } == null) {
        tasks.register<Jar>("moduleJar-${module.moduleName}") {
            group = globalGroup as String
            archiveBaseName.set("amod-${module.moduleName}")
            archiveVersion.set(module.version)

            duplicatesStrategy = DuplicatesStrategy.EXCLUDE

            from(sourceSets[module.moduleName].output) {
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }

            // Pack all jars except Akiba and Ghidra
            from(
                module.configuration.resolve()
                    .filter {
                        it.name.endsWith("jar") &&
                                // exclude all common jar
                                !configurations["Public"].contains(it) &&
                                !it.name.startsWith("amod")
                    }
                    .map { zipTree(it) }
            ) {
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }

            exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA", "**/.*/**")

            // Write dependency class names into 'META-INF/module-deps'
            val dependencyClassNames = module.configuration.resolve()
                .filter { it.name.startsWith("amod") }
                .mapNotNull {
                    file(it).inputStream().use { stream ->
                        JarInputStream(stream).use { jarStream ->
                            jarStream.manifest?.mainAttributes?.getValue("Main-Class")
                        }
                    }
                }
            val depFile = temporaryDir.resolve("META-INF/module-deps")
            depFile.parentFile.mkdirs()
            depFile.writeText(dependencyClassNames.joinToString("\n"))
            from(depFile) { into("META-INF") }

            manifest {
                attributes["Main-Class"] = module.mainClassPath
                attributes["Module-Name"] = module.moduleName
                attributes["Module-Version"] = module.version
                attributes["Module-Author"] = module.authors.joinToString(", ")
                attributes["Module-Description"] = module.briefDescription
            }

            // Create directory `docs` and write documentation of different languages into different files
            val docsDir = temporaryDir.resolve("docs")
            docsDir.mkdirs()
            module.doc.forEach { (lang, text) ->
                docsDir.resolve("$lang.md").writeText(text)
            }

            from(docsDir) { into("docs") }

            finalizeTasks[module.moduleName]?.invoke(this)
        }
    }
}

fun recDepend(allTask: Task, undone: MutableList<Task>, selected: Task) {
    val moduleName = selected.name.substringAfter("moduleJar-")
    val dependencies = configurations[moduleName].resolve()
        .filter { it.name.startsWith("amod") && !it.path.contains("/modules/") }
    if (dependencies.isEmpty()) {
        allTask.dependsOn(selected)
    } else {
        for (dependency in dependencies) {
            val dependencyName = dependency.name.substringAfter("amod-").substringBefore("-")
            val dependencyTask = tasks.getByName("moduleJar-$dependencyName")
            selected.mustRunAfter(dependencyTask)
            if (undone.contains(dependencyTask))
                recDepend(allTask, undone, dependencyTask)
        }
        allTask.dependsOn(selected)
    }
    undone.remove(selected)
}

tasks.register("moduleJar-ALL") {
    val undoneTasks = tasks.filter { it.name.startsWith("moduleJar-") && it.name != "moduleJar-ALL" }
        .toMutableList()
    while (!undoneTasks.isEmpty()) {
        val selected = undoneTasks.first()
        recDepend(this, undoneTasks, selected)
    }
}

tasks.register<Zip>("bundle-zip") {
    archiveBaseName.set("akiba_modules")
    archiveVersion.set(version.toString())
    description = "Bundle all akiba module JARs into one zip file"

    val libDir = layout.buildDirectory.dir("libs")
    destinationDirectory.set(libDir)

    from(libDir) {
        include("amod-*.jar")
        exclude("amod-Test*.jar")
    }
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}