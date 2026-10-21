package com.course.todo.storage

import android.content.Context
import com.course.todo.core.SnapshotJson
import com.course.todo.core.StudySnapshot
import java.io.File

/**
 * 上一次成功同步的快照（`last_snapshot.json`）。
 *
 * 轻量同步靠它补齐绝对开始/截止时间、分数和章节任务点，避免每分钟重复请求
 * 13 门课的课程页；写入采用「临时文件 + rename」保证原子性。
 */
class SnapshotStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "last_snapshot.json")

    fun read(): StudySnapshot? = synchronized(lock) {
        runCatching { SnapshotJson.decode(file.takeIf { it.exists() }?.readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun write(snapshot: StudySnapshot) = synchronized(lock) {
        val temp = File(appContext.filesDir, "last_snapshot.json.tmp")
        runCatching {
            temp.writeText(SnapshotJson.encode(snapshot), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
                temp.delete()
            }
        }.onFailure { temp.delete() }
    }

    companion object {
        private val lock = Any()
    }
}
