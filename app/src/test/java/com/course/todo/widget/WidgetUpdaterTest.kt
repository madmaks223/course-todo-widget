package com.course.todo.widget

import org.junit.Assert.assertEquals
import com.course.todo.core.TaskSortOrder
import org.junit.Test
import java.time.Instant

class WidgetUpdaterTest {
    @Test fun widgetRowsExcludeCompletedTasks() {
        val rows = visibleRows(
            """
            # 课程待办

            ## 高等数学

            - [ ] 作业：第三章习题 | 未提交
            - [x] 作业：第二章习题 | 已提交
            """.trimIndent(),
        )

        assertEquals(listOf("高等数学", "作业：第三章习题"), rows.map { it.text })
    }

    @Test fun widgetRowsRemoveCoursesWithOnlyCompletedTasks() {
        val rows = visibleRows(
            """
            # 课程待办

            ## 高等数学

            - [x] 作业：第二章习题 | 已提交
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), rows.map { it.text })
    }

    @Test fun widgetRowsRemoveExpiredTasksAndTheirEmptyCourseHeadings() {
        val now = Instant.parse("2026-10-20T04:00:00Z")
        val rows = visibleRows(
            """
            # 课程待办
            ## 数学
            - [ ] 作业：昨天截止 | 未提交 | 截止：2026-10-19 12:00（北京时间）
            - [ ] 作业：正好到点 | 未提交 | 截止：2026-10-20 12:00（北京时间）
            ## 英语
            - [ ] 作业：平台已结束 | 已结束
            - [ ] 作业：仍可提交 | 未提交 | 截止：2026-10-21 12:00（北京时间）
            - [x] 作业：已交 | 已提交
            """.trimIndent(), now,
        )

        assertEquals(listOf("英语", "作业：仍可提交"), rows.map { it.text })
    }

    @Test fun widgetRowsKeepUnknownAndRelativeDeadlines() {
        val rows = visibleRows(
            """
            # 课程待办
            ## 英语
            - [ ] 作业：相对时间 | 未提交 | 剩余 3 天
            - [ ] 作业：未提供时间 | 状态未知 | 截止时间未提供
            """.trimIndent(), Instant.parse("2026-10-20T04:00:00Z"),
        )

        assertEquals(listOf("英语", "作业：相对时间", "作业：未提供时间"), rows.map { it.text })
    }

    @Test fun widgetCanSortByDeadlineAcrossCourses() {
        val now = Instant.parse("2026-10-20T04:00:00Z")
        val rows = visibleRows(
            """
            # 课程待办
            ## 数学
            - [ ] 作业：三天后 | 未提交 | 截止：2026-10-23 12:00（北京时间） <!-- xxt-deadline:2026-10-23T04:00:00Z -->
            - [ ] 作业：一小时后 | 未提交 | 截止：2026-10-20 13:00（北京时间） <!-- xxt-deadline:2026-10-20T05:00:00Z -->
            ## 英语
            - [ ] 作业：明天 | 未提交 | 截止：2026-10-21 12:00（北京时间） <!-- xxt-deadline:2026-10-21T04:00:00Z -->
            """.trimIndent(), now, TaskSortOrder.DEADLINE,
        )
        assertEquals(listOf("作业：一小时后", "作业：明天", "作业：三天后"), rows.map { it.text })
    }
}
