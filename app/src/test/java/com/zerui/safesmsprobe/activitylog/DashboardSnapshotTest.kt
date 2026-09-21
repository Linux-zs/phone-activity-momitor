package com.zerui.safesmsprobe.activitylog

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DashboardSnapshotTest {
    @Test fun sqliteSnapshotFlagsDoNotCrashOrMisreportPermission() {
        val snapshot = JSONObject("""{"battery":98,"charging":0,"permission":1,"diagnostic":"ok"}""")
        // The previous renderer crashed here on the production response.
        assertThrows(JSONException::class.java) { snapshot.getBoolean("charging") }
        assertEquals(false, snapshot.flagOrNull("charging"))
        assertEquals(true, snapshot.flagOrNull("permission"))
    }

    @Test fun acceptsBothBooleanAndIntegerFlags() {
        for (value in listOf("true", "1")) {
            assertEquals(true, JSONObject("""{"flag":$value}""").flagOrNull("flag"))
        }
        for (value in listOf("false", "0")) {
            assertEquals(false, JSONObject("""{"flag":$value}""").flagOrNull("flag"))
        }
    }

    @Test fun unknownFlagsRemainUnknown() {
        for (json in listOf("{}", """{"flag":null}""", """{"flag":2}""", """{"flag":"bad"}""")) {
            assertNull(JSONObject(json).flagOrNull("flag"))
        }
    }

    @Test fun publicTimelineEventsDoNotNeedPrivateIds() {
        val event = JSONObject("""{"at":1789948800000,"kind":"unlock"}""")
        assertFalse(event.has("id"))
        assertEquals("3:1789948800000:unlock", timelineEventKey(3, event))
    }
}
