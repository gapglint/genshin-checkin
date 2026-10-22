package kr.sumi.checkin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun onlyWebEventPagesCountAsTargets() {
        assertTrue(Events.isEventPage("https://act.hoyoverse.com/ys/event/e20261001ugc-ali0ea/index.html?game_biz=hk4e_global"))
        assertTrue(Events.isEventPage("https://act.hoyolab.com/ys/event/bbs-event-20251029points/index.html"))
        // 충전센터는 호요랩 로그인이 통하지 않아 참여 링크로 쓰지 않는다
        assertFalse(Events.isEventPage("https://sdk.hoyoverse.com/payment/genshin/index.html?utm_content=TopUp#/"))
        assertFalse(Events.isEventPage("https://www.hoyolab.com/setting/notification"))
    }
}
