package com.zerui.safesmsprobe.activitylog

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ActivityCoreTest {
    @Test fun stableIdsAndKindsAreDistinct() {
        assertEquals(ActivityCore.id("device:123:unlock"), ActivityCore.id("device:123:unlock"))
        assertNotEquals(ActivityCore.id("device:123:unlock"), ActivityCore.id("device:123:lock"))
        assertEquals(64, ActivityCore.id("device:123:unlock").length)
    }
    @Test fun firstReadStartsAtShanghaiMidnight() {
        val now = Instant.parse("2026-09-19T01:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-09-18T16:00:00Z").toEpochMilli(), ActivityCore.queryStart(0, now))
        assertEquals(now - 120000, ActivityCore.queryStart(now - 60000, now))
        assertEquals(now - 2 * ActivityCore.DAY, ActivityCore.queryStart(now - 5 * ActivityCore.DAY, now))
    }
    @Test fun onlyHttpsOriginsAllowed() {
        assertEquals("https://udong.udong.top", ActivityCore.serverUrl(" https://udong.udong.top/ "))
        listOf("http://example.org", "https://a:b@example.org", "https://example.org/api", "https://example.org?key=x").forEach {
            assertTrue(runCatching { ActivityCore.serverUrl(it) }.isFailure)
        }
    }
    @Test fun acknowledgmentCannotDropOrDuplicateQueuedEvents() {
        assertTrue(ActivityCore.acknowledgeMatches(listOf("a", "b"), listOf("b", "a")))
        assertFalse(ActivityCore.acknowledgeMatches(listOf("a", "b"), listOf("a")))
        assertFalse(ActivityCore.acknowledgeMatches(listOf("a", "b"), listOf("a", "a")))
        assertFalse(ActivityCore.acknowledgeMatches(listOf("a"), listOf("other")))
    }
    @Test fun retryOnlyTransientHttpFailures() {
        listOf(429, 500, 502, 503).forEach { assertTrue(ActivityCore.retryableHttp(it)) }
        listOf(200, 301, 401, 403, 409, 422).forEach { assertFalse(ActivityCore.retryableHttp(it)) }
    }
}
