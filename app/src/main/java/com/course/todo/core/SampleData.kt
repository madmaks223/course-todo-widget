package com.course.todo.core

import java.time.Duration
import java.time.Instant

object SampleData {
    fun snapshot(now: Instant = Instant.now()): StudySnapshot {
        val math = Course("demo-math", "demo-class-1", "demo", "高等数学", "演示教师")
        val english = Course("demo-english", "demo-class-2", "demo", "大学英语", "演示教师")
        return StudySnapshot(
            courses = listOf(math, english),
            tasks = listOf(
                // 时间已过 75%，触发「剩余不足 30%」红色高亮
                StudyTask(
                    "demo-work-1", math.id, math.name, "第三章习题", TaskKind.ASSIGNMENT,
                    CompletionState.PENDING,
                    startTime = now.minus(Duration.ofDays(3)),
                    deadline = now.plus(Duration.ofDays(1)),
                ),
                StudyTask(
                    "demo-work-2", math.id, math.name, "第二章习题", TaskKind.ASSIGNMENT,
                    CompletionState.SUBMITTED,
                    startTime = now.minus(Duration.ofDays(10)),
                    deadline = now.minus(Duration.ofDays(1)),
                    score = "95分",
                ),
                // 5 小时后截止，触发「24 小时内截止」红色高亮
                StudyTask(
                    "demo-exam-1", math.id, math.name, "阶段测验", TaskKind.EXAM,
                    CompletionState.NOT_STARTED,
                    startTime = now.minus(Duration.ofDays(1)),
                    deadline = now.plus(Duration.ofHours(5)),
                ),
                StudyTask(
                    "demo-work-3", english.id, english.name, "Unit 3 阅读", TaskKind.ASSIGNMENT,
                    CompletionState.PENDING, deadlineText = "剩余 3 天",
                ),
                StudyTask(
                    "demo-exam-2", english.id, english.name, "词汇测验", TaskKind.EXAM,
                    CompletionState.EXPIRED, deadline = now.minus(Duration.ofDays(2)),
                ),
                StudyTask(
                    "demo-work-4", english.id, english.name, "口语练习", TaskKind.ASSIGNMENT,
                    CompletionState.UNKNOWN,
                ),
            ),
            fetchedAt = now,
            taskPoints = listOf(
                CourseTaskPoints(
                    math.id, math.name, 8,
                    listOf(
                        TaskPointChapter("1.1", "函数与极限", 3),
                        TaskPointChapter("1.2", "导数与微分", 5),
                    ),
                ),
                CourseTaskPoints(english.id, english.name, 3),
            ),
            warnings = listOf("演示数据"),
        )
    }
}
