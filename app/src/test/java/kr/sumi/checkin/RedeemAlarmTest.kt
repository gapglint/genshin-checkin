package kr.sumi.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RedeemAlarmTest {
    private fun at(text: String) = LocalDateTime.parse(text).atZone(ServerClock.ZONE).toInstant().toEpochMilli()

    @Test fun nextFridaySlot() {
        // 2026-10-23은 금요일. 시각은 서버 기준(UTC+8).
        assertEquals(at("2026-10-23T21:00"), RedeemAlarm.nextSlot(at("2026-10-23T20:30")))
        assertEquals(at("2026-10-23T22:00"), RedeemAlarm.nextSlot(at("2026-10-23T21:00")))
        assertEquals(at("2026-10-30T21:00"), RedeemAlarm.nextSlot(at("2026-10-23T23:30")))
        assertEquals(at("2026-10-30T21:00"), RedeemAlarm.nextSlot(at("2026-10-26T09:00")))
    }

    @Test fun untriedCodes() {
        val groups = listOf(listOf("A", "B"), listOf("C"))
        assertTrue(Redeem.untried(groups, setOf("A")))
        assertTrue(Redeem.untried(groups, setOf("A", "B")))
        assertFalse(Redeem.untried(groups, setOf("A", "B", "C")))
        assertFalse(Redeem.untried(emptyList(), emptySet()))
    }
}
