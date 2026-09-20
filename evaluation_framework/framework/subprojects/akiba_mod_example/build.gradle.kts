import java.util.jar.JarInputStream

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("org.jetbrains.dokka") version "2.1.0"
}

group = "org.iotsplab.akiba.module"   // Change this to your own group

// If you need to build Dokka documentation, add the following code to your build.gradle.kts file
/*
dokka {
    dokkaPublications.html {
        moduleName.set("Akiba Example Module")
        println(layout.projectDirectory)
        outputDirectory.set(layout.buildDirectory.dir("docs/html"))
    }

    dokkaSourceSets {
        main {
            sourceRoots.from(layout.projectDirectory.file("src/AkibaExample/kotlin"))
            classpath.from(projectDir.resolve("../akiba_framework/lib/ghidra.jar"))
        }
    }
}
*/

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
val localModules = listOf(
    ModuleMetadata(
        moduleName = "AkibaExample",
        mainClassPath = "org.iotsplab.akiba.module.AkibaExample",
        authors = listOf("Hornos3"),
        version = "1.0",
        briefDescription = "Akiba Example Module, find all strings in the binary file"
    )
)

val lc: Map<String, Configuration> = localModules.associate {
    it.moduleName to it.configuration
}

// Modules that are deprecated and will not be built
val deprecatedModules: List<String> = listOf()

// Modules that are under development and will not be built
val underDevelopmentModules: List<String> = listOf()

// Add your dependencies here
dependencies {
    PublicConfiguration(project(":akiba_framework"))
    PublicConfiguration(fileTree(mapOf("dir" to "modules", "include" to listOf("*.jar"))))
}

// If there are any finalize tasks in some modules, add them here
val finalizeTasks: Map<String, Jar.() -> Unit> = mapOf()

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

// This task is used to build all modules in one shot
tasks.register("moduleJar-ALL") {
    val undoneTasks = tasks.filter { it.name.startsWith("moduleJar-") && it.name != "moduleJar-ALL" }
        .toMutableList()
    while (!undoneTasks.isEmpty()) {
        val selected = undoneTasks.first()
        recDepend(this, undoneTasks, selected)
    }
}

// Bundle all module JARs into one zip file
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