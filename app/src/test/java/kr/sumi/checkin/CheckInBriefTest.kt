package kr.sumi.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 접힌 알림 한 줄용 짧은 문구. 오른쪽 그림 옆에 잘리지 않고 들어가야 한다. */
class CheckInBriefTest {
    @Test fun signedShowsDayAndReward() {
        assertEquals("18일째 · 원석 ×20", CheckIn.brief(Outcome.Signed(18, "원석 ×20")))
        assertEquals("3일째", CheckIn.brief(Outcome.Signed(3, null)))
    }

    @Test fun fixedMessagesAreShort() {
        listOf(Outcome.LoginExpired, Outcome.CaptchaRequired, Outcome.NotConfirmed, Outcome.AlreadySigned(5))
            .forEach { assertTrue(CheckIn.brief(it), CheckIn.brief(it).length <= 18) }
    }
}
