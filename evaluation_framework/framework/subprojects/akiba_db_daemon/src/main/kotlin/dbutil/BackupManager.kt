package org.iotsplab.akiba.dbDaemon.dbutil

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.dbDaemon.DatabaseDaemon
import org.iotsplab.akiba.dbDaemon.DatabaseDaemon.Companion.config
import org.iotsplab.akiba.dbDaemon.DatabaseDaemon.Companion.globalLogger
import org.iotsplab.akiba.dbDaemon.operations.PGInstances
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.FileSystems
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteExisting

class BackupManager (
    val instanceName: String
) {
    val snapshotPath: Path = Path.of(DatabaseDaemon.Companion.config.backupRoot, instanceName)
    private val allNodes: MutableMap<String, BackupNode> = mutableMapOf()
    private var rootNode: BackupNode? = null
    private var logicalPreviousNode: BackupNode? = null

    private val backupDirectory: Path get() = Path.of(config.backupRoot, instanceName)
    private val backupDataSource: PGSimpleDataSource

    data class BackupNode(
        val label: String,
        val isExpired: Boolean,
        val alias: String?,
        val description: String?,
        val parent: String?,
        val backupType: BackupType,
        val createdAt: OffsetDateTime,
        val children: MutableList<BackupNode> = mutableListOf()
    )

    object BackupNodeSerializer: JsonSerializer<BackupNode>() {
        override fun serialize(
            node: BackupNode,
            generator: JsonGenerator,
            provider: SerializerProvider
        ) {
            generator.writeStartObject()
            generator.writeStringField("label", node.label)
            generator.writeBooleanField("isExpired", node.isExpired)
            generator.writeStringField("alias", node.alias)
            generator.writeStringField("description", node.description)
            generator.writeStringField("parent", node.parent)
            generator.writeStringField("backupType", node.backupType.name)
            generator.writeNumberField("createdAt", node.createdAt.toInstant().toEpochMilli())
            generator.writeArrayFieldStart("children")
            node.children.forEach {
                generator.writeString(it.label)
            }
            generator.writeEndArray()
            generator.writeEndObject()
        }
    }

    enum class BackupType {
        FULL, DIFF, INCR
    }

    data class BackupTree(
        val rootNodes: List<BackupNode>
    )

    init {
        if (!snapshotPath.toFile().exists())
            snapshotPath.toFile().mkdirs()

        // Because the main instance is set only listening to localhost, we can use a fixed password
        backupDataSource = PGSimpleDataSource().apply {
            setUrl("jdbc:postgresql://localhost:5432/akiba_backup")
            user = "akiba"
            password = "akiba"
        }

        updateLogicalData()
        checkConsistency()
    }

    /**
     * Check if the backup tree is valid
     */
    @Throws(IllegalStateException::class)
    private fun checkConsistency() {
        // There are several things we need to confirm

        rootNode ?.let {
            check(logicalPreviousNode != null) { "No previous backup found" }

            // 1. The logical backup tree stored in our database SHOULD BE a tree
            check(checkNoRing(mutableSetOf(), it)) {
                "Bad backup tree structure, contact your administrator to check backup data"
            }

            // 2. All backups found in the database should be existent in the disk
            val physicalBackups: MutableSet<PhysicalBackupData> = obtainPhysicalBackup()
            checkDiskConsistency(physicalBackups)

            // 3. All aliases of backups should be unique
            check(checkAliasUnique()) {
                "Backup alias is not unique, please check your backup data"
            }
        }
    }

    /**
     * Check if there is a ring in the backup tree. Use DFS to check
     */
    private fun checkNoRing(visited: MutableSet<String> = mutableSetOf(), node: BackupNode): Boolean {
        if (visited.contains(node.label))
            return false
        visited.add(node.label)
        for (child in node.children) {
            if (!checkNoRing(visited, child))
                return false
        }
        return true
    }

    @Throws(IllegalStateException::class)
    private fun checkDiskConsistency(physical: MutableSet<PhysicalBackupData>) {
        val nodesOnTree: Set<String> = allNodes.filter {
            (_, node) -> node.backupType != BackupType.FULL && !node.isExpired
        }.keys.plus(rootNode!!.label)

        // a. We cannot allow there exists any backup in the tree that is not in the disk
        val noPhysical: Set<String> = nodesOnTree.minus(physical.map { it.label })
        if (noPhysical.isNotEmpty()) {
            throw IllegalStateException(
                "Backup tree is inconsistent, please check your backup data. " +
                        "The following backups are missing in the disk: ${noPhysical.joinToString(", ")}"
            )
        }

        // b. We cannot allow there exists any backup that has different types in the database and the disk
        // Because we do not know which one is right, we cannot fix the data automatically
        val typeErrors: MutableSet<String> = mutableSetOf()
        physical.forEach {
            if (it.type != allNodes[it.label]!!.backupType)
                typeErrors.add(it.label)
        }
        if (typeErrors.isNotEmpty()) {
            throw IllegalStateException(
                "Backup tree is inconsistent, please check your backup data. " +
                        "The following backups have wrong type: ${typeErrors.joinToString(", ")}"
            )
        }

        // c. We cannot allow there exists any backup in the disk that is not in the tree
        // TODO: To automatically fix the database data according to output of `pgbackrest info`
        val noLogical = physical.map { it.label }.minus(nodesOnTree)
        if (noLogical.isNotEmpty()) {
            throw IllegalStateException(
                "Backup tree is inconsistent, please check your backup data. " +
                        "The following backups are missing in the database: ${noLogical.joinToString(", ")}"
            )
        }
    }

    private fun checkAliasUnique(): Boolean {
        return allNodes.values.mapNotNull { it.alias }.size == allNodes.values.mapNotNull { it.alias }.distinct().size
    }

    /**
     * Get snapshot data stored in database. WON'T check if the backup tree is valid.
     *
     * @return Root node of backup tree
     */
    @Throws(IllegalStateException::class)
    private fun updateLogicalData() {
        val url = "jdbc:postgresql://localhost:5432/db_backup_tree"

        backupDataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT label, is_expired, parent, backup_alias, description, backup_type, created_at
                FROM db_backup_tree
                WHERE instance_name = ?
            """.trimIndent()).use { stmt ->
                stmt.setString(1, instanceName)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val node = BackupNode(
                        label = rs.getString("label"),
                        isExpired = rs.getBoolean("is_expired"),
                        alias = rs.getString("backup_alias"),
                        description = rs.getString("description"),
                        parent = rs.getString("parent"),
                        backupType = BackupType.valueOf(rs.getString("backup_type")),
                        createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
                    )
                    allNodes[node.label] = node
                }
            }
        }

        if (allNodes.isEmpty()) {
            globalLogger.warn("No snapshot found, creating your first snapshot is highly recommended")
            return
        }

        allNodes.forEach { (_, node) ->
            if (node.backupType == BackupType.FULL)
                return@forEach
            val parent = allNodes[node.parent]
                ?: throw IllegalStateException(
                    "Database logical error: Parent of ${node.label} (${node.parent}) not found")
            parent.children.add(node)
        }

        rootNode = allNodes.values.filter { it.parent == null && !it.isExpired }.maxBy { it.createdAt }
        logicalPreviousNode = allNodes[PGInstances.instances[instanceName]!!.logicalPrior]
            ?: allNodes.values.maxBy { it.createdAt }
    }

    fun getLogicalData(): List<BackupNode> {
        val url = "jdbc:postgresql://localhost:5432/db_backup_tree"
        val nodes: MutableList<BackupNode> = mutableListOf()

        backupDataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT label, parent, backup_alias, description, backup_type, created_at
                FROM db_backup_tree
                WHERE instance_name = '$instanceName' AND is_expired = true
            """.trimIndent()).use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    nodes.add(BackupNode(
                        label = rs.getString("label"),
                        isExpired = true,
                        alias = rs.getString("backup_alias"),
                        description = rs.getString("description"),
                        parent = rs.getString("parent"),
                        backupType = BackupType.valueOf(rs.getString("backup_type")),
                        createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
                    ))
                }
            }
        }

        return nodes
    }

    private data class PhysicalBackupData(
        val label: String,
        val prior: String?,
        val type: BackupType,
        val startTimestamp: OffsetDateTime,
        val stopTimestamp: OffsetDateTime
    )

    /**
     * Get physical backup data in the disk
     *
     * Here is an example of `pgbackdata info` output:
     *
     * ```json
     * [
     *   {
     *     "archive": [
     *       {
     *         "database": { "id": 1, "repo-key": 1 },
     *         "id": "16-1",
     *         "max": "00000001000000000000000B",
     *         "min": "000000010000000000000001"
     *       }
     *     ],
     *     "backup": [
     *       {
     *         "archive": { "start": "000000010000000000000003", "stop": "000000010000000000000003" },
     *         "backrest": { "format": 5, "version": "2.50" },
     *         "database": { "id": 1, "repo-key": 1 },
     *         "error": false,
     *         "info": { "delta": 31049162, "repository": { "delta": 4106998, "size": 4106998 }, "size": 31049162 },
     *         "label": "20260104-142406F",
     *         "lsn": { "start": "0/3000060", "stop": "0/3000170" },
     *         "prior": null,
     *         "reference": null,
     *         "timestamp": { "start": 1767507846, "stop": 1767507910 },
     *         "type": "full"
     *       }
     *     ],
     *     "cipher": "none",
     *     "db": [ { "id": 1, "repo-key": 1, "system-id": 7591364124800804000, "version": "16" } ],
     *     "name": "test",
     *     "repo": [
     *       { "cipher": "none", "key": 1, "status": { "code": 0, "message": "ok" } }
     *     ],
     *     "status": { "code": 0, "lock": { "backup": { "held": false } }, "message": "ok" }
     *   }
     * ]
     * ```
     *
     * What we want is every 'label', 'prior', 'type' in "backup".
     */
    private fun obtainPhysicalBackup(): MutableSet<PhysicalBackupData> {
        val backups = mutableSetOf<PhysicalBackupData>()
        val cmd = """
            sudo -u postgres pgbackrest --stanza=${instanceName} \
                --config=${backupDirectory.absolutePathString()}/pgbackrest.conf \
                --output=json info
        """.trimIndent()
        val process = ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start()
        process.waitFor()
        if (process.exitValue() != 0)
            globalLogger.error("Failed to obtain physical backup data: " +
                process.inputStream.bufferedReader().readText()
            )

        val json = jacksonObjectMapper().readTree(process.inputStream.bufferedReader().readText())
        val backupNodes = json.at("/0/backup")
        for (node in backupNodes) { backups.add(parseBackupNode(node)) }

        return backups
    }

    /**
     * Create a backup
     *
     * @param alias The alias of the backup
     * @param description The description of the backup
     * @param isFull Whether to create a full backup
     */
    @Throws(IllegalStateException::class)
    fun backup(alias: String? = null, description: String? = null, isFull: Boolean = false): String {
        return if (isFull) createFullBackup(alias, description)
               else createAugmentedBackup(alias, description)
    }

    private fun checkPgbackrest(): Boolean {
        globalLogger.info("Checking pgbackrest for instance $instanceName")

        val cmd = """
            sudo -u postgres pgbackrest \
            --config=${backupDirectory.absolutePathString()}/pgbackrest.conf \
            --stanza=$instanceName check
        """.trimIndent()
        val process = ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start()
        process.waitFor()

        if (process.exitValue() != 0)
            globalLogger.warn("""
                pgbackrest for $instanceName not ready. Process output:
                ${process.inputStream.bufferedReader().readText()}
            """.trimIndent())
        return process.exitValue() == 0
    }

    private val backupLock: ReentrantLock = ReentrantLock()

    /**
     * Create a full backup in the disk.
     *
     * According to pgbackrest's features, when a full backup is expired, all diff / incr backups affiliated
     * will be deleted. So after creating this backup, we need to check current backup status through
     * `pgbackrest info` to check if there are any expired backups, and adjust our backup tree.
     *
     * @param alias The alias of the backup
     * @param description The description of the backup
     * @return The label of the backup
     */
    @Throws(IllegalStateException::class)
    private fun createFullBackup(alias: String? = null, description: String? = null): String {
        backupLock.withLock {
            // Check for pgbackrest need to keep the instance running
            if (!PGInstances.instanceIsOn(instanceName))
                PGInstances.startInstance(instanceName)

            if (!checkPgbackrest())
                throw IllegalStateException("pgbackrest for instance $instanceName not ready")

            globalLogger.info("Creating FULL backup for instance $instanceName")

            val cmd = """
                sudo -u postgres pgbackrest --stanza=${instanceName} \
                    --config=${backupDirectory.absolutePathString()}/pgbackrest.conf \
                    --type=full --log-level-console=info backup
            """.trimIndent()
            val process = ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start()
            process.waitFor()

            // Check process exit code
            if (process.exitValue() != 0)
                throw IllegalStateException("""
                Error occurred while creating FULL backup for instance $instanceName. Process output:
                ${process.inputStream.bufferedReader().readText()}
            """.trimIndent())

            // Update database

            val physicalBackups = obtainPhysicalBackup()
            val lastFullBackup: PhysicalBackupData = physicalBackups.maxBy { it.startTimestamp }
            check(lastFullBackup.type == BackupType.FULL) { "Last backup is not a FULL backup" }

            // In extreme conditions (repo1-retention-full=1), the prior backup may be deleted, we need to check it
            if (physicalBackups.find { it.label == PGInstances.instances[instanceName]!!.logicalPrior} == null)
                PGInstances.instances[instanceName]!!.logicalPrior = null

            globalLogger.info("Creating FULL backup for instance $instanceName (${lastFullBackup.label}) completed")

            // Update database
            backupDataSource.connection.use { conn ->
                conn.prepareStatement("""
                    INSERT INTO db_backup_tree 
                    (instance_name, label, parent, backup_alias, description, backup_type, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, instanceName)
                    stmt.setString(2, lastFullBackup.label)
                    stmt.setString(3, PGInstances.instances[instanceName]!!.logicalPrior)
                    stmt.setString(4, alias)
                    stmt.setString(5, description)
                    stmt.setString(6, BackupType.FULL.name)
                    stmt.setObject(7, lastFullBackup.startTimestamp)
                    stmt.executeUpdate()
                }
            }

            // If there is any backup expired, mark them in the database
            // TODO: Here we only check if there are labels that don't exist in the disk. Is it ok?
            backupDataSource.connection.use { conn ->
                conn.prepareStatement("""
                    UPDATE db_backup_tree SET is_expired = true
                    WHERE instance_name = '$instanceName'
                        AND label NOT IN (${physicalBackups.joinToString(", ") { "'${it.label}'" }})
                """.trimIndent()).use { stmt ->
                    stmt.executeUpdate()
                }
            }

            // Delete expired backups in database
            backupDataSource.connection.use { conn ->
                conn.prepareStatement("""
                    DELETE FROM db_backup_tree
                    WHERE is_expired = true
                """.trimIndent()).use { stmt ->
                    stmt.executeUpdate()
                }
            }

            PGInstances.instances[instanceName]!!.logicalPrior = lastFullBackup.label
            globalLogger.info("Database updated for instance $instanceName")

            // Update logical backup tree at once
            updateLogicalData()

            return lastFullBackup.label
        }
    }

    /**
     * Create an augmented backup in the disk. If we just performed a restoration to a non-latest backup
     * and haven't done any backup, that means that we need to create a DIFF backup after the latest FULL backup.
     * Else, we need to create an INCR backup after the latest backup.
     */
    @Throws(IllegalStateException::class)
    private fun createAugmentedBackup(alias: String? = null, description: String? = null): String {
        backupLock.withLock {
            // Check for pgbackrest need to keep the instance running
            if (!PGInstances.instanceIsOn(instanceName))
                PGInstances.startInstance(instanceName)

            if (!checkPgbackrest())
                throw IllegalStateException("pgbackrest for instance $instanceName not ready")

            var physicalBackups = obtainPhysicalBackup()
            val lastBackup: PhysicalBackupData = physicalBackups.maxBy { it.startTimestamp }
            val logicPrior = PGInstances.instances[instanceName]!!.logicalPrior
            // If the logical prior backup is not the latest backup, that indicates that we just restored the data
            // with no backup ahead. In this case, we should create a DIFF backup instead.
            val backupType = if (logicPrior != lastBackup.label) BackupType.DIFF else BackupType.INCR

            globalLogger.info("Creating ${backupType.name} backup for instance $instanceName")

            val cmd = """
                sudo -u postgres pgbackrest --stanza=${instanceName} \
                    --config=${backupDirectory.absolutePathString()}/pgbackrest.conf \
                    --type=${backupType.name.lowercase()} --log-level-console=info backup
            """.trimIndent()
            val process = ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start()
            process.waitFor()

            // Check process exit code
            if (process.exitValue() != 0)
                throw IllegalStateException("""
                Error occurred while creating ${backupType.name} backup for instance $instanceName. Process output:
                ${process.inputStream.bufferedReader().readText()}
            """.trimIndent())

            val thisBackup = obtainPhysicalBackup().maxBy { it.startTimestamp }
            globalLogger.info(
                "Creating ${backupType.name} backup for instance $instanceName (${thisBackup.label}) completed")

            backupDataSource.connection.use { conn ->
                conn.prepareStatement("""
                    INSERT INTO db_backup_tree 
                    (instance_name, label, parent, backup_alias, description, backup_type, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, instanceName)
                    stmt.setString(2, thisBackup.label)
                    stmt.setString(3, PGInstances.instances[instanceName]!!.logicalPrior)
                    stmt.setString(4, alias)
                    stmt.setString(5, description)
                    stmt.setString(6, backupType.name)
                    stmt.setObject(7, thisBackup.startTimestamp)
                    stmt.executeUpdate()
                }
            }
            PGInstances.instances[instanceName]!!.logicalPrior = thisBackup.label

            // Update logical backup tree
            val thisNode = BackupNode(
                label = thisBackup.label,
                isExpired = false,
                alias = alias,
                description = description,
                parent = logicPrior,
                backupType = backupType,
                createdAt = thisBackup.startTimestamp
            )
            allNodes[logicPrior]!!.children.add(thisNode)
            allNodes[thisBackup.label] = thisNode

            return thisBackup.label
        }
    }

    @Throws(IllegalArgumentException::class)
    fun restoreBackup(labelOrAlias: String) {
        backupLock.withLock {
            val target = findBackup(labelOrAlias)
                ?: throw IllegalArgumentException("Backup $labelOrAlias not found in the database")
            if (target.isExpired) throw IllegalArgumentException("Backup $labelOrAlias is already expired")

            // Confirm again if this backup exists in the disk
            findBackupInDisk(target.label)
                ?: throw IllegalArgumentException("Backup $labelOrAlias not found in the disk")

            // When performing restoration, there should be no lock occupied for this instance
            if (PGInstances.tokenSessions.instanceIsInUse(instanceName))
                throw IllegalStateException("""
                    Instance $instanceName is in use. Please wait for the current operation to finish.
                """.trimIndent())

            // Perform physical restoration
            // 1. We need to stop the instance first
            if (PGInstances.instanceIsOn(instanceName))
                PGInstances.shutdownInstance(instanceName)

            // 2. Restore
            val cmd = """
                sudo -u postgres pgbackrest --stanza=${instanceName} \
                --config=${backupDirectory.absolutePathString()}/pgbackrest.conf \
                --delta --type=immediate --set=${target.label} restore
            """.trimIndent()
            val process = ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start()
            process.waitFor()

            if (process.exitValue() != 0)
                throw IllegalStateException("""
                    Error occurred while restoring backup $labelOrAlias for instance $instanceName. Process output:
                    ${process.inputStream.bufferedReader().readText()}
                """.trimIndent())

            // 3. Remove file `recovery.signal` and restart the instance, to make the instance backup-able again
            // postgresql needed to read recovery.signal (although it may be empty) after restoration, or it will
            // refuse to start
            PGInstances.startInstance(instanceName)
            PGInstances.shutdownInstance(instanceName)

            // Because recovery.signal is in the directory that owned by `postgres`, we need to use `sudo` to delete it
            val deleteProcess = ProcessBuilder("sudo", "rm",
                PGInstances.instanceRoot
                    .resolve(instanceName)
                    .resolve("recovery.signal").absolutePathString()
            ).redirectErrorStream(true).start()
            deleteProcess.waitFor()

            // 4. Update the logical prior backup
            PGInstances.instances[instanceName]!!.logicalPrior = target.label
        }
    }

    private fun findBackup(labelOrAlias: String): BackupNode? {
        allNodes[labelOrAlias] ?.let { return it }
        allNodes.values.find { it.alias == labelOrAlias } ?.let { return it }
        return null
    }

    /**
     * Find a backup in the disk using `pgbackrest info` command
     *
     * @param label The backup label
     * @return The backup data
     */
    @Throws(IllegalStateException::class)
    private fun findBackupInDisk(label: String): PhysicalBackupData? {
        val cmd = """
            sudo -u postgres pgbackrest --stanza=${instanceName} \
                --config=${backupDirectory.absolutePathString()}/pgbackrest.conf \
                --output=json --set=$label info
        """.trimIndent()
        val process = ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start()
        process.waitFor()

        if (process.exitValue() != 0)
            throw IllegalStateException("""
                Error occurred while querying backup $label for instance $instanceName. Process output:
                ${process.inputStream.bufferedReader().readText()}
            """.trimIndent())

        val json = jacksonObjectMapper().readTree(process.inputStream.bufferedReader().readText())

        val message = json.at("/0/status/message").textValue()
        if (message == "requested backup not found")
            return null

        val backupNode = json.at("/0/backup/0")
        return parseBackupNode(backupNode)
    }

    companion object {
        @Throws(IllegalArgumentException::class)
        private fun parseBackupNode(node: JsonNode): PhysicalBackupData {
            val label = node.at("/label").let {
                if (it.isMissingNode) throw IllegalArgumentException("Missing label.")
                else it.textValue()
            }
            val prior = node.at("/prior").let {
                if (it.isMissingNode) throw IllegalArgumentException("Missing prior.")
                else it.textValue()
            }
            val type = BackupType.valueOf(node.at("/type").let {
                if (it.isMissingNode) throw IllegalArgumentException("Missing type.")
                else it.textValue().uppercase()
            })
            val startTimestamp = OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(node.at("/timestamp/start").let {
                    if (it.isMissingNode) throw IllegalArgumentException("Missing timestamp/start.")
                    else it.longValue()
                }),
                TimeZone.getDefault().toZoneId()
            )
            val stopTimestamp = OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(node.at("/timestamp/stop").let {
                    if (it.isMissingNode) throw IllegalArgumentException("Missing timestamp/stop.")
                    else it.longValue()
                }),
                TimeZone.getDefault().toZoneId()
            )
            return PhysicalBackupData(
                label = label,
                prior = prior,
                type = type,
                startTimestamp = startTimestamp,
                stopTimestamp = stopTimestamp
            )
        }
    }
}