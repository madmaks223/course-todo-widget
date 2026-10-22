package com.course.todo.data

import android.content.Context
import com.course.todo.core.AuthenticationRequired
import com.course.todo.core.PlatformParser
import com.course.todo.core.Course
import com.course.todo.core.CourseTaskPoints
import com.course.todo.core.ParseException
import com.course.todo.core.RiskControlRequired
import com.course.todo.core.TaskMerger
import com.course.todo.core.StudySnapshot
import com.course.todo.core.StudyTask
import com.course.todo.core.TaskKind
import com.course.todo.core.UpstreamFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * 课程平台数据读取：课程 → 每门课的作业 / 考试 / 章节任务点。
 *
 * 采用「单门课失败不影响其它课程」的部分成功策略：失败课程写入 warnings，
 * 已有任务照常更新；只有全部课程都失败时才保留上一份文档（避免清空待办）。
 */
class PlatformRepository(context: Context) : StudyRepository {
    private val session = PlatformSession(context)
    private val http = PlatformHttpClient(session)

    suspend fun verifySession(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val response = http.get(HOME_URL)
            response.code == 200 && response.finalUrl.startsWith(HOME_URL, ignoreCase = true)
        }.getOrDefault(false)
    }

    /** 网页登录兜底后，把 WebView Cookie 同步到统一存储。 */
    fun adoptWebViewCookies() {
        session.copyFromWebViewCookieManager()
    }

    fun clearSession() {
        session.clear()
    }

    override suspend fun fetch(): StudySnapshot = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val courses = fetchCourses()
        if (courses.isEmpty()) throw ParseException("未获取到课程，已保留上次数据")
        val limit = Semaphore(MAX_PARALLEL_COURSES)
        val results = coroutineScope {
            courses.mapIndexed { index, course ->
                async {
                    if (index > 0) delay(FULL_SYNC_COURSE_DELAY_MILLIS)
                    limit.withPermit { syncCourse(course, now) }
                }
            }.awaitAll()
        }
        val tasks = results.flatMap { it.tasks }
        val taskPoints = results.mapNotNull { it.taskPoints }.filter { it.pending > 0 || it.chapters.isNotEmpty() }
        val warnings = results.flatMap { it.warnings }.distinct()
        if (tasks.isEmpty() && results.all { it.failed }) {
            throw ParseException("课程数据全部读取失败，已保留上次数据")
        }
        StudySnapshot(
            courses = courses,
            tasks = tasks,
            fetchedAt = now,
            taskPoints = taskPoints,
            warnings = warnings.take(MAX_WARNINGS),
        )
    }

    private fun fetchCourses(): List<Course> {
        return try {
            val html = http.get(COURSE_LIST_URL).body
            PlatformParser.parseCourseList(html)
        } catch (error: AuthenticationRequired) {
            throw error
        } catch (error: RiskControlRequired) {
            throw error
        } catch (_: Exception) {
            // 网页课程页改版时回退到 App 接口的 JSON。
            val json = http.get(COURSE_JSON_URL).body
            PlatformParser.parseCourseJson(json)
        }
    }

    /**
     * 轻量同步：只请求 3 个接口（课程 JSON / 全部作业 / 全部考试），
     * 状态与相对倒计时用新数据，绝对时间、分数、任务点从 [previous] 合并。
     * 用于常驻服务的近实时轮询，避免每分钟重复请求 13 门课的课程页。
     */
    suspend fun fetchLight(previous: StudySnapshot): StudySnapshot = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val parsedCourses = runCatching {
            PlatformParser.parseCourseJson(http.get(COURSE_JSON_URL).body)
        }.getOrElse { error ->
            if (error is AuthenticationRequired || error is RiskControlRequired) throw error
            previous.courses
        }
        val courses = parsedCourses.ifEmpty { previous.courses }
        val enrichedCourses = courses.map { course ->
            val previousCourse = previous.courses.firstOrNull { it.id == course.id }
            course.copy(teacher = course.teacher.ifBlank { previousCourse?.teacher.orEmpty() })
        }

        delay(LIGHT_SYNC_DELAY_MILLIS)
        val works = runCatching {
            PlatformParser.parseStuWorks(http.get(STU_WORK_URL).body, enrichedCourses, now)
        }.onFailure { error ->
            if (error is AuthenticationRequired || error is RiskControlRequired) throw error
        }
        delay(LIGHT_SYNC_DELAY_MILLIS)
        val exams = runCatching {
            PlatformParser.parsePhoneExams(http.get(EXAM_BULK_URL).body, enrichedCourses, now)
        }.onFailure { error ->
            if (error is AuthenticationRequired || error is RiskControlRequired) throw error
        }
        if (works.isFailure && exams.isFailure) {
            throw UpstreamFailure("轻量同步失败，保留上次数据")
        }
        val previousAssignments = previous.tasks.filter { it.kind == TaskKind.ASSIGNMENT }
        val previousExams = previous.tasks.filter { it.kind == TaskKind.EXAM }
        // 分组任务只在完整同步里刷新，轻量同步原样保留上一次的结果。
        val previousGroups = previous.tasks.filter { it.kind == TaskKind.GROUP_TASK }
        val mergedTasks = TaskMerger.merge(previousAssignments, works.getOrDefault(emptyList())) +
            TaskMerger.merge(previousExams, exams.getOrDefault(emptyList())) + previousGroups
        StudySnapshot(
            courses = enrichedCourses,
            tasks = mergedTasks,
            fetchedAt = now,
            taskPoints = previous.taskPoints,
            // 轻量同步不刷新任务点，保留完整同步里「任务点目录未加载」这类提示。
            warnings = previous.warnings.filter { it.contains("任务点") },
        )
    }

    private suspend fun syncCourse(course: Course, now: Instant): CourseSync {
        val warnings = mutableListOf<String>()
        val tasks = mutableListOf<StudyTask>()
        val page = try {
            http.get(courseHomeUrl(course))
        } catch (error: AuthenticationRequired) {
            throw error
        } catch (error: RiskControlRequired) {
            throw error
        } catch (error: Exception) {
            return CourseSync(emptyList(), null, listOf("${course.name} 课程页读取失败，已跳过"), failed = true)
        }

        val workUrl = PlatformParser.extractWorkUrl(page.body)
        if (workUrl != null) {
            val result = runCatching {
                PlatformParser.parseWorks(
                    http.get("https://mooc1.chaoxing.com/mooc-ans$workUrl").body,
                    course,
                    now,
                )
            }
            result.onSuccess(tasks::addAll)
                .onFailure { error ->
                    if (error is AuthenticationRequired || error is RiskControlRequired) throw error
                    warnings += "${course.name} 作业读取失败"
                }
        } else {
            warnings += "${course.name} 未找到作业入口"
        }

        val examUrl = PlatformParser.extractExamUrl(page.body)
        if (examUrl != null) {
            val result = runCatching {
                PlatformParser.parseExams(
                    http.get("https://mooc1.chaoxing.com$examUrl").body,
                    course,
                    now,
                )
            }
            result.onSuccess(tasks::addAll)
                .onFailure { error ->
                    if (error is AuthenticationRequired || error is RiskControlRequired) throw error
                    warnings += "${course.name} 考试读取失败"
                }
        } else {
            warnings += "${course.name} 未找到考试入口"
        }

        // 分组任务(PBL)：单独接口，失败只记 warning，不影响课程其它数据。
        delay(150)
        val pblResult = runCatching {
            PlatformParser.parsePblTasks(http.get(pblUrl(course)).body, course, now)
        }
        pblResult.onSuccess(tasks::addAll).onFailure { error ->
            if (error is AuthenticationRequired || error is RiskControlRequired) throw error
            warnings += "${course.name} 分组任务读取失败"
        }

        val taskPoints = runCatching {
            PlatformParser.parseTaskPoints(page.body, course)
        }.getOrNull()
        if (taskPoints == null) warnings += "${course.name} 任务点目录未加载"

        return CourseSync(tasks, taskPoints, warnings, failed = false)
    }

    private fun pblUrl(course: Course): String {
        val fid = session.cookieValue("fid").ifBlank { DEFAULT_FID }
        return "https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist" +
            "?fid=$fid&courseId=${course.id}&classId=${course.classId}&activeTypes=35"
    }

    private fun courseHomeUrl(course: Course): String =
        "https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${course.id}" +
            "&clazzid=${course.classId}&vc=1&cpi=${course.cpi}"

    private data class CourseSync(
        val tasks: List<StudyTask>,
        val taskPoints: CourseTaskPoints?,
        val warnings: List<String>,
        val failed: Boolean,
    )

    private companion object {
        const val HOME_URL = "https://i.chaoxing.com/base"
        const val COURSE_LIST_URL = "https://mooc1.chaoxing.com/visit/courses"
        const val COURSE_JSON_URL =
            "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&mcode=&rss=1"
        const val MAX_PARALLEL_COURSES = 2
        const val FULL_SYNC_COURSE_DELAY_MILLIS = 400L
        const val LIGHT_SYNC_DELAY_MILLIS = 150L
        const val STU_WORK_URL = "https://mooc1-api.chaoxing.com/work/stu-work"
        const val EXAM_BULK_URL = "https://mooc1-api.chaoxing.com/exam-ans/exam/phone/examcode"
        const val MAX_WARNINGS = 8
        const val DEFAULT_FID = "181"
    }
}
