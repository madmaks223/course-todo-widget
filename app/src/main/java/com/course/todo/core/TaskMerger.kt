package com.course.todo.core

/**
 * 轻量同步（3 个接口）与完整同步（逐门课）之间的任务合并。
 *
 * 轻量列表更新状态与相对倒计时，但缺绝对开始/截止时间；完整同步有精确时间。
 * 这里按 `类型 + 课程 + 标题` 对齐，保留旧的精确时间与分数，只用新状态覆盖。
 */
object TaskMerger {
    fun merge(previous: List<StudyTask>, bulk: List<StudyTask>): List<StudyTask> {
        if (bulk.isEmpty()) return previous
        val previousKeys = previous.map(::key).toSet()
        val matchedKeys = mutableSetOf<String>()
        val updated = previous.map { old ->
            val fresh = bulk.firstOrNull { key(it) == key(old) }
            if (fresh == null) {
                old
            } else {
                matchedKeys += key(old)
                old.copy(
                    state = fresh.state,
                    startTime = old.startTime ?: fresh.startTime,
                    deadline = old.deadline ?: fresh.deadline,
                    deadlineText = old.deadlineText ?: fresh.deadlineText,
                    score = old.score ?: fresh.score,
                )
            }
        }
        val newTasks = bulk.filter { key(it) !in previousKeys && key(it) !in matchedKeys }
        return updated + newTasks
    }

    private fun key(task: StudyTask): String = "${task.kind}:${task.courseId}|${task.title}"
}
