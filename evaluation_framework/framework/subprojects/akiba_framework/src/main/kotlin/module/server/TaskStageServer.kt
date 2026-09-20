//package org.iotsplab.akiba.module.server
//import io.ktor.http.*
//import io.ktor.server.application.*
//import io.ktor.server.response.*
//import kotlinx.serialization.Serializable
//import kotlinx.serialization.json.Json
//import org.iotsplab.akiba.utils.RouteRequestMethod
//import org.iotsplab.akiba.utils.TaskInterface
//import java.util.concurrent.locks.ReentrantLock
//import kotlin.concurrent.withLock
//
//@RouteRequestMethod(["POST"])
//object TaskStageServer : DynamicServer("TaskStageServer", "/task_stages")  {
//    data class TaskStageInfo (
//        var name: String,
//        var id: Int = 0,
//        var stageSetId: Int = 0,
//        var currentStage: Int = 0
//    )
//
//    data class StageSet (
//        var stages: MutableList<String> = mutableListOf(),
//        var usingTaskCount: Int = 0
//    )
//
//    @Serializable
//    data class OverallStageMessage (
//        var ongoingCount: Int = 0,
//        var totalTaskCount: Int = 0,
//        var finished: Int = 0,
//        var ongoing: Array<TaskStageMessage> = arrayOf()
//    )
//
//    @Serializable
//    data class TaskStageMessage (
//        var name: String,
//        var status: String = "waiting",
//        var id: Int = 0,
//        var currentStage: Int = 0,
//        var currentStageName: String = "unknown stage",
//        var stageCount: Int = 0
//    )
//
//    private val tasks: HashMap<Int, TaskStageInfo> = hashMapOf()      // task id -> task stage
//    private val ongoingTasks: HashSet<Int> = hashSetOf()
//    private val finishedTasks: HashSet<Int> = hashSetOf()
//    private val stageSets: MutableMap<Int, StageSet> = mutableMapOf()
//    private var nextId: Int = 0
//    private var nextStageSetId: Int = 0
//    private val taskStageAddLock: ReentrantLock = ReentrantLock()
//
//    private val json = Json { encodeDefaults = true }
//
//    @TaskInterface
//    fun addTask(taskName: String, stages: List<String>): Int {
//        return addTaskWithId(nextId, taskName, stages)
//    }
//
//    @TaskInterface
//    fun addTaskWithId(id: Int, taskName: String, stages: List<String>): Int {
//        require(tasks[id] == null) { "Id occupied" }
//        val stageSetId: Int
//        taskStageAddLock.withLock {
//             stageSetId = stageSets.filter { it.value.stages == stages }.firstNotNullOfOrNull { it.key }
//                ?: run {
//                    stageSets[nextStageSetId] = StageSet(stages.toMutableList(), 0)
//                    nextStageSetId++
//                }
//            stageSets[stageSetId]!!.usingTaskCount++
//        }
//
//        tasks[id] = TaskStageInfo(taskName, id, stageSetId)
//        nextId = if (nextId > id) nextId else id + 1
//        return id
//    }
//
//    @TaskInterface
//    fun removeTask(id: Int) {
//        tasks.filter { it.value.id == id }.map { it.value }.firstNotNullOfOrNull {
//            stageSets[it.stageSetId]!!.usingTaskCount--
//            if (stageSets[it.stageSetId]!!.usingTaskCount == 0)
//                stageSets.remove(it.stageSetId)
//            tasks.remove(it.id)
//        } ?: require(false) { "Id does not exist" }
//    }
//
//    @TaskInterface
//    fun startTask(id: Int) {
//        tasks.filter { it.value.id == id }.map { it.value }.firstNotNullOfOrNull {
//            require(it.currentStage == 0) { "Task already started" }
//            it.currentStage = 1
//            ongoingTasks.add(it.id)
//            finishedTasks.remove(it.id)
//            it
//        } ?: require(false) { "Id does not exist" }
//    }
//
//    @TaskInterface
//    fun step(id: Int) {
//        tasks.filter { it.value.id == id }.map { it.value }.firstNotNullOfOrNull {
//            require(it.currentStage != 0) { "Task not started" }
//            require(it.currentStage <= stageSets[it.stageSetId]!!.stages.size) { "Task already finished" }
//            it.currentStage++
//            if (it.currentStage == stageSets[it.stageSetId]!!.stages.size + 1) {
//                ongoingTasks.remove(it.id)
//                finishedTasks.add(it.id)
//            }
//        } ?: require(false) { "Id does not exist" }
//    }
//
//    @TaskInterface
//    fun complete(id: Int) {
//        tasks.filter { it.value.id == id }.map { it.value }.firstNotNullOfOrNull {
//            require(it.currentStage != 0) { "Task not started" }
//            require(it.currentStage <= stageSets[it.stageSetId]!!.stages.size) { "Task already finished" }
//            it.currentStage = stageSets[it.stageSetId]!!.stages.size + 1
//            ongoingTasks.remove(it.id)
//            finishedTasks.add(it.id)
//        } ?: require(false) { "Id does not exist" }
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
//            call.respond(HttpStatusCode.OK, parseStageMessage(id))
//        } else {
//            call.respond(HttpStatusCode.OK, json.encodeToString( OverallStageMessage(
//                ongoingCount = ongoingTasks.size,
//                totalTaskCount = tasks.size,
//                finished = finishedTasks.size,
//                ongoing = ongoingTasks.map { parseStageMessage(it) }.toTypedArray()
//            )))
//        }
//    }
//
//    private fun parseStageMessage(id: Int): TaskStageMessage {
//        return tasks[id] ?.let { task ->
//            val stageSet = stageSets[task.stageSetId]!!
//            TaskStageMessage(
//                name = task.name,
//                status =
//                    if (ongoingTasks.contains(id)) "ongoing"
//                    else if (task.stageSetId == 0) "waiting"
//                    else "finished",
//                id = id,
//                currentStage = task.currentStage,
//                currentStageName =
//                    if (task.currentStage == stageSet.stages.size + 1) "finished"
//                    else stageSet.stages[task.currentStage - 1],
//                stageCount = stageSet.stages.size
//            )
//        } ?: TaskStageMessage("error", "no such stage", id)
//    }
//}