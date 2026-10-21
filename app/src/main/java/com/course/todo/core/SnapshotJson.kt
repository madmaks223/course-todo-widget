package com.course.todo.core

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * [StudySnapshot] 的 JSON 序列化。
 *
 * 轻量同步只请求 3 个接口（课程 / 全部作业 / 全部考试），需要从上次完整同步的
 * 快照里合并出绝对开始时间、截止时间、分数与任务点，因此把快照保存到本机。
 */
object SnapshotJson {
    fun encode(snapshot: StudySnapshot): String = JSONObject().apply {
        put("fetchedAt", snapshot.fetchedAt.toString())
        put("courses", JSONArray().apply {
            snapshot.courses.forEach { course ->
                put(JSONObject().apply {
                    put("id", course.id)
                    put("classId", course.classId)
                    put("cpi", course.cpi)
                    put("name", course.name)
                    put("teacher", course.teacher)
                })
            }
        })
        put("tasks", JSONArray().apply {
            snapshot.tasks.forEach { task ->
                put(JSONObject().apply {
                    put("id", task.id)
                    put("courseId", task.courseId)
                    put("courseName", task.courseName)
                    put("title", task.title)
                    put("kind", task.kind.name)
                    put("state", task.state.name)
                    task.startTime?.let { put("startTime", it.toString()) }
                    task.deadline?.let { put("deadline", it.toString()) }
                    task.deadlineText?.let { put("deadlineText", it) }
                    task.url?.let { put("url", it) }
                    task.score?.let { put("score", it) }
                })
            }
        })
        put("taskPoints", JSONArray().apply {
            snapshot.taskPoints.forEach { points ->
                put(JSONObject().apply {
                    put("courseId", points.courseId)
                    put("courseName", points.courseName)
                    put("pending", points.pending)
                    put("chapters", JSONArray().apply {
                        points.chapters.forEach { chapter ->
                            put(JSONObject().apply {
                                put("number", chapter.number)
                                put("title", chapter.title)
                                put("pending", chapter.pending)
                            })
                        }
                    })
                })
            }
        })
        put("warnings", JSONArray(snapshot.warnings))
    }.toString()

    fun decode(text: String?): StudySnapshot? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(text)
            val fetchedAt = Instant.parse(root.getString("fetchedAt"))
            val courses = root.optJSONArray("courses").orEmpty().map { item ->
                Course(
                    id = item.getString("id"),
                    classId = item.getString("classId"),
                    cpi = item.optString("cpi"),
                    name = item.getString("name"),
                    teacher = item.optString("teacher"),
                )
            }
            val tasks = root.optJSONArray("tasks").orEmpty().mapNotNull { item ->
                val kind = runCatching { TaskKind.valueOf(item.getString("kind")) }.getOrNull()
                    ?: return@mapNotNull null
                val state = runCatching { CompletionState.valueOf(item.getString("state")) }
                    .getOrDefault(CompletionState.UNKNOWN)
                StudyTask(
                    id = item.getString("id"),
                    courseId = item.getString("courseId"),
                    courseName = item.optString("courseName"),
                    title = item.getString("title"),
                    kind = kind,
                    state = state,
                    startTime = item.optString("startTime").toInstantOrNull(),
                    deadline = item.optString("deadline").toInstantOrNull(),
                    deadlineText = item.optString("deadlineText").takeIf { it.isNotBlank() },
                    url = item.optString("url").takeIf { it.isNotBlank() },
                    score = item.optString("score").takeIf { it.isNotBlank() },
                )
            }
            val taskPoints = root.optJSONArray("taskPoints").orEmpty().map { item ->
                CourseTaskPoints(
                    courseId = item.getString("courseId"),
                    courseName = item.optString("courseName"),
                    pending = item.optInt("pending"),
                    chapters = item.optJSONArray("chapters").orEmpty().map { chapter ->
                        TaskPointChapter(
                            number = chapter.optString("number"),
                            title = chapter.optString("title"),
                            pending = chapter.optInt("pending"),
                        )
                    },
                )
            }
            val warnings = root.optJSONArray("warnings")?.let { array ->
                (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
            }.orEmpty()
            StudySnapshot(courses, tasks, fetchedAt, taskPoints, warnings)
        }.getOrNull()
    }

    private fun JSONArray?.orEmpty(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun String?.toInstantOrNull(): Instant? =
        this?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
