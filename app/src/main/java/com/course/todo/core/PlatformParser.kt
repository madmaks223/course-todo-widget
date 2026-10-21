package com.course.todo.core

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.jsoup.parser.Parser

/**
 * 课程平台页面解析器。
 *
 * 数据来源：本人账号的课程列表、课程首页里的作业/考试入口、目录里的待完成数，
 * 以及平台移动端的作业/考试列表与分组任务列表。
 */
object PlatformParser {
    private val chinaZone = ZoneId.of("Asia/Shanghai")
    private val timestamp = Regex(
        "(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?[ T]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?",
    )
    private val isoTimestamp = Regex(
        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})",
    )
    private val workUrlPattern = Regex("""data="(/work/getAllWork\?[^"]+)"""")
    private val examUrlPattern = Regex("""data="(/exam-ans/exam/test\?[^"]+)"""")
    private val chapterNumberPattern = Regex("""^\d+(\.\d+)+$""")

    private val workStates = mapOf(
        "未交" to CompletionState.PENDING,
        "未提交" to CompletionState.PENDING,
        "待做" to CompletionState.PENDING,
        "未做" to CompletionState.PENDING,
        "未完成" to CompletionState.PENDING,
        "进行中" to CompletionState.PENDING,
        "未开始" to CompletionState.NOT_STARTED,
        "尚未开始" to CompletionState.NOT_STARTED,
        "已交" to CompletionState.SUBMITTED,
        "已提交" to CompletionState.SUBMITTED,
        "已做" to CompletionState.SUBMITTED,
        "已完成" to CompletionState.SUBMITTED,
        "已批阅" to CompletionState.SUBMITTED,
        "已批改" to CompletionState.SUBMITTED,
        "待批阅" to CompletionState.SUBMITTED,
        "待批改" to CompletionState.SUBMITTED,
        "已过期" to CompletionState.EXPIRED,
        "已截止" to CompletionState.EXPIRED,
        "已结束" to CompletionState.EXPIRED,
    )

    // ------------------------------------------------------------------ 课程列表

    /** 解析 `visit/courses` 页面里的课程卡片。 */
    fun parseCourseList(html: String): List<Course> {
        val doc = Jsoup.parse(html, "https://mooc1.chaoxing.com/")
        val items = doc.select("li.courseItem")
        if (items.isEmpty()) {
            if (doc.text().contains("暂无课程") || doc.text().contains("还没有课程")) return emptyList()
            throw ParseException("未识别课程列表格式，已保留上次数据")
        }
        val courses = items.mapNotNull { item ->
            val id = item.selectFirst("input[name=courseId]")?.attr("value")?.trim().orEmpty()
            val classId = item.selectFirst("input[name=classId]")?.attr("value")?.trim().orEmpty()
            val anchor = item.selectFirst("a.courseName")
            val name = clean(anchor?.attr("title").orEmpty().ifBlank { anchor?.text().orEmpty() })
            if (id.isBlank() || classId.isBlank() || name.isBlank()) return@mapNotNull null
            val cpi = anchor?.attr("href")?.let { href ->
                Regex("[?&]cpi=(\\d+)").find(href)?.groupValues?.get(1)
            }.orEmpty()
            val teacher = clean(item.selectFirst("p[title]")?.attr("title").orEmpty())
            Course(id, classId, cpi, name, teacher)
        }.distinctBy { it.id to it.classId }
        if (courses.isEmpty()) throw ParseException("未识别课程列表格式，已保留上次数据")
        return courses
    }

    /** 兜底：解析 `mycourse/backclazzdata` JSON。 */
    fun parseCourseJson(json: String): List<Course> {
        try {
            val root = JSONObject(json)
            val message = listOf("msg", "message", "error").joinToString(" ") { root.optString(it) }
            if (message.contains("登录") || message.contains("login", ignoreCase = true)) {
                throw AuthenticationRequired()
            }
            if ((root.has("status") && root.opt("status") == false) ||
                (root.has("result") && root.optString("result") in listOf("0", "false"))
            ) {
                throw ParseException("课程请求失败，已保留上次数据")
            }
            val channels = root.optJSONArray("channelList")
                ?: root.optJSONObject("data")?.optJSONArray("channelList")
                ?: throw ParseException("未识别课程列表格式，已保留上次数据")
            val courses = mutableListOf<Course>()
            for (index in 0 until channels.length()) {
                val channel = channels.optJSONObject(index) ?: continue
                if (channel.optString("cataid") != "100000002") continue
                val content = channel.optJSONObject("content") ?: continue
                val course = content.optJSONObject("course")?.optJSONArray("data")?.optJSONObject(0)
                    ?: continue
                val id = course.optString("id").trim()
                val classId = channel.optString("key").trim()
                val name = clean(course.optString("name").ifBlank { content.optString("name") })
                if (id.isBlank() || classId.isBlank() || name.isBlank()) continue
                courses += Course(id, classId, channel.optString("cpi"), name)
            }
            return courses.distinctBy { it.id to it.classId }
        } catch (error: AuthenticationRequired) {
            throw error
        } catch (error: ParseException) {
            throw error
        } catch (error: Exception) {
            throw ParseException("课程列表解析失败，已保留上次数据", error)
        }
    }

    /** 兼容旧入口：HTML 课程页或 backclazzdata JSON 都可以。 */
    fun parseCourses(document: String): List<Course> =
        if (document.trimStart().startsWith("<")) parseCourseList(document) else parseCourseJson(document)

    // ------------------------------------------------------------------ 单门课程入口

    fun extractWorkUrl(html: String): String? =
        workUrlPattern.find(html)?.groupValues?.get(1)?.let(::unescapeUrl)

    fun extractExamUrl(html: String): String? =
        examUrlPattern.find(html)?.groupValues?.get(1)?.let(::unescapeUrl)

    private fun unescapeUrl(url: String): String =
        Parser.unescapeEntities(url, false).replace("&amp;", "&").trim()

    // ------------------------------------------------------------------ 作业

    /** 解析 `getAllWork` 页面：开始时间、截止时间、状态、分数。 */
    fun parseWorks(html: String, course: Course, now: Instant): List<StudyTask> {
        val doc = checkedDocument(html)
        val blocks = doc.select("div.titTxt")
        if (blocks.isEmpty()) {
            // 这门课没有作业时，页面显示「暂无内容」而不是报错。
            return emptyList()
        }
        val tasks = blocks.mapNotNull { block -> parseWorkBlock(block, course, now) }
        return uniqueTasks(tasks)
    }

    private fun parseWorkBlock(block: Element, course: Course, now: Instant): StudyTask? {
        val anchor = block.selectFirst("a[title]")
        val title = clean(
            anchor?.attr("title").orEmpty().ifBlank {
                block.selectFirst("p")?.text().orEmpty()
            },
        )
        if (title.isBlank()) return null
        val text = block.text()
        val start = parseAbsolute(
            Regex("开始时间：\\s*([0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2}[ T][0-9]{1,2}:[0-9]{2})")
                .find(text)?.groupValues?.get(1),
        )
        val deadline = parseAbsolute(
            Regex("截止时间：\\s*([0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2}[ T][0-9]{1,2}:[0-9]{2})")
                .find(text)?.groupValues?.get(1),
        )
        val statusLabel = Regex("作业状态：\\s*([\\u4e00-\\u9fa5]+)").find(text)?.groupValues?.get(1).orEmpty()
        // 分数显示在 titTxt 后面的 titOper 里，需要看整个 li 的文本。
        val containerText = (block.parents().firstOrNull { it.tagName() == "li" } ?: block.parent())?.text().orEmpty()
        val score = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*分").find(containerText)?.value?.trim()
        val workId = Regex("workId=(\\d+)").find(block.select("a[href]").joinToString(" ") { it.attr("href") })
            ?.groupValues?.get(1)
        val state = resolveState(workStates[statusLabel], deadline, now)
        return StudyTask(
            id = "assignment:${course.id}:${workId ?: digest("$title|$start|$deadline")}",
            courseId = course.id,
            courseName = course.name,
            title = title,
            kind = TaskKind.ASSIGNMENT,
            state = state,
            startTime = start,
            deadline = deadline,
            deadlineText = deadline?.let { null } ?: "截止时间未提供",
            score = score,
        )
    }

    // ------------------------------------------------------------------ 考试

    /** 解析 `exam-ans/exam/test` 页面：考试时间区间与状态。 */
    fun parseExams(html: String, course: Course, now: Instant): List<StudyTask> {
        val doc = checkedDocument(html)
        val items = doc.select("li.lookLi")
        if (items.isEmpty()) return emptyList()
        val tasks = items.mapNotNull { item ->
            val title = clean(
                item.selectFirst("a[title]")?.attr("title").orEmpty().ifBlank {
                    item.selectFirst("dt")?.text().orEmpty()
                },
            )
            if (title.isBlank()) return@mapNotNull null
            val text = item.text()
            val times = timestamp.findAll(text).toList()
            val start = times.firstOrNull()?.let { parseTimestamp(it) }
            val deadline = when {
                times.size >= 2 -> parseTimestamp(times.last())
                times.size == 1 -> parseTimestamp(times.first())
                else -> null
            }
            val statusLabel = Regex("考试状态：\\s*([\\u4e00-\\u9fa5]+)").find(text)?.groupValues?.get(1).orEmpty()
            val state = resolveState(workStates[statusLabel], deadline, now)
            StudyTask(
                id = "exam:${course.id}:${digest("$title|$start|$deadline")}",
                courseId = course.id,
                courseName = course.name,
                title = title,
                kind = TaskKind.EXAM,
                state = state,
                startTime = start,
                deadline = deadline,
                deadlineText = deadline?.let { null } ?: "考试时间未提供",
            )
        }
        return uniqueTasks(tasks)
    }

    // ------------------------------------------------------------------ 章节任务点

    /**
     * 从课程首页目录提取每章的待完成任务点数（橙色数字）。
     * 目录由新版接口异步加载时返回 null，调用方应只提示、不阻塞整门课。
     */
    fun parseTaskPoints(html: String, course: Course): CourseTaskPoints? {
        // 课程目录的 div 嵌套在部分页面里不闭合，用 Jsoup 选择器会漏行/重复；
        // 这里按原始 HTML 扫描橙色数字，再用最近的章节号与标题配对，实测最稳。
        val timelineStart = html.indexOf("<div class=\"timeline\"")
        val scope = if (timelineStart >= 0) html.substring(timelineStart) else html
        if (!scope.contains("chapterId=")) return null
        val badges = Regex("""<em class="orange">\s*(\d+)\s*</em>""").findAll(scope).toList()
        if (badges.isEmpty()) return CourseTaskPoints(course.id, course.name, 0)
        val chapterNumbers = Regex("""<span class="chapterNumber">([^<]*)</span>""").findAll(scope).toList()
        val titles = Regex("""<span class="articlename"[^>]*title="([^"]*)"[^>]*>""").findAll(scope).toList()
        val chapters = badges.mapNotNull { badge ->
            val position = badge.range.first
            val nearestNumber = chapterNumbers.minByOrNull {
                kotlin.math.abs(it.range.first - position)
            } ?: return@mapNotNull null
            val nearestTitle = titles.minByOrNull {
                kotlin.math.abs(it.range.first - nearestNumber.range.first)
            }
            val pending = badge.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            if (pending <= 0) return@mapNotNull null
            TaskPointChapter(
                number = clean(nearestNumber.groupValues[1]),
                title = clean(nearestTitle?.groupValues?.get(1).orEmpty()),
                pending = pending,
            )
        }.distinctBy { it.number }
        return CourseTaskPoints(course.id, course.name, chapters.sumOf { it.pending }, chapters)
    }

    // ------------------------------------------------------------------ 分组任务(PBL)

    /**
     * 分组任务(PBL)列表：`mobilelearn.chaoxing.com/v2/apis/active/student/activelist?...&activeTypes=35`
     *
     * 返回字段：nameOne（名称）、startTime/endTime（毫秒时间戳，endTime 可能为空字符串）、
     * status（1 进行中 / 2 已结束）、userStatus / attendNum（参与情况）。
     * 状态映射是保守估计：只有明确已参与（userStatus>=2 或 attendNum>0）才勾选。
     */
    fun parsePblTasks(json: String, course: Course, now: Instant): List<StudyTask> {
        val root = runCatching { JSONObject(json) }.getOrElse {
            throw ParseException("分组任务返回格式异常")
        }
        val message = root.optString("errorMsg").ifBlank { root.optString("msg") }
        if (message.contains("登录") || message.contains("login", ignoreCase = true)) {
            throw AuthenticationRequired()
        }
        if (root.optInt("result", 0) != 1) return emptyList()
        val list = root.optJSONObject("data")?.optJSONArray("activeList") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val title = clean(item.optString("nameOne").ifBlank { item.optString("name") })
            if (title.isBlank()) return@mapNotNull null
            val start = item.optLong("startTime", 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
            val end = item.optString("endTime").toLongOrNull()?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
            val status = item.optInt("status", 0)
            val userStatus = item.optInt("userStatus", 0)
            val attendNum = item.optInt("attendNum", 0)
            val rawState = when {
                userStatus >= 2 || attendNum > 0 -> CompletionState.SUBMITTED
                status == 2 -> CompletionState.EXPIRED
                else -> CompletionState.PENDING
            }
            StudyTask(
                id = "group:${course.id}:${item.optLong("id", 0L)}",
                courseId = course.id,
                courseName = course.name,
                title = title,
                kind = TaskKind.GROUP_TASK,
                state = resolveState(rawState, end, now),
                startTime = start,
                deadline = end,
                deadlineText = if (end == null) "无截止时间" else null,
            )
        }
    }

    // ------------------------------------------------------------------ 轻量同步（移动端全量列表）

    /**
     * 移动端「作业」页：一次请求返回所有课程的作业与状态，但没有绝对截止时间。
     * 轻量同步用它更新状态，绝对时间从上次完整同步的快照合并。
     */
    fun parseStuWorks(html: String, courses: List<Course>, now: Instant): List<StudyTask> {
        val doc = checkedDocument(html)
        val items = doc.select("li").filter { it.attr("onclick").contains("goTask") }
        if (items.isEmpty()) {
            if (doc.text().contains("暂无")) return emptyList()
            throw ParseException("未识别全部作业列表格式")
        }
        return items.mapNotNull { item ->
            val data = item.attr("data")
            val courseId = Regex("[?&]courseId=(\\d+)").find(data)?.groupValues?.get(1)
            val referenceId = Regex("[?&]taskrefId=(\\d+)").find(data)?.groupValues?.get(1)
            val block = item.selectFirst("div[role=option]") ?: return@mapNotNull null
            val title = clean(block.selectFirst("p")?.text().orEmpty())
            if (title.isBlank()) return@mapNotNull null
            val spanTexts = block.select("span").map { clean(it.text()) }
            val statusText = clean(block.selectFirst("span.status")?.text().orEmpty())
                .ifBlank { spanTexts.firstOrNull { it in workStates }.orEmpty() }
            val courseLabel = spanTexts.firstOrNull { it.startsWith("《") && it.endsWith("》") }
            val remaining = clean(block.selectFirst("span.fr")?.text().orEmpty()).ifBlank { null }
            val course = courses.firstOrNull { it.id == courseId }
            val courseName = course?.name
                ?: clean(courseLabel.orEmpty().removePrefix("《").removeSuffix("》"))
            StudyTask(
                id = "assignment:${course?.id ?: courseId.orEmpty()}:${referenceId ?: digest("$title|$remaining")}",
                courseId = course?.id ?: courseId.orEmpty(),
                courseName = courseName,
                title = title,
                kind = TaskKind.ASSIGNMENT,
                state = resolveState(workStates[statusText], null, now),
                deadlineText = remaining,
            )
        }
    }

    /** 移动端「考试」页：一次请求返回所有考试与状态；未开始的考试可能带 dd 时间文本。 */
    fun parsePhoneExams(html: String, courses: List<Course>, now: Instant): List<StudyTask> {
        val doc = checkedDocument(html)
        val items = doc.select("li").filter { it.attr("onclick").contains("goTask") }
        if (items.isEmpty()) {
            if (doc.text().contains("暂无")) return emptyList()
            throw ParseException("未识别全部考试列表格式")
        }
        return items.mapNotNull { item ->
            val data = item.attr("data")
            val courseId = Regex("[?&]courseId=(\\d+)").find(data)?.groupValues?.get(1)
            val referenceId = Regex("[?&]taskrefId=(\\d+)").find(data)?.groupValues?.get(1)
            val title = clean(item.attr("title").ifBlank { item.selectFirst("dt")?.text().orEmpty() })
            if (title.isBlank()) return@mapNotNull null
            val statusText = clean(item.selectFirst("span.ks_state")?.text().orEmpty()).ifBlank {
                Regex("考试状态：([\\u4e00-\\u9fa5]+)")
                    .find(item.selectFirst("dl[aria-label]")?.attr("aria-label").orEmpty())
                    ?.groupValues?.get(1).orEmpty()
            }
            val timeText = item.selectFirst("dd")?.text()?.let { clean(it) }
            val times = timestamp.findAll(timeText.orEmpty()).toList()
            val start = times.firstOrNull()?.let(::parseTimestamp)
            val deadline = when {
                times.size >= 2 -> parseTimestamp(times.last())
                times.size == 1 -> parseTimestamp(times.first())
                else -> null
            }
            val course = courses.firstOrNull { it.id == courseId }
            StudyTask(
                id = "exam:${course?.id ?: courseId.orEmpty()}:${referenceId ?: digest("$title|$start|$deadline")}",
                courseId = course?.id ?: courseId.orEmpty(),
                courseName = course?.name.orEmpty(),
                title = title,
                kind = TaskKind.EXAM,
                state = resolveState(workStates[statusText], deadline, now),
                startTime = start,
                deadline = deadline,
                deadlineText = timeText?.takeIf { it.isNotBlank() },
            )
        }
    }

    // ------------------------------------------------------------------ 通用工具

    private fun resolveState(raw: CompletionState?, deadline: Instant?, now: Instant): CompletionState {
        val state = raw ?: CompletionState.UNKNOWN
        return if ((state == CompletionState.PENDING || state == CompletionState.NOT_STARTED) &&
            deadline != null && !deadline.isAfter(now)
        ) {
            CompletionState.EXPIRED
        } else {
            state
        }
    }

    private fun checkedDocument(html: String): Document {
        val doc = Jsoup.parse(html, "https://mooc1.chaoxing.com/")
        val loginForm = doc.selectFirst("input[type=password], form[action*=login], #loginForm")
        val message = doc.text()
        if (loginForm != null || message.contains("请重新登录") || message.contains("请先登录") ||
            message.contains("登录已失效") || message.contains("登录超时") ||
            doc.title().contains("用户登录")
        ) {
            throw AuthenticationRequired()
        }
        return doc
    }

    private fun parseTimestamp(match: MatchResult): Instant? = runCatching {
        LocalDateTime.of(
            match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt(),
            match.groupValues[4].toInt(), match.groupValues[5].toInt(),
            match.groupValues[6].ifBlank { "0" }.toInt(),
        ).atZone(chinaZone).toInstant()
    }.getOrNull()

    private fun parseAbsolute(text: String?): Instant? {
        if (text == null) return null
        val iso = isoTimestamp.findAll(text).lastOrNull()?.value
        if (iso != null) {
            runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()?.let { return it }
        }
        return timestamp.findAll(text).lastOrNull()?.let(::parseTimestamp)
    }

    private fun clean(text: String): String =
        Parser.unescapeEntities(text, false).replace('\u00a0', ' ').trim()

    private fun digest(seed: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun uniqueTasks(tasks: List<StudyTask>): List<StudyTask> {
        if (tasks.groupingBy { it.id }.eachCount().any { it.value > 1 }) {
            throw ParseException("任务 ID 重复，无法安全同步，已保留上次数据")
        }
        return tasks
    }
}
