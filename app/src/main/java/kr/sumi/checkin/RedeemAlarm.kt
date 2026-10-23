package kr.sumi.checkin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.ThreadLocalRandom

/**
 * 생방송 리딤 코드용 금요일 밤 추가 확인. 원신 특별 방송은 보통 금요일 오후 8시(UTC+8, 한국 시간 9시)에 하고,
 * 코드는 방송 중에 나와 하루 안팎이면 만료된다. 그래서 금요일 밤 한국 시간 10시·11시·자정에 새 코드가 있는지 본다.
 * 새 코드가 없으면 공개 목록만 보고 끝난다(호요랩에 로그인 요청을 보내지 않는다).
 */
object RedeemAlarm {
    /** 확인 시각(UTC+8). 한국 시간으로는 한 시간 뒤. */
    internal val SLOTS = listOf(LocalTime.of(21, 0), LocalTime.of(22, 0), LocalTime.of(23, 0))
    private const val SPREAD_MS = 3 * 60_000L

    fun schedule(context: Context) {
        val serverNow = ServerClock.now()
        val runAt = nextSlot(serverNow) + ThreadLocalRandom.current().nextLong(SPREAD_MS)
        val trigger = SystemClock.elapsedRealtime() + (runAt - serverNow)
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (DailyAlarm.canRunExactly(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pendingIntent(context))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pendingIntent(context))
        }
    }

    fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))

    /** serverMs 다음의 금요일 확인 시각(서버 시각, ms). */
    internal fun nextSlot(serverMs: Long): Long {
        val today = Instant.ofEpochMilli(serverMs).atZone(ServerClock.ZONE).toLocalDate()
        for (day in 0L..7L) {
            val date = today.plusDays(day)
            if (date.dayOfWeek != DayOfWeek.FRIDAY) continue
            for (slot in SLOTS) {
                val at = date.atTime(slot).atZone(ServerClock.ZONE).toInstant().toEpochMilli()
                if (at > serverMs) return at
            }
        }
        error("unreachable")
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, RedeemAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * 알람이 울리면 새 코드를 확인해 입력하고 다음 확인을 건다.
 * 알람 처리에는 1분 정도의 시간 제한이 있어, 코드가 많아 오래 걸릴 수 있는 입력은 작업 예약으로 넘긴다.
 */
class RedeemAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val settings = AppSettings(app)
        if (!settings.hasAccounts) return
        RedeemAlarm.schedule(app)
        if (!settings.redeemEnabled) return
        val pending = goAsync()
        Thread {
            try {
                if (Redeem.hasNewCodes(app)) Jobs.scheduleOnce(app, Jobs.REDEEM)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
