package com.zerui.safesmsprobe.activitylog
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class DashboardFormatTest {
    @Test fun nullIsNotZero() {
        assertEquals("—", DashboardFormat.duration(null))
        assertEquals("0分", DashboardFormat.duration(0))
        assertEquals("<1分", DashboardFormat.duration(59000))
        assertEquals("1小时32分", DashboardFormat.duration(5520000))
    }
    @Test fun usesBeijingMidnight() {
        val at = Instant.parse("2026-09-19T16:01:00Z").toEpochMilli()
        assertEquals("2026-09-20", DashboardFormat.date(at))
        assertEquals("00:01", DashboardFormat.time(at))
    }
    @Test fun incompleteEmptyDayIsUnknown() {
        assertFalse(DashboardFormat.available(false, false, 0, 0, 0))
        assertFalse(DashboardFormat.available(true, true, 0, 0, 0))
        assertTrue(DashboardFormat.available(true, false, 0, 0, 0))
        assertTrue(DashboardFormat.available(true, true, 1, 0, 0))
    }
    @Test fun periodicIntervalsRespectPlatformMinimum() {
        assertEquals(15, DashboardFormat.effectiveInterval(5))
        assertEquals(15, DashboardFormat.effectiveInterval(15))
        assertEquals(30, DashboardFormat.effectiveInterval(30))
    }
}
