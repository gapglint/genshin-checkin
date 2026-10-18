package com.lesumein.hoyocheckin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInClientTest {
    private fun sign(json: String, day: Int = 5) =
        CheckInClient.interpretSign(JSONObject(json), day) { "원석 x20" }

    @Test fun signSuccess() {
        assertEquals(Outcome.Signed(5, "원석 x20"), sign("""{"retcode":0,"message":"OK","data":{"code":"ok"}}"""))
    }

    @Test fun signSuccessWithoutRisk() {
        val json = """{"retcode":0,"data":{"gt_result":{"risk_code":0,"is_risk":false}}}"""
        assertEquals(Outcome.Signed(5, "원석 x20"), sign(json))
    }

    @Test fun signRiskMeansCaptcha() {
        val json = """{"retcode":0,"data":{"gt_result":{"risk_code":375,"gt":"x","is_risk":true}}}"""
        assertEquals(Outcome.CaptchaRequired, sign(json))
    }

    @Test fun alreadySigned() {
        assertEquals(Outcome.AlreadySigned(4), sign("""{"retcode":-5003,"message":"already"}"""))
    }

    @Test fun notLoggedIn() {
        assertEquals(Outcome.LoginExpired, sign("""{"retcode":-100,"message":"Please log in"}"""))
        assertEquals(Outcome.LoginExpired, CheckInClient.infoError(JSONObject("""{"retcode":-100}""")))
    }

    @Test fun tooFrequent() {
        assertEquals(Outcome.RateLimited, sign("""{"retcode":-1004,"message":"Visits too frequently"}"""))
        assertEquals(Outcome.RateLimited, CheckInClient.infoError(JSONObject("""{"retcode":-1004}""")))
    }

    @Test fun unknownError() {
        assertTrue(sign("""{"retcode":-10002,"message":"no role"}""") is Outcome.Failed)
    }

    @Test fun infoOk() {
        assertNull(CheckInClient.infoError(JSONObject("""{"retcode":0,"data":{"is_sign":false,"total_sign_day":3}}""")))
    }

    @Test fun rewardForDay() {
        val home = JSONObject("""{"retcode":0,"data":{"awards":[{"name":"원석","cnt":20},{"name":"모라","cnt":5000}]}}""")
        assertEquals("모라 x5000", CheckInClient.rewardName(home, 2))
        assertNull(CheckInClient.rewardName(home, 3))
    }

    @Test fun loginTokens() {
        assertTrue(LoginActivity.hasLoginTokens("mi18nLang=ko-kr; ltoken_v2=v2_abc; ltuid_v2=123"))
        assertFalse(LoginActivity.hasLoginTokens("mi18nLang=ko-kr; ltuid_v2=123"))
        assertFalse(LoginActivity.hasLoginTokens("old_ltoken_v2=x; ltuid_v2=1"))
    }

    @Test fun uidFromCookie() {
        assertEquals("123", LoginActivity.uidOf("mi18nLang=ko-kr; ltoken_v2=v2_abc; ltuid_v2=123"))
        assertNull(LoginActivity.uidOf("ltoken_v2=v2_abc"))
    }
}
