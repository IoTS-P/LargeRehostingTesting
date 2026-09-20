package org.iotsplab.akiba.process

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.data.CharDataType
import ghidra.program.model.data.PointerDataType
import ghidra.program.model.listing.Program
import ghidra.program.model.symbol.RefType
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.WithTableColumn
import org.iotsplab.akiba.utils.FailOnCancelled
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer.allModules
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.highFunction.allScalarsInC
import org.iotsplab.akiba.utils.io.PythonExecutor
import org.iotsplab.akiba.utils.memory.MemoryUtil
import org.iotsplab.akiba.utils.memory.MemoryUtil.Companion.readSmall
import org.iotsplab.akiba.utils.string.allStrings
import java.util.jar.JarFile
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.jvm.Throws
import kotlin.math.floor

@WithTableColumn("base_address", "INTEGER")
@WithConfigClass(StringBaseFinderConfig::class)
@FailOnCancelled
class StringBaseFinder(
    id: Int,
    configPath: String? = null,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null
): AkibaModule (
    id = id,
    configPath = configPath,
    defaultConfig = StringBaseFinderConfig(),
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName
) {
    val prog: Program
        get() = program!!
    val conf = (config as? StringBaseFinderConfig) ?: StringBaseFinderConfig()
    private val stringAddresses = prog.allStrings().map { it.address.offset }.toMutableList()
    private var base: Long = -1

    override suspend fun startProcess() {
        // First: Try to build subMap for scalar groups (grouped by DBSCAN cluster)
        logger.info("Got ${stringAddresses.size} strings")
        if (stringAddresses.size < MIN_STRINGS_REQUIRED) {
            logger.warn("Got too few strings, the result can be inauthentic.")
            updateErr("Strings got not enough")
            return
        }
        val scalarGroups = try {
            clusterScalars()
        } catch (_: IllegalStateException) {
            updateErr("Scalars got not enough")
            return
        }

        val (bestBase, matchCount) = scalarGroups.mapNotNull { group ->
            logger.info("Group size: ${group.size} to match at most 500 strings")
            val scalarSubMap = getTopSubCandidates(stringAddresses, group)
            logger.info("Result for group [${group.first()} .. ${group.last()}] (${group.size} elements)")
            scalarSubMap.forEach {
                logger.info("${it.key.toString(16)}: ${it.value} matched")
            }
            if (scalarSubMap.isEmpty()) null
            else scalarSubMap.maxBy { it.value }
        }.maxByOrNull { it.value }
            ?: run {
                logger.warn("Failed to find base address by strings")
                updateErr("Failed to find base address by strings")
                failureSign = FAILED
                return
            }

        base = bestBase
        if (matchCount.toDouble() / stringAddresses.size >= conf.subtractionMapThreshold) {
            logger.info("Predicted base address by strings: ${base.toString(16)}")
            updateData(mapOf("base_address" to base))

            applyBase()
        } else {
            logger.warn("No match reaches threshold (${conf.subtractionMapThreshold})")
            updateErr("No match reaches threshold (${conf.subtractionMapThreshold})",)
        }

//        val (addresses, failedFunctions) = prog.allUnmappedDataAddressInC(conf.decompileThreadNumber)
//        failedFunctions.forEach {
//            logger.warn("Failed to decompile function $it")
//        }
//        logger.info("Got ${addresses.size} data address references from decompiled C code")
//
//        val subMap = buildSubtractionMap(prog.allStrings().map { it.address },addresses)
//
//        // Remove those diff that repeats less than threshold
//        val filteredSubMap = subMap.filter { it.value >= conf.subtractionMapThreshold }
//
//        filteredSubMap.forEach {
//            logger.info("${it.key.toString(16)} : ${it.value}")
//        }
    }

    @Throws(IllegalStateException::class)
    suspend fun clusterScalars(): List<List<Long>> {
        // Get all scalars
        var scalars = prog.allScalarsInC(conf.decompileThreadNumber).first.toMutableList()
        scalars.addAll(getRodataValues())
        scalars = scalars.distinct().toMutableList()
        logger.info("Got ${scalars.size} scalars")
        scalars.sort()
        if (scalars.size < MIN_STRINGS_REQUIRED)
            throw IllegalStateException("Not enough scalars found")

        val json = Json.encodeToString(scalars)
        logDir.resolve("all_scalars.json").writeText(json)

        // Use python script to cluster scalars
        val moduleJarPath = allModules["org.iotsplab.akiba.process.StringBaseFinder"]!!
        val code = JarFile(moduleJarPath.toFile()).use { jar ->
            jar.getEntry("ClusterScalar.py")?.let { entry ->
                jar.getInputStream(entry).use { stream ->
                    stream.bufferedReader().readText()
                }
            } ?: throw Exception("Failed to read ClusterScalar.py")
        }

        logger.info("Spawning python to run HDBSCAN...")
        PythonExecutor.executeCode(
            code = code, args = listOf(
                logDir.resolve("all_scalars.json").absolutePathString(),
                logDir.resolve("scalar_cluster_result.json").absolutePathString(),
                "-e",
                conf.scalarClusterEpsilon.toString(),
            ))

        val result = logDir.resolve("scalar_cluster_result.json").readText()
        val clusters: MutableMap<Int, List<Long>> = Json.decodeFromString<Map<Int, List<Long>>>(result)
            .filter { (key, _) -> key != -1 }.toMutableMap()
        clusters.forEach { (k, v) ->
            if (v.any { it < 0 })
                clusters[k] = v.filter { it >= 0 }
        }
        return clusters.values.sortedByDescending { it.size }.take(CLUSTER_SELECTION_COUNT)
    }

    fun getTopSubCandidates(strings: List<Long>, refs: List<Long>): Map<Long, Int> {
        // The whole hashmap could be very large, to save memory, we will randomly select subsets and calculate,
        // Calculate multiple times and find the candidate, then work out in a whole to confirm the repeat count.
        val subMap = HashMap<Long, Int>()

        // If the list has more than 500 elements, we will randomly select 500 elements and calculate multiple times
        val samplingTime: Int =
            if (strings.size > 500 || refs.size > 500)
                1.coerceAtLeast(
                    floor(((strings.size.toDouble() / 500 + refs.size.toDouble() / 500) / 2)).toInt())
            else 1

        val totalMap: MutableMap<Long, Int> = mutableMapOf()
        // For each round, we will randomly select a pair of 500 elements to build subtract map,
        // and get the top 10 most repeated elements, and add them to the total map.
        // At last, we will get the top 10 elements from the total map, and recalculate their repeat count.
        (0..<samplingTime).forEach { _ ->
            val sampledStrings = strings.shuffled().take(500)
            val sampledRefs = refs.shuffled().take(500)
            val subMap = buildSubMap(sampledStrings, sampledRefs).filter { it.key >= 0 }
            subMap.entries.sortedByDescending { it.value }.take(MAP_TOP_COUNT).forEach { entry ->
                totalMap[entry.key] ?. let { _ -> totalMap[entry.key] = totalMap[entry.key]!! + entry.value }
                    ?: run { totalMap[entry.key] = entry.value }
            }
        }
        val totalCandidates: List<Long> = totalMap.entries.sortedByDescending { it.value }
            .take(MAP_TOP_COUNT).map { it.key }

        // Recalculate real repeat count
        val stringSet = strings.toSet()
        return totalCandidates.associateWith { candidate ->
            getRepeatCount(stringSet, refs, candidate) }
    }

    fun getRodataValues(): List<Long> {
        val rm = prog.referenceManager
        return prog.listing.getData(true).filter { data ->
            // Has at least 1 reference and are all reads
            rm.getReferencesTo(data.address).any() &&
            rm.getReferencesTo(data.address).all { ref ->
                listOf(RefType.READ, RefType.READ_IND).contains(ref.referenceType) }
        }.mapNotNull {
            when (it.dataType.name) {
                "undefined1" -> readSmall(prog, it.address, 1)
                "undefined2" -> readSmall(prog, it.address, 2)
                "undefined4" -> readSmall(prog, it.address, 4)
                "undefined8" -> readSmall(prog, it.address, 8)
                else -> null
            }
        }
    }

    fun buildSubMap(subee: List<Long>, suber: List<Long>): HashMap<Long, Int> {
        val subMap = HashMap<Long, Int>()
        subee.forEach { ee ->
            suber.forEach { er ->
                val diff = er - ee
                // Directly calculate the repeat count of the diff
                subMap[diff] ?.let { subMap[diff] = it + 1 } ?: run { subMap[diff] = 1 }
            }
        }
        return subMap
    }

    fun applyBase() {
        MemoryUtil.moveAllBlocksInOffset(prog.memory, base)
        val api = FlatProgramAPI(prog)

        // Redefine data type of string references
        var targetTypeName = "undefined"
        val addrLength = when (prog.languageID.idAsString.split(":")[2]) {
            "32" -> 4
            "64" -> 8
            else -> return
        }
        targetTypeName += addrLength

        prog.listing.getData(true).forEach {
            if (it.dataType.name != targetTypeName) return@forEach
            val value = readSmall(prog, it.address, addrLength)
            val addr = api.toAddr(value)
            if (!prog.memory.contains(addr)) return@forEach

            val data = prog.listing.getDataAt(addr) ?: return@forEach
            if (data.dataType.name != "string") return@forEach

            prog.listing.clearCodeUnits(it.address, it.address.add(it.length.toLong() - 1), true)
            prog.listing.createData(it.address, PointerDataType(CharDataType(), 4))
        }
    }

    fun getRepeatCount(subee: Set<Long>, suber: List<Long>, diff: Long): Int {
        return subee.count { suber.contains(it + diff) }
    }

    companion object {
        const val MIN_STRINGS_REQUIRED: Int = 30
        const val DEFAULT_SUBTRACTION_MAP_THRESHOLD: Double = 0.2
        const val DEFAULT_DECOMPILE_THREAD_NUMBER: Int = 8
        const val CLUSTER_SELECTION_COUNT: Int = 3
        const val MAP_TOP_COUNT: Int = 10
    }
}