package kr.sumi.checkin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.io.IOException
import kotlin.random.Random

/**
 * 서버 기준으로 출석 날짜가 바뀐 직후(UTC+8 자정 = 한국 시간 새벽 1시)에 출석하도록 알람을 건다.
 *
 * 폰 시계는 틀릴 수 있어서 벽시계(RTC) 알람을 쓰지 않는다. 서버 시각으로 자정까지 남은 시간을 구하고,
 * 폰 시계와 무관한 부팅 후 경과 시간(ELAPSED_REALTIME) 기준으로 그만큼 뒤에 울린다.
 * 모든 사용자가 0시 정각에 몰리면 요청 과다(-1004)에 걸리기 쉬워 1~5분 사이로 흩뜨린다.
 */
object DailyAlarm {
    private const val MIN_DELAY_AFTER_RESET_MS = 60_000L
    private const val SPREAD_MS = 4 * 60_000L

    fun schedule(context: Context) {
        val serverNow = ServerClock.now()
        val runAt = ServerClock.nextReset(serverNow) + MIN_DELAY_AFTER_RESET_MS + Random.nextLong(SPREAD_MS)
        val trigger = SystemClock.elapsedRealtime() + (runAt - serverNow)
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (canRunExactly(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pendingIntent(context))
        } else {
            // 정확한 알람 권한이 없으면 절전 중에도 울리되 시스템이 몇 분 늦출 수 있다.
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pendingIntent(context))
        }
        val settings = AppSettings(context)
        settings.nextRunAt = runAt
        if (ServerClock.offsetMs != 0L) settings.clockOffset = ServerClock.offsetMs
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
        AppSettings(context).nextRunAt = 0
    }

    /** 정확한 시각에 울릴 수 있는지. Android 12부터는 사용자가 '알람 및 리마인더'를 허용해야 한다. */
    fun canRunExactly(context: Context) =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, DailyAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** 알람이 울리면 바로 출석하고 다음 날 알람을 건다. 네트워크가 안 되면 연결될 때 다시 시도한다. */
class DailyAlarmReceiver : BroadcastReceiver() {
    private companion object {
        const val MAX_INLINE_ACCOUNTS = 3
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val count = AppSettings(app).accounts().size
        if (count == 0) return
        // 알람 처리에는 1분 정도의 시간 제한이 있다. 계정이 많으면 작업 예약으로 넘겨 시간 제한 없이 돌린다.
        if (count > MAX_INLINE_ACCOUNTS) {
            CheckIn.retrySoon(app)
            DailyAlarm.schedule(app)
            return
        }
        val pending = goAsync()
        Thread {
            try {
                if (CheckIn.runAll(app).any { it is Outcome.RateLimited }) CheckIn.retrySoon(app)
            } catch (e: IOException) {
                CheckIn.retrySoon(app)
            } finally {
                DailyAlarm.schedule(app)
                pending.finish()
            }
        }.start()
    }
}

/** 재부팅·앱 업데이트·정확한 알람 권한 변경 뒤에는 알람이 사라지거나 바뀌므로 다시 건다. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AppSettings(context).hasAccounts) CheckIn.schedule(context)
    }
}
