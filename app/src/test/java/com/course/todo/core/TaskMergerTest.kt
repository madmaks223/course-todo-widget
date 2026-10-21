package com.course.todo.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskMergerTest {
    private val now = Instant.parse("2026-10-21T08:00:00Z")
    private val courseId = "215205746"

    private fun previous() = StudyTask(
        id = "assignment:$courseId:1",
        courseId = courseId,
        courseName = "普通化学",
        title = "第一章 作业",
        kind = TaskKind.ASSIGNMENT,
        state = CompletionState.PENDING,
        startTime = now.minusSeconds(86_400),
        deadline = now.plusSeconds(3 * 86_400),
        score = null,
    )

    private fun bulk(state: CompletionState = CompletionState.SUBMITTED, title: String = "第一章 作业") =
        StudyTask(
            id = "assignment:$courseId:1",
            courseId = courseId,
            courseName = "普通化学",
            title = title,
            kind = TaskKind.ASSIGNMENT,
            state = state,
            deadlineText = "剩余72小时0分钟",
        )

    @Test
    fun lightSyncUpdatesStateButKeepsAbsoluteTimes() {
        val merged = TaskMerger.merge(listOf(previous()), listOf(bulk()))
        val task = merged.single()
        assertEquals(CompletionState.SUBMITTED, task.state)
        assertEquals(previous().startTime, task.startTime)
        assertEquals(previous().deadline, task.deadline)
        assertEquals("剩余72小时0分钟", task.deadlineText)
    }

    @Test
    fun lightSyncAppendsNewTasksAndKeepsMissingOnes() {
        val newTask = bulk(title = "第二章 作业")
        val merged = TaskMerger.merge(listOf(previous()), listOf(bulk(), newTask))
        assertEquals(2, merged.size)
        assertEquals("第二章 作业", merged[1].title)
        assertEquals(CompletionState.SUBMITTED, merged[1].state)
    }

    @Test
    fun lightSyncFailureKeepsPreviousTasks() {
        assertEquals(listOf(previous()), TaskMerger.merge(listOf(previous()), emptyList()))
    }
}
