package kr.sumi.checkin

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ServerClockTest {
    @After fun reset() { ServerClock.offsetMs = 0 }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun dayChangesAtUtc8Midnight() {
        // 한국 시간 00:59(UTC 15:59)는 아직 전날, 01:00(UTC 16:00)부터 다음 날
        assertEquals(LocalDate.of(2026, 10, 18), ServerClock.dayOf(ms("2026-10-18T15:59:59Z")))
        assertEquals(LocalDate.of(2026, 10, 19), ServerClock.dayOf(ms("2026-10-18T16:00:00Z")))
    }

    @Test fun nextResetIsNextUtc8Midnight() {
        assertEquals(ms("2026-10-18T16:00:00Z"), ServerClock.nextReset(ms("2026-10-18T12:54:39Z")))
        assertEquals(ms("2026-10-19T16:00:00Z"), ServerClock.nextReset(ms("2026-10-18T16:00:00Z")))
    }

    @Test fun phoneClockErrorIsCorrected() {
        // 폰 시계가 10분 빠르다: 서버 00:55(KST)인데 폰은 01:05라고 생각한다
        val server = ms("2026-10-18T15:55:00Z")
        ServerClock.observe(server, phoneMs = server + 10 * 60_000)
        assertEquals(-10 * 60_000L, ServerClock.offsetMs)
        val serverNowFromPhone = (server + 10 * 60_000) + ServerClock.offsetMs
        assertEquals(LocalDate.of(2026, 10, 18), ServerClock.dayOf(serverNowFromPhone))
        assertEquals(5 * 60_000L, ServerClock.nextReset(serverNowFromPhone) - serverNowFromPhone)
    }

    @Test fun challengeFromRiskyResponse() {
        val risky = JSONObject("""{"retcode":0,"data":{"gt_result":{"risk_code":5001,"challenge":"abc","is_risk":true}}}""")
        assertEquals("abc", CheckInClient.challengeOf(risky))
        assertNull(CheckInClient.challengeOf(JSONObject("""{"retcode":0,"data":{"gt_result":{"challenge":""}}}""")))
    }
}
