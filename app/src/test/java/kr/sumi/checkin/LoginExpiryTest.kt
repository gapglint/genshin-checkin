package kr.sumi.checkin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginExpiryTest {
    private val day = 24L * 3600 * 1000
    private val loginAt = 1_760_000_000_000L
    private val warnFrom = loginAt + Store.LOGIN_LIFETIME_MS - Store.LOGIN_EXPIRY_WARN_MS

    @Test fun notBeforeTwoWeeksLeft() = assertFalse(Store.loginExpiryDue(loginAt, 0, warnFrom - 1))

    @Test fun dueWithTwoWeeksLeft() = assertTrue(Store.loginExpiryDue(loginAt, 0, warnFrom))

    @Test fun dueAfterExpiry() = assertTrue(Store.loginExpiryDue(loginAt, 0, loginAt + Store.LOGIN_LIFETIME_MS + day))

    @Test fun oncePerLogin() = assertFalse(Store.loginExpiryDue(loginAt, loginAt, warnFrom + day))

    @Test fun newLoginWarnsAgain() {
        val relogin = loginAt + 360 * day
        assertFalse(Store.loginExpiryDue(relogin, loginAt, warnFrom + day))
        assertTrue(Store.loginExpiryDue(relogin, loginAt, relogin + Store.LOGIN_LIFETIME_MS - Store.LOGIN_EXPIRY_WARN_MS))
    }

    @Test fun unknownLoginNeverWarns() = assertFalse(Store.loginExpiryDue(0, 0, warnFrom))
}
