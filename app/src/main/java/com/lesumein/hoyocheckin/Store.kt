package com.lesumein.hoyocheckin

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate
import java.time.ZoneId

/**
 * 쿠키와 마지막 실행 결과를 앱 전용 저장소에 둔다.
 * 다른 앱은 읽을 수 없고, 매니페스트에서 백업도 막아 두었다.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("hoyo", Context.MODE_PRIVATE)

    var cookie: String?
        get() = prefs.getString("cookie", null)
        set(value) = prefs.edit { putString("cookie", value) }

    var lastResult: String?
        get() = prefs.getString("last_result", null)
        set(value) = prefs.edit { putString("last_result", value) }

    var lastRunAt: Long
        get() = prefs.getLong("last_run_at", 0)
        set(value) = prefs.edit { putLong("last_run_at", value) }

    /** 서버에서 출첵을 확인한 날(UTC+8 기준)과 그때의 누적 일수. 그날은 더 묻지 않는다. */
    var signedDay: String?
        get() = prefs.getString("signed_day", null)
        set(value) = prefs.edit { putString("signed_day", value) }

    var signedTotal: Int
        get() = prefs.getInt("signed_total", 0)
        set(value) = prefs.edit { putInt("signed_total", value) }

    /** 오늘 받은 출첵 보상 이름. 화면에 보여 준다. */
    var lastReward: String?
        get() = prefs.getString("last_reward", null)
        set(value) = prefs.edit { putString("last_reward", value) }

    /** 보충 출석이 가능할 때 알릴지. 앱 설정에서 끌 수 있다. */
    var makeUpAlerts: Boolean
        get() = prefs.getBoolean("make_up_alerts", true)
        set(value) = prefs.edit { putBoolean("make_up_alerts", value) }

    /** 보충 출석이 가능하다고 확인한 날과 그때 놓친 날 수. 화면의 보충 안내에 쓴다. */
    var makeUpDay: String?
        get() = prefs.getString("make_up_day", null)
        set(value) = prefs.edit { putString("make_up_day", value) }

    var makeUpMissed: Int
        get() = prefs.getInt("make_up_missed", 0)
        set(value) = prefs.edit { putInt("make_up_missed", value) }

    /** 같은 알림을 하루에 여러 번 보내지 않으려고 마지막으로 보낸 알림의 키를 기억한다. */
    var lastNotified: String?
        get() = prefs.getString("last_notified", null)
        set(value) = prefs.edit { putString("last_notified", value) }

    var lastMakeUpNotified: String?
        get() = prefs.getString("last_make_up_notified", null)
        set(value) = prefs.edit { putString("last_make_up_notified", value) }

    /** 로그인·기록을 지운다. 사용자가 고른 설정은 남긴다. */
    fun clear() {
        val alerts = makeUpAlerts
        prefs.edit { clear() }
        makeUpAlerts = alerts
    }

    companion object {
        /** 호요랩 출첵 하루는 UTC+8 자정(한국 시간 새벽 1시)에 바뀐다. */
        fun checkInDay(): LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    }
}
