package com.course.todo.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStaleRulesTest {
    private val hour = 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    @Test
    fun staleOnlyAfterFourHours() {
        assertFalse(WidgetStaleRules.isStale(now - 4 * hour, now, demoMode = false, hasLoggedIn = true))
        assertTrue(WidgetStaleRules.isStale(now - 4 * hour - 1, now, demoMode = false, hasLoggedIn = true))
    }

    @Test
    fun demoAndLoggedOutAreNotStale() {
        assertFalse(WidgetStaleRules.isStale(now - 10 * hour, now, demoMode = true, hasLoggedIn = true))
        assertFalse(WidgetStaleRules.isStale(now - 10 * hour, now, demoMode = false, hasLoggedIn = false))
    }

    @Test
    fun neverSyncedIsStaleButSyncingIsNot() {
        assertTrue(WidgetStaleRules.isStale(0L, now, demoMode = false, hasLoggedIn = true))
        assertFalse(WidgetStaleRules.isStale(0L, now, demoMode = false, hasLoggedIn = false))
        assertFalse(
            WidgetStaleRules.isStale(now - 10 * hour, now, demoMode = false, hasLoggedIn = true, syncing = true),
        )
    }
}
