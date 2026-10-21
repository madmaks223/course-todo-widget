package com.course.todo.core

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.jsoup.parser.Parser

data class MarkdownRow(
    val text: String,
    val checked: Boolean? = null,
    val isHeading: Boolean = false,
    val detail: String? = null,
    val startTime: Instant? = null,
    val deadline: Instant? = null,
    val stateLabel: String? = null,
    val deadlineText: String? = null,
    /** 章节任务点汇总行（不带复选框，不进入小组件待办列表）。 */
    val isTaskPoint: Boolean = false,
    val taskPointCount: Int? = null,
)

object MarkdownDocument {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))
    private val taskLine = Regex("^\\s*[-*+] \\[([ xX])\\]\\s+(.*)$")
    private val headingLine = Regex("^#{1,6}\\s+(.+)$")
    private val comments = Regex("<!--.*?-->")
    private val deadlineMetadata = Regex("<!--\\s*xxt-deadline:([^\\s<>]+)\\s*-->")
    private val startMetadata = Regex("<!--\\s*xxt-start:([^\\s<>]+)\\s*-->")
    private val taskPointMetadata = Regex("<!--\\s*xxt-taskpoint:([^:\\s<>]+):(\\d+)\\s*-->")

    fun render(snapshot: StudySnapshot): String = buildString {
        appendLine(if (snapshot.warnings.any { it.contains("演示数据") }) "# 课程待办（演示）" else "# 课程待办")
        appendLine()
        appendLine("同步时间：${dateTime.format(snapshot.fetchedAt)}（北京时间）")
        appendLine()
        snapshot.warnings.forEach { appendLine("> ${escape(it)}") }
        if (snapshot.warnings.isNotEmpty()) appendLine()
        if (snapshot.tasks.isEmpty()) {
            appendLine("暂无作业或考试。")
            appendLine()
        } else {
            val courseNames = snapshot.courses.associate { it.id to it.name }
            snapshot.tasks.groupBy { it.courseId }
                .entries.sortedBy { courseNames[it.key] ?: it.value.first().courseName }
                .forEach { (courseId, tasks) ->
                    appendLine("## ${escape(courseNames[courseId] ?: tasks.first().courseName)}")
                    appendLine()
                    tasks.sortedWith(
                        compareBy<StudyTask> { it.state == CompletionState.SUBMITTED }
                            .thenBy { it.deadline ?: Instant.MAX }
                            .thenBy { it.title }
                            .thenBy { it.id },
                    ).forEach { task ->
                        appendTask(task)
                    }
                    appendLine()
                }
        }
        // 任务点是长期存在的学习进度，统一放在作业和考试之后，避免打断待办阅读。
        if (snapshot.taskPoints.isNotEmpty()) {
            appendLine("## ${DeadlineRules.TASK_POINT_HEADING}")
            appendLine()
            snapshot.taskPoints.sortedByDescending { it.pending }.forEach { points ->
                val id = URLEncoder.encode(points.courseId, "UTF-8")
                appendLine("- ${escape(points.courseName)}：${points.pending} 个待完成 <!-- xxt-taskpoint:$id:${points.pending} -->")
                points.chapters.take(CHAPTERS_PER_COURSE).forEach { chapter ->
                    val label = listOf(chapter.number, chapter.title)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    appendLine("  - ${escape(label)}（${chapter.pending} 个）")
                }
                appendLine()
            }
        }
    }

    private fun StringBuilder.appendTask(task: StudyTask) {
        val check = if (task.state == CompletionState.SUBMITTED) "x" else " "
        val kind = when (task.kind) {
            TaskKind.ASSIGNMENT -> "作业"
            TaskKind.EXAM -> "考试"
            TaskKind.GROUP_TASK -> "分组任务"
        }
        append("- [$check] $kind：${escape(task.title)}")
        append(" | ${stateLabel(task.state)}")
        task.score?.takeIf { it.isNotBlank() }?.let { append(" | 分数：${escape(it)}") }
        val due = task.deadline?.let { "截止：${dateTime.format(it)}（北京时间）" }
            ?: task.deadlineText?.takeIf { it.isNotBlank() } ?: "截止时间未提供"
        append(" | ${escape(due)}")
        append(" <!-- xxt-task:${URLEncoder.encode(task.id, "UTF-8")} -->")
        task.startTime?.let { append(" <!-- xxt-start:$it -->") }
        task.deadline?.let { append(" <!-- xxt-deadline:$it -->") }
        appendLine()
    }

    fun parse(markdown: String): List<MarkdownRow> {
        val rows = mutableListOf<MarkdownRow>()
        var inFence = false
        markdown.lineSequence().forEach { raw ->
            val pointMatch = taskPointMetadata.find(raw)
            val line = comments.replace(raw, "").trim()
            if (line.startsWith("```") || line.startsWith("~~~")) {
                inFence = !inFence
                return@forEach
            }
            if (inFence || line.isBlank() || line.startsWith("> ") || line.startsWith("同步时间：")) return@forEach
            if (pointMatch != null) {
                rows += MarkdownRow(
                    text = unescape(line.removePrefix("- ").removePrefix("* ").trim()),
                    isTaskPoint = true,
                    taskPointCount = pointMatch.groupValues[2].toIntOrNull(),
                )
                return@forEach
            }
            val task = taskLine.matchEntire(line)
            val heading = headingLine.matchEntire(line)
            when {
                task != null -> {
                    val parts = splitDetails(task.groupValues[2])
                    val unescapedParts = parts.map(::unescape)
                    val taskDetails = unescapedParts.drop(1)
                    val detail = taskDetails.joinToString(" | ").takeIf { it.isNotBlank() }
                    val stateLabel = taskDetails.firstOrNull { it in DeadlineRules.KNOWN_STATE_LABELS }
                    val deadlineText = taskDetails.firstOrNull {
                        it !in DeadlineRules.KNOWN_STATE_LABELS && DeadlineRules.looksLikeDeadline(it)
                    }
                    rows += MarkdownRow(
                        text = unescapedParts.firstOrNull().orEmpty(),
                        checked = task.groupValues[1].equals("x", ignoreCase = true),
                        detail = detail,
                        startTime = startMetadata.find(raw)?.groupValues?.get(1)
                            ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                        deadline = deadlineMetadata.find(raw)?.groupValues?.get(1)
                            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                            ?: deadlineText?.let(DeadlineRules::parseAbsoluteInstant),
                        stateLabel = stateLabel,
                        deadlineText = deadlineText,
                    )
                }
                heading != null -> rows += MarkdownRow(unescape(heading.groupValues[1]), isHeading = true)
                else -> rows += MarkdownRow(unescape(line.removePrefix("- ").removePrefix("* ").trim()))
            }
        }
        return rows
    }

    private fun splitDetails(text: String): List<String> = text.split(" | ")

    private fun escape(text: String): String = buildString {
        text.replace(Regex("[\\r\\n\\t]+"), " ").forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\\', '`', '*', '_', '[', ']', '|' -> { append('\\'); append(character) }
                else -> append(character)
            }
        }
    }

    private fun unescape(text: String): String = Parser.unescapeEntities(
        text.replace(Regex("\\\\([\\\\`*_\\[\\]|])"), "$1"), false,
    )

    private fun stateLabel(state: CompletionState): String = when (state) {
        CompletionState.PENDING -> "未提交"
        CompletionState.SUBMITTED -> "已提交"
        CompletionState.EXPIRED -> "已截止"
        CompletionState.NOT_STARTED -> "未开始"
        CompletionState.UNKNOWN -> "状态未知"
    }

    private const val CHAPTERS_PER_COURSE = 200
}
