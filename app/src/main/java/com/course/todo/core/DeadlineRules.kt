package com.course.todo.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Deadline rules shared by the app and widgets.
 *
 * 临期（红色高亮）的两种触发条件：
 * 1. 距截止时间不超过 24 小时；
 * 2. 剩余时间不足总时长（开始时间 → 截止时间）的 30%。
 *
 * 相对倒计时（“剩余 2 小时”）只在能确认不足 24 小时时参与警示；不会伪造绝对时间。
 */
object DeadlineRules {
    val KNOWN_STATE_LABELS: Set<String> = setOf(
        "未提交", "已提交", "已截止", "已结束", "未开始", "状态未知",
    )

    /** 剩余时间占总时长不足该比例时视为临期。 */
    const val URGENT_REMAINING_PERCENT: Int = 30

    const val TASK_POINT_HEADING: String = "待完成任务点"

    private val chinaZone = ZoneId.of("Asia/Shanghai")
    private val isoTimestamp = Regex(
        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})",
    )
    private val timestamp = Regex(
        "(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?[ T]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?",
    )
    private val shortRemaining = Regex("^(?:剩余|还有)\\s*(\\d+)\\s*(小时|分钟|天)\\s*$")

    /** True only for an unchecked row with an explicit expired status or date. */
    fun isExpiredUnsubmitted(row: MarkdownRow, now: Instant): Boolean =
        row.checked == false && (row.stateLabel in EXPIRED_STATE_LABELS ||
            row.deadline?.let { !it.isAfter(now) } == true ||
            isLegacyClosedAssignment(row))

    /** A deadline exactly 24 hours away is urgent; a deadline at `now` has expired. */
    fun isUrgent24h(row: MarkdownRow, now: Instant): Boolean {
        if (row.checked != false || isExpiredUnsubmitted(row, now)) return false
        row.deadline?.let {
            return it.isAfter(now) && Duration.between(now, it) <= URGENT_WINDOW
        }
        // A platform countdown can warn without inventing a date or hiding a task.
        val remaining = shortRemaining.matchEntire(row.deadlineText.orEmpty()) ?: return false
        val amount = remaining.groupValues[1].toLongOrNull() ?: return false
        return when (remaining.groupValues[2]) {
            "小时" -> amount in 1..24
            "分钟" -> amount in 1..1440
            else -> false // “剩余 1 天”等按天取整的文本无法确定是否不足 24 小时
        }
    }

    /** 剩余时间不足总时长 30% 时临期。需要同时有开始时间和截止时间。 */
    fun isTimeProgressUrgent(
        row: MarkdownRow,
        now: Instant,
        thresholdPercent: Int = URGENT_REMAINING_PERCENT,
    ): Boolean {
        if (row.checked != false || isExpiredUnsubmitted(row, now)) return false
        val deadline = row.deadline ?: return false
        val start = row.startTime ?: return false
        if (!start.isBefore(deadline)) return false
        val remaining = Duration.between(now, deadline)
        if (remaining.isNegative || remaining.isZero) return false
        val window = Duration.between(start, deadline)
        val threshold = window.multipliedBy(thresholdPercent.toLong()).dividedBy(100)
        return remaining <= threshold
    }

    /** 两种临期条件任一命中。 */
    fun isUrgent(row: MarkdownRow, now: Instant): Boolean =
        isUrgent24h(row, now) || isTimeProgressUrgent(row, now)

    /** 时间已用百分比（0–100），缺少开始时间或截止时间时返回 null。 */
    fun elapsedPercent(row: MarkdownRow, now: Instant): Int? {
        val deadline = row.deadline ?: return null
        val start = row.startTime ?: return null
        val window = Duration.between(start, deadline).toMillis()
        if (window <= 0) return null
        val elapsed = Duration.between(start, now).toMillis()
        return ((elapsed * 100) / window).coerceIn(0, 100).toInt()
    }

    /** 剩余时间百分比（0–100），缺少开始时间或截止时间时返回 null。 */
    fun remainingPercent(row: MarkdownRow, now: Instant): Int? = elapsedPercent(row, now)?.let { 100 - it }

    /** 给 UI 的临期原因文案；不临期返回 null。 */
    fun urgencyLabel(row: MarkdownRow, now: Instant): String? {
        if (isUrgent24h(row, now)) return "24 小时内截止"
        if (isTimeProgressUrgent(row, now)) {
            val elapsed = elapsedPercent(row, now) ?: return "剩余时间不足 30%"
            return "时间已过 $elapsed%"
        }
        return null
    }

    /** Rows suitable for a widget/list, excluding completed and explicitly expired tasks. */
    fun filterVisible(rows: List<MarkdownRow>, now: Instant, hideCompleted: Boolean = true): List<MarkdownRow> =
        rows.filterNot { (hideCompleted && it.checked == true) || isExpiredUnsubmitted(it, now) }

    /** Legacy rows written before parser status metadata was available. */
    private fun isLegacyClosedAssignment(row: MarkdownRow): Boolean =
        row.stateLabel == "未提交" &&
            row.deadline == null &&
            row.deadlineText == "截止时间未提供" &&
            row.text.trimStart().startsWith("作业：")

    /** Whether a detail fragment contains a supported, explicit deadline expression. */
    fun looksLikeDeadline(text: String): Boolean =
        text.contains("截止") || text.contains("剩余") || text.contains("还有") ||
            isoTimestamp.containsMatchIn(text) || timestamp.containsMatchIn(text)

    /** Parse an unambiguous absolute timestamp as China local time unless it has an offset. */
    fun parseAbsoluteInstant(text: String): Instant? {
        val iso = isoTimestamp.findAll(text).lastOrNull()?.value
        if (iso != null) {
            runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()?.let { return it }
        }
        val match = timestamp.findAll(text).lastOrNull() ?: return null
        return runCatching {
            LocalDateTime.of(
                match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt(),
                match.groupValues[4].toInt(), match.groupValues[5].toInt(),
                match.groupValues[6].ifBlank { "0" }.toInt(),
            ).atZone(chinaZone).toInstant()
        }.getOrNull()
    }

    private val EXPIRED_STATE_LABELS = setOf("已截止", "已结束")
    private val URGENT_WINDOW: Duration = Duration.ofHours(24)
}
