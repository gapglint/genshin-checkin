package kr.sumi.checkin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱을 열거나 업데이트·재부팅한 뒤, 오늘 출석을 놓친 계정이 있으면 곧 한 번 돈다(알람이 지워졌을 수 있다). */
class CheckInScheduleTest {
    private val today = "2026-10-26"

    @Test fun catchesUpWhenTodayIsMissed() {
        assertTrue(CheckIn.missedToday(listOf("2026-10-25" to null), today))
        assertTrue(CheckIn.missedToday(listOf(today to null, null to null), today))
    }

    @Test fun leavesItWhenSignedOrAlreadyReported() {
        assertFalse(CheckIn.missedToday(listOf(today to null), today))
        // 오늘 이미 문제(로그인 만료 등)를 알렸으면 열 때마다 되풀이하지 않는다.
        assertFalse(CheckIn.missedToday(listOf("2026-10-25" to today), today))
        assertFalse(CheckIn.missedToday(emptyList(), today))
    }
}
