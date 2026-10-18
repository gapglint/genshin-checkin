package com.lesumein.hoyocheckin

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    private const val CHANNEL = "checkin"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "출첵 결과", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 이미 출첵한 경우는 조용히 넘어가고, 나머지는 같은 날 같은 종류를 한 번만 알린다. */
    fun notify(context: Context, store: Store, outcome: Outcome) {
        // 이미 출첵했거나 잠깐의 속도 제한은 알릴 필요가 없다.
        if (outcome is Outcome.AlreadySigned || outcome == Outcome.RateLimited) return
        val key = "${outcome::class.simpleName}:${Store.checkInDay()}"
        if (key == store.lastNotified) return

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = when (outcome) {
            Outcome.LoginExpired -> LoginActivity.loginIntent(context)
            Outcome.CaptchaRequired -> LoginActivity.checkInPageIntent(context)
            else -> Intent(context, MainActivity::class.java)
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when (outcome) {
            is Outcome.Signed -> "원신 출첵 완료"
            Outcome.LoginExpired -> "호요랩 로그인이 필요해요"
            Outcome.CaptchaRequired -> "출첵에 캡차가 필요해요"
            else -> "원신 출첵 실패"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(CheckIn.describe(outcome))
            .setStyle(NotificationCompat.BigTextStyle().bigText(CheckIn.describe(outcome)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1, notification)
        store.lastNotified = key
    }
}
