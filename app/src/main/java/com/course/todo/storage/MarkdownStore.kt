package com.course.todo.storage

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.IOException

class MarkdownStore(context: Context) {
    private val appContext = context.applicationContext
    private val document = AtomicFile(File(appContext.filesDir, "课程待办.md"))

    fun read(): String? = synchronized(lock) {
        try {
            document.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: IOException) { null }
    }

    fun write(markdown: String) = synchronized(lock) {
        val stream = document.startWrite()
        try {
            stream.write(markdown.toByteArray(Charsets.UTF_8))
            document.finishWrite(stream)
        } catch (error: Throwable) {
            document.failWrite(stream)
            throw error
        }
    }

    fun exportTo(uri: Uri) {
        val markdown = read() ?: throw IOException("暂无可导出的文档，请先同步")
        val stream = appContext.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("无法写入所选文档")
        stream.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
    }

    companion object { private val lock = Any() }
}
