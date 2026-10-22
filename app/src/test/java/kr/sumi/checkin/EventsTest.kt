package kr.sumi.checkin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventsTest {
    private val events = Events.parse(JSONObject(javaClass.getResource("/events.json")!!.readText()))

    @Test fun parsesEventList() {
        assertEquals(20, events.size)
        val web = events.first { it.id == "2317" }
        assertTrue(web.path.startsWith("https://act.hoyolab.com/"))
        assertTrue(web.live)
        assertEquals("/article/47163124", events.first().path)
        assertTrue(events.count { it.live } in 1..10)
    }
}
