package com.course.todo.data

import com.course.todo.core.TaskKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTasksJsonTest {
    @Test
    fun manualDataRoundTrips() {
        val original = ManualData(
            courses = listOf(ManualCourse("m-course-1", "我的课程", 1000L)),
            tasks = listOf(
                ManualTaskItem("m-task-1", "m-course-1", "手动作业", TaskKind.ASSIGNMENT, 1700000000000L, 1001L),
                ManualTaskItem("m-task-2", "m-course-1", "手动考试", TaskKind.EXAM, null, 1002L),
            ),
        )
        val decoded = ManualTasksJson.decode(ManualTasksJson.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun brokenOrEmptyJsonFallsBackToEmpty() {
        assertEquals(ManualData(emptyList(), emptyList()), ManualTasksJson.decode(null))
        assertEquals(ManualData(emptyList(), emptyList()), ManualTasksJson.decode("not json"))
        assertTrue(ManualTasksJson.decode("").courses.isEmpty())
    }
}
