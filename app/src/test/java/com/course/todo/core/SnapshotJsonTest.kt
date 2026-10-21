package com.course.todo.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotJsonTest {
    @Test
    fun snapshotRoundTripsWithTimesScoresAndTaskPoints() {
        val now = Instant.parse("2026-10-21T08:00:00Z")
        val course = Course("1", "2", "3", "普通化学", "贾琼等")
        val task = StudyTask(
            id = "assignment:1:9",
            courseId = "1",
            courseName = "普通化学",
            title = "第一章 作业",
            kind = TaskKind.ASSIGNMENT,
            state = CompletionState.PENDING,
            startTime = now.minusSeconds(3600),
            deadline = now.plusSeconds(86_400),
            score = "100分",
        )
        val points = CourseTaskPoints(
            "1", "普通化学", 12,
            listOf(TaskPointChapter("1.1", "第一节", 12)),
        )
        val snapshot = StudySnapshot(listOf(course), listOf(task), now, listOf(points), listOf("测试警告"))

        val decoded = SnapshotJson.decode(SnapshotJson.encode(snapshot))!!
        assertEquals(snapshot, decoded)
    }

    @Test
    fun brokenJsonDecodesToNull() {
        assertNull(SnapshotJson.decode("not json"))
        assertNull(SnapshotJson.decode(null))
        assertNull(SnapshotJson.decode(""))
    }
}
