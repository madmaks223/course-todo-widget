package com.course.todo.core

import java.time.Instant

/** 任务排序方式：按课程分组，或按截止时间由近到远平铺。 */
enum class TaskSortOrder { COURSE, DEADLINE }

/**
 * 把 Markdown 行重新组织成 App / 桌面小组件要显示的顺序。
 *
 * 规则（用户要求）：
 * - 上方只放普通作业和考试，排序方式可选：
 *   - [TaskSortOrder.COURSE]：按课程分组，课程内按截止时间；
 *   - [TaskSortOrder.DEADLINE]：所有作业/考试按截止时间由短到长平铺，无截止时间的排最后；
 * - 分组任务(PBL) 与标题含“大作业”的长期任务统一放到最后，单独一段
 *   “分组任务 / 大作业”，不参与上面的排序竞争。
 */
object TodoOrdering {
    const val LONG_TERM_HEADING = "分组任务 / 大作业"

    sealed class DisplayRow {
        data class Heading(val text: String) : DisplayRow()
        data class Task(val row: MarkdownRow, val course: String) : DisplayRow()
    }

    private data class Entry(val course: String, val row: MarkdownRow, val index: Int)

    fun build(rows: List<MarkdownRow>, sortOrder: TaskSortOrder): List<DisplayRow> {
        val entries = mutableListOf<Entry>()
        val courseOrder = mutableListOf<String>()
        var currentCourse = ""
        var index = 0
        rows.forEach { row ->
            when {
                row.isHeading -> {
                    currentCourse = row.text
                    if (currentCourse !in courseOrder) courseOrder += currentCourse
                }
                row.checked != null -> entries += Entry(currentCourse, row, index++)
            }
        }
        val regular = entries.filterNot { isLongTerm(it.row) }
        val longTerm = entries.filter { isLongTerm(it.row) }
        val ordered = mutableListOf<DisplayRow>()
        when (sortOrder) {
            TaskSortOrder.COURSE -> {
                courseOrder.forEach { course ->
                    val items = regular.filter { it.course == course }
                    if (items.isEmpty()) return@forEach
                    ordered += DisplayRow.Heading(course)
                    items.sortedWith(taskComparator).forEach { entry ->
                        ordered += DisplayRow.Task(entry.row, course)
                    }
                }
            }
            TaskSortOrder.DEADLINE -> {
                regular.sortedWith(taskComparator).forEach { entry ->
                    ordered += DisplayRow.Task(withCoursePrefix(entry.row, entry.course), entry.course)
                }
            }
        }
        if (longTerm.isNotEmpty()) {
            ordered += DisplayRow.Heading(LONG_TERM_HEADING)
            longTerm.sortedWith(taskComparator).forEach { entry ->
                ordered += DisplayRow.Task(withCoursePrefix(entry.row, entry.course), entry.course)
            }
        }
        return ordered
    }

    private fun isLongTerm(row: MarkdownRow): Boolean =
        row.text.startsWith("分组任务") || row.text.contains("大作业")

    private fun withCoursePrefix(row: MarkdownRow, course: String): MarkdownRow {
        if (course.isBlank()) return row
        val detail = row.detail?.takeIf { it.isNotBlank() }
        return row.copy(detail = if (detail == null) "《$course》" else "《$course》｜$detail")
    }

    /** 截止时间由近到远，无截止时间排最后；时间相同保持文档原顺序（稳定排序）。 */
    private val taskComparator = Comparator<Entry> { left, right ->
        val leftDeadline = left.row.deadline ?: Instant.MAX
        val rightDeadline = right.row.deadline ?: Instant.MAX
        val byDeadline = leftDeadline.compareTo(rightDeadline)
        if (byDeadline != 0) byDeadline else left.index.compareTo(right.index)
    }
}
