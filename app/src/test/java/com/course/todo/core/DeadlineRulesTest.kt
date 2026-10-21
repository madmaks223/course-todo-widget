package com.course.todo.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadlineRulesTest {
    private val now = Instant.parse("2026-10-14T04:00:00Z")

    @Test fun deadlineAtNowOrEarlierIsExpiredAndCannotBeUrgent() {
        listOf(now, now.minusNanos(1), now.minusSeconds(86400)).forEach { deadline ->
            val row = MarkdownRow("task", checked = false, deadline = deadline, stateLabel = "未提交")
            assertTrue(DeadlineRules.isExpiredUnsubmitted(row, now))
            assertFalse(DeadlineRules.isUrgent24h(row, now))
        }
    }

    @Test fun urgentWindowIncludesExactly24HoursButExcludesLaterDeadlines() {
        listOf(now.plusNanos(1), now.plusSeconds(3600), now.plusSeconds(86400)).forEach { deadline ->
            val row = MarkdownRow("task", checked = false, deadline = deadline)
            assertTrue(DeadlineRules.isUrgent24h(row, now))
            assertFalse(DeadlineRules.isExpiredUnsubmitted(row, now))
        }
        val later = MarkdownRow("later", checked = false, deadline = now.plusSeconds(86400).plusNanos(1))
        assertFalse(DeadlineRules.isUrgent24h(later, now))
    }

    @Test fun explicitEndedStateIsHiddenEvenWithoutAnAbsoluteDeadline() {
        listOf("已截止", "已结束").forEach { state ->
            val row = MarkdownRow("task", checked = false, stateLabel = state)
            assertTrue(DeadlineRules.isExpiredUnsubmitted(row, now))
            assertFalse(DeadlineRules.isUrgent24h(row, now))
        }
    }

    @Test fun unknownAndDayOnlyDeadlineTextNeverCausesTasksToDisappearOrBecomeUrgent() {
        listOf(null, "截止时间未提供", "明天", "剩余 0 天", "剩余 1 天", "剩余 3 天")
            .forEach { text ->
                val row = MarkdownRow("task", checked = false, deadlineText = text)
                assertFalse(DeadlineRules.isExpiredUnsubmitted(row, now))
                assertFalse(DeadlineRules.isUrgent24h(row, now))
                assertEquals(listOf(row), DeadlineRules.filterVisible(listOf(row), now))
            }
    }

    @Test fun explicitShortCountdownWarnsWithoutBecomingAnAbsoluteOrExpiredDeadline() {
        listOf("剩余 2 小时", "剩余 24 小时", "剩余 3 分钟", "还有1440分钟").forEach { text ->
            val row = MarkdownDocument.parse("- [ ] 作业 | 未提交 | $text").single()
            assertTrue(DeadlineRules.isUrgent24h(row, now))
            assertNull(row.deadline)
            assertFalse(DeadlineRules.isExpiredUnsubmitted(row, now.plusSeconds(86400)))
            assertEquals(listOf(row), DeadlineRules.filterVisible(listOf(row), now))
        }
        listOf("剩余 25 小时", "剩余 1441 分钟", "剩余 0 分钟", "剩余 24 小时 1 分钟").forEach { text ->
            assertFalse(DeadlineRules.isUrgent24h(MarkdownRow("task", checked = false, deadlineText = text), now))
        }
        assertFalse(DeadlineRules.isUrgent24h(MarkdownRow("done", checked = true, deadlineText = "剩余 2 小时"), now))
        assertFalse(DeadlineRules.isUrgent24h(MarkdownRow("ended", checked = false,
            stateLabel = "已截止", deadlineText = "剩余 2 小时"), now))
    }

    @Test fun completedTasksAreAlwaysHiddenByDefaultRegardlessOfDeadline() {
        val completed = MarkdownRow("done", checked = true, deadline = now.plusSeconds(3600))
        val completedExpired = MarkdownRow("done earlier", checked = true, deadline = now.minusSeconds(1))
        val heading = MarkdownRow("course", isHeading = true)
        val pending = MarkdownRow("pending", checked = false)
        val expired = MarkdownRow("expired", checked = false, stateLabel = "已截止")
        val rows = listOf(heading, completed, completedExpired, pending, expired)

        assertEquals(listOf(heading, pending), DeadlineRules.filterVisible(rows, now))
        assertEquals(listOf(heading, completed, completedExpired, pending),
            DeadlineRules.filterVisible(rows, now, hideCompleted = false))
        assertFalse(DeadlineRules.isUrgent24h(completed, now))
        assertFalse(DeadlineRules.isExpiredUnsubmitted(completedExpired, now))
    }

    @Test fun invalidDatesAndDateOnlyTextAreNotInventedIntoDeadlines() {
        assertNull(DeadlineRules.parseAbsoluteInstant("截止：2026-13-40 25:61"))
        assertNull(DeadlineRules.parseAbsoluteInstant("截止：2026-10-14"))
        assertNull(DeadlineRules.parseAbsoluteInstant("剩余 2 小时"))
        assertEquals(Instant.parse("2026-10-14T05:00:00Z"),
            DeadlineRules.parseAbsoluteInstant("开始：2026-10-13 12:00，截止：2026-10-14 13:00"))
    }

    @Test fun legacyUnsubmittedAssignmentsWithoutDeadlineAreHiddenButUnknownRowsRemain() {
        val markdown = """
            ## 26秋-15机械-段云娜
            - [ ] 作业：第三章-Python程序设计基础 | 未提交 | 截止时间未提供
            - [ ] 作业：第二轮实验 | 未提交 | 截止时间未提供
            - [ ] 作业：仍开放 | 未提交 | 剩余 2 天
            - [ ] 考试：状态未知 | 状态未知 | 截止时间未提供
        """.trimIndent()
        val rows = MarkdownDocument.parse(markdown)
        val visible = DeadlineRules.filterVisible(rows, now)
        assertEquals(listOf("26秋-15机械-段云娜", "作业：仍开放", "考试：状态未知"), visible.map { it.text })
    }

    @Test fun remainingThirtyPercentOfWindowIsUrgent() {
        val start = now.minusSeconds(7 * 86_400L)
        val deadline = now.plusSeconds(3 * 86_400L) // 总时长 10 天，剩余 30%
        val boundary = MarkdownRow("task", checked = false, startTime = start, deadline = deadline)
        assertTrue(DeadlineRules.isTimeProgressUrgent(boundary, now))
        assertTrue(DeadlineRules.isUrgent(boundary, now))
        assertEquals(70, DeadlineRules.elapsedPercent(boundary, now))
        assertEquals(30, DeadlineRules.remainingPercent(boundary, now))
        assertEquals("时间已过 70%", DeadlineRules.urgencyLabel(boundary, now))

        val justOver = MarkdownRow(
            "task", checked = false, startTime = start, deadline = now.plusSeconds(3 * 86_400L + 1),
        )
        assertFalse(DeadlineRules.isTimeProgressUrgent(justOver, now))
        assertNull(DeadlineRules.urgencyLabel(justOver, now))
    }

    @Test fun withinTwentyFourHoursWinsTheUrgencyLabel() {
        val row = MarkdownRow(
            "task", checked = false,
            startTime = now.minusSeconds(10 * 86_400L),
            deadline = now.plusSeconds(86_400L),
        )
        assertTrue(DeadlineRules.isUrgent(now = now, row = row))
        assertEquals("24 小时内截止", DeadlineRules.urgencyLabel(row, now))
    }

    @Test fun progressUrgencyNeedsBothTimesAnOpenDeadlineAndUncheckedState() {
        assertFalse(
            DeadlineRules.isTimeProgressUrgent(
                MarkdownRow("no-start", checked = false, deadline = now.plusSeconds(60)), now,
            ),
        )
        assertFalse(
            DeadlineRules.isTimeProgressUrgent(
                MarkdownRow("no-deadline", checked = false, startTime = now.minusSeconds(600)), now,
            ),
        )
        assertFalse(
            DeadlineRules.isTimeProgressUrgent(
                MarkdownRow(
                    "done", checked = true,
                    startTime = now.minusSeconds(600), deadline = now.plusSeconds(60),
                ), now,
            ),
        )
        assertFalse(
            DeadlineRules.isTimeProgressUrgent(
                MarkdownRow(
                    "reversed", checked = false,
                    startTime = now.plusSeconds(600), deadline = now.plusSeconds(60),
                ), now,
            ),
        )
        assertFalse(
            DeadlineRules.isTimeProgressUrgent(
                MarkdownRow(
                    "expired", checked = false,
                    startTime = now.minusSeconds(600), deadline = now.minusSeconds(1),
                ), now,
            ),
        )
    }

    @Test fun progressUrgentTasksStayVisibleInListAndWidget() {
        val row = MarkdownRow(
            "task", checked = false,
            startTime = now.minusSeconds(8 * 86_400L),
            deadline = now.plusSeconds(86_400L),
        )
        assertTrue(DeadlineRules.isUrgent(row, now))
        assertEquals(listOf(row), DeadlineRules.filterVisible(listOf(row), now))
    }
}
