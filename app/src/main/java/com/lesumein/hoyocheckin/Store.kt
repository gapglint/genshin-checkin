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

    /** 같은 알림을 하루에 여러 번 보내지 않으려고 마지막으로 보낸 알림의 키를 기억한다. */
    var lastNotified: String?
        get() = prefs.getString("last_notified", null)
        set(value) = prefs.edit { putString("last_notified", value) }

    fun clear() = prefs.edit { clear() }

    companion object {
        /** 호요랩 출첵 하루는 UTC+8 자정(한국 시간 새벽 1시)에 바뀐다. */
        fun checkInDay(): LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    }
}
