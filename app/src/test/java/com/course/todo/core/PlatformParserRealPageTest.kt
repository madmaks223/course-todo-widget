package com.course.todo.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实账号页面（已保存为测试资源）验证解析器，防止课程平台改版后静默解析失败。
 *
 * 这些 HTML 文件是从本人账号页面导出的快照，内容固定，所以断言可以精确。
 */
class PlatformParserRealPageTest {
    private val now = Instant.parse("2026-10-21T08:00:00Z")
    private val english = Course("266988735", "154557508", "585650519", "学科英语理工篇", "任春梅")
    private val packaging = Course("211654952", "155772326", "572582297", "绿色包装", "于庆宇")

    private val fixturesAvailable: Boolean
        get() = javaClass.classLoader?.getResource("fixtures/courses.html") != null

    /** 仓库不提交真实页面快照；没有 fixtures 时跳过这些回归测试。 */
    private fun requireFixtures() {
        assumeTrue("本地真实页面快照未提供（见 README「本地回归测试」）", fixturesAvailable)
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun realWebCourseListParsesEveryCourseWithTeacherAndCpi() {
        requireFixtures()
        val courses = PlatformParser.parseCourseList(fixture("courses.html"))
        assertEquals(13, courses.size)
        assertTrue(courses.any { it == english })
        assertTrue(courses.any { it == packaging })
        assertTrue(courses.all { it.cpi.isNotBlank() })
        assertTrue(courses.count { it.teacher.isNotBlank() } >= 10)
    }

    @Test
    fun realCoursePageExposesWorkExamAndTaskPointEntries() {
        requireFixtures()
        val page = fixture("course_page.html")
        assertNotNull(PlatformParser.extractWorkUrl(page))
        assertNotNull(PlatformParser.extractExamUrl(page))
        assertTrue(PlatformParser.extractWorkUrl(page)!!.contains("courseId=266988735"))
    }

    @Test
    fun realWorkPageKeepsAbsoluteStartDeadlineStatusAndScore() {
        requireFixtures()
        val tasks = PlatformParser.parseWorks(fixture("works.html"), english, now)
        assertEquals(10, tasks.size)
        assertTrue(tasks.all { it.kind == TaskKind.ASSIGNMENT })
        assertTrue(tasks.all { it.courseId == english.id })
        assertTrue(tasks.all { it.state == CompletionState.SUBMITTED })
        assertEquals(10, tasks.count { it.startTime != null })
        assertEquals(9, tasks.count { it.deadline != null })
        assertTrue(tasks.any { it.score == "100分" })
        val unit2 = tasks.first { it.title.startsWith("Unit 2 Prep listening 3") }
        assertEquals("2026-10-15T05:42:00Z", unit2.startTime.toString())
        assertEquals("2026-10-23T15:30:00Z", unit2.deadline.toString())
    }

    @Test
    fun realEmptyWorkPageMeansNoAssignments() {
        requireFixtures()
        assertEquals(emptyList<StudyTask>(), PlatformParser.parseWorks(fixture("works_empty.html"), packaging, now))
    }

    @Test
    fun realExamPageParsesTimeRangeAndStatus() {
        requireFixtures()
        val exams = PlatformParser.parseExams(fixture("exams.html"), english, now)
        assertEquals(1, exams.size)
        val exam = exams.single()
        assertEquals("Word test Unit 1", exam.title)
        assertEquals(CompletionState.SUBMITTED, exam.state)
        assertEquals("2026-10-08T00:00:00Z", exam.startTime.toString())
        assertEquals("2026-10-08T00:10:00Z", exam.deadline.toString())
    }

    @Test
    fun realTaskPointTimelineGivesPendingCountsPerChapter() {
        requireFixtures()
        val points = PlatformParser.parseTaskPoints(fixture("course_page.html"), english)!!
        assertEquals(49, points.pending)
        assertEquals(16, points.chapters.size)
        assertEquals(points.pending, points.chapters.sumOf { it.pending })
        assertTrue(points.chapters.all { it.pending > 0 })
        assertTrue(points.chapters.any { it.number.startsWith("1.") })
    }

    @Test
    fun realTaskPointTimelineAlsoWorksForOtherCourse() {
        requireFixtures()
        val points = PlatformParser.parseTaskPoints(fixture("course_page_empty.html"), packaging)!!
        assertEquals(11, points.pending)
        assertEquals(10, points.chapters.size)
        assertNull(points.chapters.firstOrNull { it.pending <= 0 })
    }

    @Test
    fun realStuWorkListParsesStatusesForLightSync() {
        requireFixtures()
        val courses = listOf(
            english,
            packaging.copy(id = "215205746", name = "普通化学"),
            packaging.copy(id = "265803586", name = "工程制图及机械CAD"),
            packaging.copy(id = "266545913", name = "人工智能引论A-26年秋季学期"),
        )
        val tasks = PlatformParser.parseStuWorks(fixture("stu_work.html"), courses, now)
        assertEquals(21, tasks.size)
        assertTrue(tasks.all { it.kind == TaskKind.ASSIGNMENT })
        val chemistry = tasks.first { it.title.contains("化学反应基本规律") }
        assertEquals("普通化学", chemistry.courseName)
        assertEquals(CompletionState.PENDING, chemistry.state)
        assertEquals("剩余151小时6分钟", chemistry.deadlineText)
        assertEquals(4, tasks.count { it.state == CompletionState.PENDING })
        assertEquals(17, tasks.count { it.state == CompletionState.SUBMITTED })
    }

    @Test
    fun realPhoneExamListParsesStatusForLightSync() {
        requireFixtures()
        val exams = PlatformParser.parsePhoneExams(fixture("phone_exams.html"), listOf(english), now)
        assertEquals(1, exams.size)
        assertEquals("Word test Unit 1", exams.single().title)
        assertEquals(CompletionState.SUBMITTED, exams.single().state)
    }

    @Test
    fun realPblGroupTaskIsParsedWithStartTimeAndNoDeadline() {
        requireFixtures()
        val policy = Course("267828362", "156749675", "572582297", "形势与政策（26秋季选修）")
        val tasks = PlatformParser.parsePblTasks(fixture("pbl_task.json"), policy, now)
        assertEquals(1, tasks.size)
        val task = tasks.single()
        assertEquals(TaskKind.GROUP_TASK, task.kind)
        assertEquals("09“时事我来讲”课堂展示", task.title)
        assertEquals(policy.name, task.courseName)
        assertEquals(Instant.ofEpochMilli(1792203036000), task.startTime)
        assertNull(task.deadline)
        assertEquals("无截止时间", task.deadlineText)
        assertEquals(CompletionState.PENDING, task.state)
        assertEquals("group:267828362:3000174779170", task.id)
        // 其它课程没有分组任务时返回空列表（不是错误）
        assertEquals(emptyList<StudyTask>(), PlatformParser.parsePblTasks(fixture("pbl_empty.json"), english, now))
    }
}
