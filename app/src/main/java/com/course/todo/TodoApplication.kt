package com.course.todo

import android.app.Application
import android.webkit.CookieManager
import com.course.todo.core.MarkdownDocument
import com.course.todo.core.SampleData
import com.course.todo.storage.AppPreferences
import com.course.todo.storage.MarkdownStore
import com.course.todo.storage.SyncStatus
import com.course.todo.sync.SyncScheduler

class TodoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CookieManager.getInstance().setAcceptCookie(true)
        val prefs = AppPreferences(this)
        val store = MarkdownStore(this)
        if (prefs.demoMode && store.read() == null) {
            val snapshot = SampleData.snapshot()
            store.write(MarkdownDocument.render(snapshot))
            prefs.writeSyncState(SyncStatus.SUCCESS, "演示数据已就绪", snapshot.fetchedAt.toEpochMilli())
        }
        if (prefs.readSyncState().status == SyncStatus.RUNNING) {
            prefs.writeSyncState(SyncStatus.IDLE, "等待重新同步，保留上次文档")
        }
        SyncScheduler.schedule(this)
    }
}
