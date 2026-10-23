package com.course.todo.data

import android.content.Context
import com.course.todo.core.CompletionState
import com.course.todo.core.Course
import com.course.todo.core.MarkdownDocument
import com.course.todo.core.SampleData
import com.course.todo.core.StudySnapshot
import com.course.todo.core.StudyTask
import com.course.todo.core.TaskKind
import com.course.todo.storage.AppPreferences
import com.course.todo.storage.MarkdownStore
import com.course.todo.storage.SnapshotStore
import com.course.todo.widget.WidgetUpdater
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

data class ManualCourse(val id: String, val name: String, val createdAt: Long)
data class ManualTaskItem(
    val id: String,
    val courseId: String,
    val title: String,
    val kind: TaskKind,
    val deadlineEpochMillis: Long?,
    val createdAt: Long,
)
data class ManualData(val courses: List<ManualCourse>, val tasks: List<ManualTaskItem>)

/** 手动添加的课程/任务：独立 JSON 文件保存，同步时合并进 Markdown，不会被同步覆盖。 */
class ManualTaskStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "manual_tasks.json")
    private val lock = Any()

    fun load(): ManualData = synchronized(lock) { ManualTasksJson.decode(file.takeIf { it.exists() }?.readText()) }

    fun addCourse(name: String): ManualData {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "课程名不能为空" }
        return update { data ->
            require(data.courses.none { it.name == trimmed }) { "课程已存在" }
            data.copy(
                courses = data.courses + ManualCourse("m-course-${newId()}", trimmed, System.currentTimeMillis()),
            )
        }
    }

    fun addTask(courseId: String, title: String, kind: TaskKind, deadline: Instant?): ManualData {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "任务名不能为空" }
        return update { data ->
            require(data.courses.any { it.id == courseId }) { "请先选择课程" }
            data.copy(
                tasks = data.tasks + ManualTaskItem(
                    id = "m-task-${newId()}",
                    courseId = courseId,
                    title = trimmed,
                    kind = kind,
                    deadlineEpochMillis = deadline?.toEpochMilli(),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteCourse(courseId: String): ManualData = update { data ->
        data.copy(
            courses = data.courses.filterNot { it.id == courseId },
            tasks = data.tasks.filterNot { it.courseId == courseId },
        )
    }

    fun deleteTask(taskId: String): ManualData = update { data ->
        data.copy(tasks = data.tasks.filterNot { it.id == taskId })
    }

    /** 把手动课程/任务合并进快照；同名课程会并入已有课程，避免重复分组。 */
    fun mergeInto(snapshot: StudySnapshot): StudySnapshot {
        val data = load()
        if (data.courses.isEmpty() && data.tasks.isEmpty()) return snapshot
        val syncedByName = snapshot.courses.associateBy { it.name }
        val manualCourses = mutableListOf<Course>()
        val courseIdOverride = mutableMapOf<String, String>()
        val courseNameOverride = mutableMapOf<String, String>()
        data.courses.forEach { course ->
            val existing = syncedByName[course.name]
            if (existing != null) {
                courseIdOverride[course.id] = existing.id
                courseNameOverride[course.id] = existing.name
            } else {
                manualCourses += Course(
                    id = course.id,
                    classId = "",
                    cpi = "",
                    name = course.name,
                )
            }
        }
        val courseById = data.courses.associateBy { it.id }
        val manualTasks = data.tasks.mapNotNull { item ->
            val course = courseById[item.courseId] ?: return@mapNotNull null
            val courseId = courseIdOverride[item.courseId] ?: item.courseId
            val courseName = courseNameOverride[item.courseId] ?: course.name
            val deadline = item.deadlineEpochMillis?.let(Instant::ofEpochMilli)
            StudyTask(
                id = "manual:${item.id}",
                courseId = courseId,
                courseName = courseName,
                title = item.title,
                kind = item.kind,
                state = if (deadline != null && deadline.isBefore(Instant.now())) {
                    CompletionState.EXPIRED
                } else {
                    CompletionState.PENDING
                },
                deadline = deadline,
            )
        }
        return snapshot.copy(
            courses = snapshot.courses + manualCourses,
            tasks = snapshot.tasks + manualTasks,
        )
    }

    private fun update(transform: (ManualData) -> ManualData): ManualData = synchronized(lock) {
        val updated = transform(load())
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(ManualTasksJson.encode(updated))
        if (!temp.renameTo(file)) {
            file.writeText(ManualTasksJson.encode(updated))
            temp.delete()
        }
        updated
    }

    private fun read() = ManualTasksJson.decode(file.takeIf { it.exists() }?.readText())

    private fun newId(): String = UUID.randomUUID().toString().take(8)

    companion object {
        /** 重新生成 Markdown（快照 + 手动任务），并刷新 App/小组件。 */
        fun rebuildMarkdown(context: Context) {
            val appContext = context.applicationContext
            val base = SnapshotStore(appContext).read()
                ?: if (AppPreferences(appContext).demoMode) {
                    SampleData.snapshot()
                } else {
                    StudySnapshot(emptyList(), emptyList(), Instant.now(), emptyList(), emptyList())
                }
            val merged = ManualTaskStore(appContext).mergeInto(base)
            MarkdownStore(appContext).write(MarkdownDocument.render(merged))
            WidgetUpdater.refreshAll(appContext)
        }
    }
}

/** 手动任务的 JSON 编解码（单独拆出便于单元测试）。 */
object ManualTasksJson {
    fun encode(data: ManualData): String = JSONObject().apply {
        put("courses", JSONArray().apply {
            data.courses.forEach { course ->
                put(JSONObject().apply {
                    put("id", course.id)
                    put("name", course.name)
                    put("createdAt", course.createdAt)
                })
            }
        })
        put("tasks", JSONArray().apply {
            data.tasks.forEach { task ->
                put(JSONObject().apply {
                    put("id", task.id)
                    put("courseId", task.courseId)
                    put("title", task.title)
                    put("kind", task.kind.name)
                    put("deadline", task.deadlineEpochMillis ?: JSONObject.NULL)
                    put("createdAt", task.createdAt)
                })
            }
        })
    }.toString()

    fun decode(raw: String?): ManualData {
        if (raw.isNullOrBlank()) return ManualData(emptyList(), emptyList())
        return runCatching {
            val root = JSONObject(raw)
            val courses = (0 until root.optJSONArray("courses").orEmptyLength()).mapNotNull { index ->
                val item = root.optJSONArray("courses")?.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name").trim()
                if (name.isBlank()) null else ManualCourse(
                    id = item.optString("id").ifBlank { "m-course-${index}" },
                    name = name,
                    createdAt = item.optLong("createdAt", 0L),
                )
            }
            val courseIds = courses.map { it.id }.toSet()
            val tasks = (0 until root.optJSONArray("tasks").orEmptyLength()).mapNotNull { index ->
                val item = root.optJSONArray("tasks")?.optJSONObject(index) ?: return@mapNotNull null
                val title = item.optString("title").trim()
                val courseId = item.optString("courseId")
                if (title.isBlank() || courseId !in courseIds) return@mapNotNull null
                ManualTaskItem(
                    id = item.optString("id").ifBlank { "m-task-$index" },
                    courseId = courseId,
                    title = title,
                    kind = runCatching { TaskKind.valueOf(item.optString("kind")) }
                        .getOrDefault(TaskKind.ASSIGNMENT),
                    deadlineEpochMillis = item.optLong("deadline", -1L).takeIf { it > 0 },
                    createdAt = item.optLong("createdAt", 0L),
                )
            }
            ManualData(courses, tasks)
        }.getOrDefault(ManualData(emptyList(), emptyList()))
    }

    private fun JSONArray?.orEmptyLength(): Int = this?.length() ?: 0
}
