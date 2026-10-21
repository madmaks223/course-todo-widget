package com.course.todo.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformParserTest {
    private val now = Instant.parse("2026-10-14T04:00:00Z")
    private val course = Course("266988735", "154557508", "585650519", "学科英语理工篇", "任春梅")

    @Test
    fun courseCardsComeFromTheWebCourseListWithTeacherAndCpi() {
        val html = """
            <ul class="clearfix">
              <li style="position:relative;" class="courseItem curFile">
                <input type="hidden" name="courseId" value="266988735" />
                <input type="hidden" name="classId" value="154557508" />
                <a class="courseName"
                   href='/visit/stucoursemiddle?courseid=266988735&clazzid=154557508&vc=1&cpi=585650519'
                   title="学科英语理工篇">学科英语理工篇</a>
                <p title="任春梅">任春梅</p>
                <p title="2026秋">2026秋</p>
              </li>
              <li style="position:relative;" class="courseItem">
                <input type="hidden" name="courseId" value="215205746" />
                <input type="hidden" name="classId" value="155553569" />
                <a class="courseName"
                   href='/visit/stucoursemiddle?courseid=215205746&clazzid=155553569&vc=1&cpi=572582297'
                   title="普通化学">普通化学</a>
                <p title="贾琼等">贾琼等</p>
              </li>
            </ul>
        """.trimIndent()

        assertEquals(
            listOf(
                Course("266988735", "154557508", "585650519", "学科英语理工篇", "任春梅"),
                Course("215205746", "155553569", "572582297", "普通化学", "贾琼等"),
            ),
            PlatformParser.parseCourseList(html),
        )
    }

    @Test
    fun emptyCourseListIsRecognizedButUnknownHtmlIsRejected() {
        assertEquals(emptyList<Course>(), PlatformParser.parseCourseList("<div>暂无课程</div>"))
        assertThrows(ParseException::class.java) {
            PlatformParser.parseCourseList("<div>完全认不出的页面</div>")
        }
    }

    @Test
    fun backclazzJsonFallbackUsesRealCourseNameInsteadOfFolderName() {
        val json = """{"result":1,"channelList":[
          {"cataid":"100000001","key":"folder","content":{"name":"文件夹"}},
          {"cataid":"100000002","key":"201","cpi":"301",
           "content":{"name":"2026秋季南岭","course":{"data":[{"id":"101","name":"高等数学"}]}}}
        ]}"""
        assertEquals(
            listOf(Course("101", "201", "301", "高等数学")),
            PlatformParser.parseCourseJson(json),
        )
    }

    @Test
    fun coursePageExposesWorkAndExamEntriesWithUnescapedUrls() {
        val html = """
            <li><a data="/work/getAllWork?classId=154557508&amp;courseId=266988735&amp;ut=s">作业</a></li>
            <li><a data="/exam-ans/exam/test?classId=154557508&amp;courseId=266988735&amp;ut=s">考试</a></li>
        """.trimIndent()
        assertEquals(
            "/work/getAllWork?classId=154557508&courseId=266988735&ut=s",
            PlatformParser.extractWorkUrl(html),
        )
        assertEquals(
            "/exam-ans/exam/test?classId=154557508&courseId=266988735&ut=s",
            PlatformParser.extractExamUrl(html),
        )
        assertNull(PlatformParser.extractWorkUrl("<div>没有入口</div>"))
    }

    @Test
    fun workListKeepsStartEndStatusAndScore() {
        val html = """
            <div class="ulDiv"><ul>
              <li><div class="titTxt" data="0">
                <p><a href="/mooc-ans/work/x?workId=56931458" title="Unit 2 Prep listening 3">Unit 2 Prep listening 3</a></p>
                <span class="pt5"><span class="fl">开始时间：</span>2026-10-15 13:42</span>
                <span class="pt5"><span class="fl">截止时间：</span>2026-10-23 23:30</span>
                <span class="pt5"><span class="fl">作业状态：</span><strong>待批阅</strong></span>
              </div><div class="titOper"></div><div class="shadow"></div></li>
              <li><div class="titTxt" data="0">
                <p><a href="/mooc-ans/work/x?workId=56097498" title="Unit 1 Prep Listening 1">Unit 1 Prep Listening 1</a></p>
                <span class="pt5"><span class="fl">开始时间：</span>2026-09-24 10:50</span>
                <span class="pt5"><span class="fl">截止时间：</span>2026-09-30 23:30</span>
                <span class="pt5"><span class="fl">作业状态：</span><strong>已完成</strong></span>
                <span>100分</span>
              </div><div class="titOper"></div><div class="shadow"></div></li>
              <li><div class="titTxt" data="0">
                <p><a href="/mooc-ans/work/x?workId=1" title="过期作业">过期作业</a></p>
                <span class="pt5"><span class="fl">截止时间：</span>2026-10-18 23:59</span>
                <span class="pt5"><span class="fl">作业状态：</span><strong>已过期</strong></span>
              </div><div class="titOper"></div><div class="shadow"></div></li>
            </ul></div>
        """.trimIndent()

        val tasks = PlatformParser.parseWorks(html, course, now)
        assertEquals(3, tasks.size)
        val first = tasks[0]
        assertEquals(TaskKind.ASSIGNMENT, first.kind)
        assertEquals(CompletionState.SUBMITTED, first.state)
        assertEquals(Instant.parse("2026-10-15T05:42:00Z"), first.startTime)
        assertEquals(Instant.parse("2026-10-23T15:30:00Z"), first.deadline)
        assertEquals("assignment:${course.id}:56931458", first.id)
        assertEquals("100分", tasks[1].score)
        assertEquals(CompletionState.EXPIRED, tasks[2].state)
    }

    @Test
    fun emptyWorkPageMeansNoAssignmentsNotAnError() {
        assertEquals(emptyList<StudyTask>(), PlatformParser.parseWorks("<div>暂无内容</div>", course, now))
    }

    @Test
    fun examListKeepsTimeRangeAndStatus() {
        val html = """
            <ul>
              <li class="lookLi"><div class="titTxt">
                <p><a href="javascript:void(0)" title="Word test Unit 1">Word test Unit 1</a></p>
                <span class="pt5"><span class="fl">考试时间：</span>2026-10-08 08:00 至 2026-10-08 08:10</span>
                <span class="pt5"><span class="fl">考试状态：</span><strong>已完成</strong></span>
              </div></li>
              <li class="lookLi"><div class="titTxt">
                <p><a href="javascript:void(0)" title="期中测验">期中测验</a></p>
                <span class="pt5"><span class="fl">考试时间：</span>2026-11-01 09:00 至 2026-11-01 11:00</span>
                <span class="pt5"><span class="fl">考试状态：</span><strong>待做</strong></span>
              </div></li>
            </ul>
        """.trimIndent()

        val exams = PlatformParser.parseExams(html, course, now)
        assertEquals(2, exams.size)
        assertEquals(TaskKind.EXAM, exams[0].kind)
        assertEquals(CompletionState.SUBMITTED, exams[0].state)
        assertEquals(Instant.parse("2026-10-08T00:00:00Z"), exams[0].startTime)
        assertEquals(Instant.parse("2026-10-08T00:10:00Z"), exams[0].deadline)
        assertEquals(CompletionState.PENDING, exams[1].state)
    }

    @Test
    fun taskPointsComeFromTimelineBadgesOnly() {
        val html = """
            <div class="timeline">
              <div class="units">
                <h2 class="levelfirst clearfix">第1章 绪论</h2>
                <div class="leveltwo"><h3 class="clearfix">
                  <a href="/mooc-ans/mycourse/studentstudy?chapterId=111&courseId=266988735&clazzid=154557508">
                    <span class="icon">
                      <input type="hidden" value="3" class="knowledgeJobCount" />
                      <em class="orange">2</em>
                    </span>
                    <span class="fl fontCon">
                      <span class="chapterNumber">1.1</span>
                      <span class="articlename" title="函数与极限">函数与极限</span>
                    </span>
                  </a>
                </h3></div>
                <div class="leveltwo"><h3 class="clearfix">
                  <a href="/mooc-ans/mycourse/studentstudy?chapterId=112&courseId=266988735&clazzid=154557508">
                    <span class="icon"><em class="openlock"></em></span>
                    <span class="fl fontCon">
                      <span class="chapterNumber">1.2</span>
                      <span class="articlename" title="导数与微分">导数与微分</span>
                    </span>
                  </a>
                </h3></div>
              </div>
            </div>
        """.trimIndent()

        val points = PlatformParser.parseTaskPoints(html, course)!!
        assertEquals(2, points.pending)
        assertEquals(listOf(TaskPointChapter("1.1", "函数与极限", 2)), points.chapters)
        assertNull(PlatformParser.parseTaskPoints("<div>目录异步加载</div>", course))
    }

    @Test
    fun loginPageIsReportedAsAuthenticationRequired() {
        assertThrows(AuthenticationRequired::class.java) {
            PlatformParser.parseWorks("<html><head><title>用户登录</title></head><body></body></html>", course, now)
        }
    }

    @Test
    fun duplicateWorkIdsAreRejectedInsteadOfSilentlyMerged() {
        val block = """
            <li><div class="titTxt"><p><a title="同名作业">同名作业</a></p>
            <span>开始时间：2026-10-15 13:42</span></div></li>
        """.trimIndent()
        val html = "<div class=\"ulDiv\">$block $block</div>"
        assertThrows(ParseException::class.java) {
            PlatformParser.parseWorks(html, course, now)
        }
        assertTrue(html.contains("同名作业"))
    }
}
