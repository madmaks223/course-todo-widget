package com.course.todo.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoOrderingTest {
    private val now = Instant.parse("2026-10-21T08:00:00Z")
    private fun task(text: String, deadline: Instant?): MarkdownRow =
        MarkdownRow(text, checked = false, detail = "未提交", deadline = deadline)

    private fun documentRows(): List<MarkdownRow> = listOf(
        MarkdownRow("高等数学", isHeading = true),
        task("作业：A-晚", now.plusSeconds(2 * 86_400)),
        task("作业：A-早", now.plusSeconds(3_600)),
        MarkdownRow("大学英语", isHeading = true),
        task("作业：B-中", now.plusSeconds(86_400)),
        task("考试：B-无截止", null),
        task("分组任务：课堂展示", null),
        task("作业：课程大作业", now.plusSeconds(10 * 86_400)),
    )

    @Test
    fun courseModeGroupsByCourseAndKeepsLongTermAtBottom() {
        val rows = TodoOrdering.build(documentRows(), TaskSortOrder.COURSE)
        assertEquals(listOf("高等数学", "作业：A-早", "作业：A-晚", "大学英语", "作业：B-中", "考试：B-无截止",
            TodoOrdering.LONG_TERM_HEADING, "作业：课程大作业", "分组任务：课堂展示"),
            rows.map { item -> when (item) {
                is TodoOrdering.DisplayRow.Heading -> item.text
                is TodoOrdering.DisplayRow.Task -> item.row.text
            } })
        val longTerm = rows.dropWhile { it != TodoOrdering.DisplayRow.Heading(TodoOrdering.LONG_TERM_HEADING) }
            .filterIsInstance<TodoOrdering.DisplayRow.Task>()
        assertTrue(longTerm.all { it.row.detail.orEmpty().startsWith("《") })
    }

    @Test
    fun deadlineModeFlattensByDeadlineAndPutsUnknownDeadlineLast() {
        val rows = TodoOrdering.build(documentRows(), TaskSortOrder.DEADLINE)
        val tasks = rows.filterIsInstance<TodoOrdering.DisplayRow.Task>().map { it.row.text }
        assertEquals(listOf("作业：A-早", "作业：B-中", "作业：A-晚", "考试：B-无截止",
            "作业：课程大作业", "分组任务：课堂展示"), tasks)
        val firstTask = rows.filterIsInstance<TodoOrdering.DisplayRow.Task>().first()
        assertEquals("《高等数学》｜未提交", firstTask.row.detail)
    }

    @Test
    fun noHeadingsMeansNoRows() {
        assertEquals(emptyList<TodoOrdering.DisplayRow>(), TodoOrdering.build(emptyList(), TaskSortOrder.COURSE))
    }
}
