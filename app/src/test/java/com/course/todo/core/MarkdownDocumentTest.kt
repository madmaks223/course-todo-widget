package com.course.todo.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentTest {
    private val now = Instant.parse("2026-10-14T04:00:00Z")

    @Test
    fun documentCheckboxesReflectOnlySubmittedStateAndPreserveTimePrecision() {
        val snapshot = SampleData.snapshot(now)
        val markdown = MarkdownDocument.render(snapshot)
        val rows = MarkdownDocument.parse(markdown)
        val tasks = rows.filter { it.checked != null }
        assertEquals(snapshot.tasks.size, tasks.size)
        assertEquals(1, tasks.count { it.checked == true })
        assertEquals("作业：第二章习题", tasks.single { it.checked == true }.text)
        assertTrue(tasks.single { it.text.contains("Unit 3") }.detail!!.contains("剩余 3 天"))
        assertTrue(tasks.single { it.text.contains("口语练习") }.detail!!.contains("状态未知"))
        assertTrue(markdown.contains("2026-10-14 12:00"))
        assertTrue(markdown.contains("<!-- xxt-task:"))
        assertTrue(rows.first().text.contains("演示"))
        assertFalse(rows.any { it.text.contains("xxt-task:") || it.text == "演示数据" })
    }

    @Test
    fun titlesCannotInjectTasksHeadingsMetadataOrComments() {
        val course = Course("1", "2", "3", "高等数学\n## 伪造课程")
        val title = "真实 *习题* | 特殊\\字符 [x]\n- [x] 伪造完成 <!-- injected --> &lt; \\"
        val snapshot = StudySnapshot(
            listOf(course),
            listOf(
                StudyTask(
                    "id-->\n- [x] hidden", course.id, course.name, title,
                    TaskKind.ASSIGNMENT, CompletionState.PENDING, deadlineText = "剩余 3 天 | 待确认",
                ),
            ),
            now,
        )
        val markdown = MarkdownDocument.render(snapshot)
        val tasks = MarkdownDocument.parse(markdown).filter { it.checked != null }
        assertEquals(1, tasks.size)
        assertEquals(false, tasks.single().checked)
        assertEquals("作业：${title.replace("\n", " ")}", tasks.single().text)
        assertTrue(tasks.single().detail!!.contains("剩余 3 天 | 待确认"))
        assertFalse(markdown.contains("<!-- injected -->"))
    }

    @Test
    fun incompleteTasksSortBeforeCompletedAndThenByDeadline() {
        val course = Course("1", "2", "3", "数学")
        val snapshot = StudySnapshot(
            listOf(course),
            listOf(
                StudyTask("done", "1", "数学", "已完成", TaskKind.ASSIGNMENT, CompletionState.SUBMITTED, deadline = now),
                StudyTask("later", "1", "数学", "较晚", TaskKind.ASSIGNMENT, CompletionState.PENDING, deadline = now.plusSeconds(7200)),
                StudyTask("soon", "1", "数学", "较早", TaskKind.EXAM, CompletionState.NOT_STARTED, deadline = now.plusSeconds(3600)),
            ),
            now,
        )
        assertEquals(
            listOf("考试：较早", "作业：较晚", "作业：已完成"),
            MarkdownDocument.parse(MarkdownDocument.render(snapshot)).filter { it.checked != null }.map { it.text },
        )
    }

    @Test
    fun emptySnapshotAndStandardMarkdownAreReadable() {
        assertTrue(MarkdownDocument.render(StudySnapshot(emptyList(), emptyList(), now)).contains("暂无作业或考试"))
        val rows = MarkdownDocument.parse(
            """
            # 我的待办
            - [ ] 第一项
            * [X] 已完成 | 已提交
            ```markdown
            - [x] 示例内容
            ```
            """.trimIndent(),
        )
        assertEquals(3, rows.size)
        assertTrue(rows.first().isHeading)
        assertEquals(listOf(false, true), rows.drop(1).map { it.checked })
    }

    @Test
    fun exactDeadlinesRoundTripWithSubMinutePrecision() {
        val deadline = now.plusSeconds(59).plusNanos(123456789)
        val course = Course("1", "2", "3", "数学")
        val task = StudyTask(
            "precise", "1", "数学", "限时测验", TaskKind.EXAM,
            CompletionState.PENDING, deadline = deadline,
        )
        val markdown = MarkdownDocument.render(StudySnapshot(listOf(course), listOf(task), now))
        val row = MarkdownDocument.parse(markdown).single { it.checked != null }

        assertTrue(markdown.contains("<!-- xxt-deadline:$deadline -->"))
        assertEquals(deadline, row.deadline)
        assertEquals("未提交", row.stateLabel)
        assertEquals("截止：2026-10-14 12:00（北京时间）", row.deadlineText)
        assertFalse(row.detail!!.contains("xxt-deadline"))
    }

    @Test
    fun startTimeRoundTripsAndPowersTheThirtyPercentRule() {
        val start = now.minusSeconds(8 * 24 * 3600) // 已过 8 天
        val deadline = now.plusSeconds(2 * 24 * 3600) // 还剩 2 天，总时长 10 天 → 剩 20%
        val course = Course("1", "2", "3", "数学")
        val task = StudyTask(
            "progress", "1", "数学", "实验报告", TaskKind.ASSIGNMENT,
            CompletionState.PENDING, startTime = start, deadline = deadline,
        )
        val markdown = MarkdownDocument.render(StudySnapshot(listOf(course), listOf(task), now))
        val row = MarkdownDocument.parse(markdown).single { it.checked != null }

        assertEquals(start, row.startTime)
        assertEquals(deadline, row.deadline)
        assertTrue(DeadlineRules.isUrgent(row, now))
        assertEquals(80, DeadlineRules.elapsedPercent(row, now))
        assertEquals(20, DeadlineRules.remainingPercent(row, now))
        assertEquals("时间已过 80%", DeadlineRules.urgencyLabel(row, now))
    }

    @Test
    fun legacyRowsWithoutStartMetadataKeepWorking() {
        val row = MarkdownDocument.parse("- [ ] 作业：旧文档 | 未提交 | 截止：2026-10-21 23:59").single()
        assertNull(row.startTime)
        assertFalse(DeadlineRules.isTimeProgressUrgent(row, now))
        assertEquals(Instant.parse("2026-10-21T15:59:00Z"), row.deadline)
    }

    @Test
    fun taskPointsAreRenderedLastAndDoNotBecomeWidgetTasks() {
        val snapshot = SampleData.snapshot(now)
        val markdown = MarkdownDocument.render(snapshot)
        val rows = MarkdownDocument.parse(markdown)

        assertTrue(markdown.indexOf("## 待完成任务点") > markdown.lastIndexOf("## 大学英语"))
        val points = rows.filter { it.isTaskPoint }
        assertEquals(2, points.size)
        assertEquals("高等数学：8 个待完成", points.first { it.text.contains("高等数学") }.text)
        assertEquals(8, points.first { it.text.contains("高等数学") }.taskPointCount)
        // 任务点没有复选框，不会进入小组件待办列表
        assertTrue(points.all { it.checked == null })
        assertEquals(snapshot.tasks.size, rows.count { it.checked != null })
    }

    @Test
    fun legacyMarkdownDeadlineTextAndExpiredLabelsAreParsed() {
        val rows = MarkdownDocument.parse(
            """
            - [ ] 作业：未提交 | 未提交 | 截止：2026-10-14 13:02:03（北京时间）
            - [ ] 考试：已结束 | 已结束 | 截止：2026-10-13 13:02
            - [ ] 作业：国际时间 | 状态未知 | 截止：2026-10-14T07:00:00Z
            """.trimIndent(),
        )

        assertEquals(Instant.parse("2026-10-14T05:02:03Z"), rows[0].deadline)
        assertEquals("已结束", rows[1].stateLabel)
        assertEquals(Instant.parse("2026-10-13T05:02:00Z"), rows[1].deadline)
        assertEquals(Instant.parse("2026-10-14T07:00:00Z"), rows[2].deadline)
    }

    @Test
    fun relativeAndVagueDeadlinesRemainTextWithoutInventingAnInstant() {
        val rows = MarkdownDocument.parse(
            """
            同步时间：2026-10-14 12:00（北京时间）
            - [ ] 作业：天 | 未提交 | 剩余 1 天
            - [ ] 作业：小时 | 未提交 | 剩余 2 小时
            - [ ] 作业：分钟 | 未提交 | 剩余 3 分钟
            - [ ] 作业：未知 | 状态未知 | 截止时间未提供
            - [ ] 作业：标题中的 2026-10-13 10:00
            """.trimIndent(),
        )

        assertEquals(5, rows.size)
        assertTrue(rows.all { it.deadline == null })
        assertEquals("剩余 1 天", rows[0].deadlineText)
        assertEquals("剩余 2 小时", rows[1].deadlineText)
        assertEquals("剩余 3 分钟", rows[2].deadlineText)
        assertNull(rows[4].deadlineText)
    }

    @Test
    fun invalidDeadlineMetadataFallsBackToLegacyAbsoluteText() {
        val row = MarkdownDocument.parse(
            "- [ ] 习题 | 未提交 | 截止：2026-10-14 13:00 <!-- xxt-deadline:invalid -->",
        ).single()
        assertEquals(Instant.parse("2026-10-14T05:00:00Z"), row.deadline)
    }

    @Test
    fun taskTitleWithPipeRoundTrips() {
        val now = java.time.Instant.parse("2026-10-21T08:00:00Z")
        val snapshot = StudySnapshot(
            courses = listOf(Course("1", "2", "3", "高等数学")),
            tasks = listOf(
                StudyTask(
                    id = "assignment:1:9",
                    courseId = "1",
                    courseName = "高等数学",
                    title = "A | B 作业",
                    kind = TaskKind.ASSIGNMENT,
                    state = CompletionState.PENDING,
                    deadline = now.plusSeconds(86_400),
                ),
            ),
            fetchedAt = now,
            taskPoints = emptyList(),
            warnings = emptyList(),
        )
        val rows = MarkdownDocument.parse(MarkdownDocument.render(snapshot))
        assertEquals("作业：A | B 作业", rows.first { it.checked != null }.text)
    }
}
