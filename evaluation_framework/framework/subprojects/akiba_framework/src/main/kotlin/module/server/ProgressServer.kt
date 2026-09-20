//package org.iotsplab.akiba.module.server
//
//import io.ktor.http.*
//import io.ktor.server.application.*
//import io.ktor.server.response.*
//import io.ktor.utils.io.*
//import io.ktor.utils.io.locks.*
//import kotlinx.serialization.Serializable
//import kotlinx.serialization.json.Json
//import org.iotsplab.akiba.mainAuxiliary.WorkspaceManager.globalLogger
//import org.iotsplab.akiba.utils.RouteRequestMethod
//import org.iotsplab.akiba.utils.TaskInterface
//import java.util.concurrent.locks.ReentrantLock
//import kotlin.collections.get
//
//@RouteRequestMethod(["POST"])
//class ProgressServer() : DynamicServer("ProgressServer", "/progress") {
//    data class TaskStatus (
//        var name: String? = null,
//        var ongoing: Boolean = false,
//        var progress: Int = 0,
//        var total: Int = 0,
//    )
//
//    @Serializable
//    data class ProgressMessage (
//        var ongoingCount: Int = 0,
//        var totalTaskCount: Int = 0,
//        var finished: Int = 0,
//        var ongoing: Array<OngoingMessage> = arrayOf()
//    )
//
//    @Serializable
//    data class OngoingMessage (
//        var id: Int = 0,
//        var name: String? = null,
//        var current: Int = 0,
//        var total: Int = 0,
//    )
//
//    private val tasks: HashMap<Int, TaskStatus> = hashMapOf()
//    private val ongoingTasks: HashSet<Int> = hashSetOf()
//    private val finishedTasks: HashSet<Int> = hashSetOf()
//    val unstartedTaskCount: Int
//        get() = tasks.size - finishedTasks.size - ongoingTasks.size
//
//    private val json = Json { encodeDefaults = true }
//    private val updateLock = ReentrantLock()
//
//    constructor(taskNames: List<String>): this() {
//        taskNames.forEachIndexed { index, s -> tasks[index] = TaskStatus(name = s) }
//    }
//
//    @TaskInterface
//    fun addTask(taskName: String, id: Int, total: Int = 0) {
//        if (tasks.containsKey(id)) { require(false) { "Id already exists" } }
//        else tasks[id] = TaskStatus(name = taskName, total = total)
//    }
//
//    @TaskInterface
//    fun removeTask(id: Int) {
//        if (tasks.containsKey(id)) { tasks.remove(id) }
//    }
//
//    @TaskInterface
//    fun defineTotal(id: Int, total: Int) {
//        if (!tasks.containsKey(id)) { require(false) { "Id does not exist" } }
//        tasks[id]?.total = total
//    }
//
//    @TaskInterface
//    fun startTask(id: Int) {
//        if (!tasks.containsKey(id)) { require(false) { "Id does not exist" } }
//        else {
//            if (tasks[id]!!.total == 0) {
//                finishedTasks.add(id)
//                return
//            }
//            ongoingTasks.add(id)
//            tasks[id]?.ongoing = true
//        }
//    }
//
//    @OptIn(InternalAPI::class)
//    @TaskInterface
//    fun step(id: Int) {
//        tasks[id] ?. let {
//            require(it.ongoing) { "Task is not started" }
//            updateLock.withLock {
//                if (it.progress == it.total - 1) {
//                    ongoingTasks.remove(id)
//                    finishedTasks.add(id)
//                    globalLogger.info("Finished")
//                }
//                it.progress++
//            }
//        }
//            ?: require(false) { "Invalid task id" }
//    }
//
//    @OptIn(InternalAPI::class)
//    @TaskInterface
//    fun set(id: Int, value: Int) {
//        tasks[id] ?. let {
//            updateLock.withLock {
//                if (value > it.total) {
//                    it.progress = it.total
//                    ongoingTasks.remove(id)
//                    finishedTasks.add(id)
//                } else if (value < 0) {
//                    it.progress = 0
//                } else {
//                    it.progress = value
//                    it.ongoing = true
//                    finishedTasks.remove(id)
//                }
//            }
//        }
//            ?: require(false) { "Invalid task id" }
//    }
//
//    override suspend fun respondPost(call: ApplicationCall) {
//        if (!enabled) {
//            call.respond(HttpStatusCode.NotFound, null)
//            return
//        }
//        val id: Int? = runCatching { call.parameters["id"]?.toInt() }.getOrElse {
//            call.respond(HttpStatusCode.BadRequest, "Id must be integer")
//            return
//        }
//        if (id != null) {
//            if (tasks[id] == null)
//                call.respond(HttpStatusCode.BadRequest, "Task not found")
//            else
//                call.respond(HttpStatusCode.OK, getProcess(id))
//        }
//        else {
//            // Send all ongoing tasks and finished task count
//            call.respond(HttpStatusCode.OK, json.encodeToString( ProgressMessage(
//                ongoingTasks.size,
//                tasks.size,
//                finishedTasks.size,
//                ongoingTasks.map {
//                    val ongoingMessage = getProcess(it)
//                    assert(ongoingMessage.containsKey("ongoing"))
//                    val message = (ongoingMessage["ongoing"] as Array<*>)[0] as MutableMap<*, *>
//                    OngoingMessage(message["id"] as Int, message["name"] as String,
//                        message["current"] as Int, message["total"] as Int)
//                }.toTypedArray()
//            )))
//        }
//    }
//
//    /**
//     * Retrieves task processing progress information based on task ID and mode flags
//     *
//     * @param id Unique identifier of the task to query (integer)
//     * @return Map containing task status information with varying structures:
//     *         - {"error": "Task not found"} when task doesn't exist
//     *         - {"finished": [id]} for completed tasks
//     *         - {"waiting": [id]} for pending tasks
//     *         - {"ongoing": [resultMap]} for active tasks, where resultMap may contain:
//     *           - name: Task name (when corresponding flag is set)
//     *           - progress: Progress percentage (when corresponding flag is set)
//     *           - current/total: Raw progress values (default)
//     */
//    private fun getProcess(id: Int): Map<String, Any> {
//        // Check task existence and return immediately if not found
//        val status: TaskStatus = tasks[id] ?: return mapOf("error" to "Task not found")
//
//        // Handle non-active task states (completed or pending)
//        if (!status.ongoing && status.progress == status.total)
//            return mapOf("finished" to arrayOf(id))
//        else if (!status.ongoing)
//            return mapOf("waiting" to arrayOf(id))
//
//        // Build base result map and populate fields based on mode flags
//        val result: MutableMap<String, Any> = mutableMapOf()
//        result["name"] = status.name ?: "$id"
//        result["id"] = id
//
//        // Default raw progress values
//        result["current"] = status.progress
//        result["total"] = status.total
//
//        return mapOf("ongoing" to arrayOf(result))
//    }
//}