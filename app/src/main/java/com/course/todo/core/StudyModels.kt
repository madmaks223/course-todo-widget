package com.course.todo.core

import java.time.Instant

enum class TaskKind { ASSIGNMENT, EXAM, GROUP_TASK }

enum class CompletionState { PENDING, SUBMITTED, EXPIRED, NOT_STARTED, UNKNOWN }

data class Course(
    val id: String,
    val classId: String,
    val cpi: String,
    val name: String,
    val teacher: String = "",
)

data class StudyTask(
    val id: String,
    val courseId: String,
    val courseName: String,
    val title: String,
    val kind: TaskKind,
    val state: CompletionState,
    val startTime: Instant? = null,
    val deadline: Instant? = null,
    val deadlineText: String? = null,
    val url: String? = null,
    val score: String? = null,
)

/** 章节任务点：pending 表示这门课当前待完成的章节任务点数量。 */
data class TaskPointChapter(
    val number: String,
    val title: String,
    val pending: Int,
)

data class CourseTaskPoints(
    val courseId: String,
    val courseName: String,
    val pending: Int,
    val chapters: List<TaskPointChapter> = emptyList(),
)

data class StudySnapshot(
    val courses: List<Course>,
    val tasks: List<StudyTask>,
    val fetchedAt: Instant,
    val taskPoints: List<CourseTaskPoints> = emptyList(),
    val warnings: List<String> = emptyList(),
)

class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
