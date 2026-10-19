package kr.sumi.checkin

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.Notification

object Notifier {
    private const val CHANNEL = "checkin"
    private const val CHANNEL_MAKE_UP = "make_up"
    private const val CHANNEL_REDEEM = "redeem"
    private const val CHANNEL_QUIET = "quiet_night"
    private const val CHANNEL_NOTES = "notes"
    private const val ID_REDEEM = 3
    private const val ID_CHECK_IN = 1
    private const val ID_MAKE_UP = 2
    private const val ID_LOGIN_EXPIRY = 4
    private const val ID_RESIN = 5
    private const val ID_EXPEDITIONS = 6
    private const val ID_HOME_COIN = 7

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "출첵 결과", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_MAKE_UP, "보충 출석", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_REDEEM, "리딤 코드", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_NOTES, "레진·탐사 파견", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_QUIET, "밤 알림(소리 없음)", NotificationManager.IMPORTANCE_LOW))
    }

    /** 밤에 조용히가 켜져 있고 지금이 밤(폰 시각 오후 10시~오전 8시)이면 소리 없는 채널, 아니면 원래 채널. */
    private fun channel(context: Context, normal: String): String {
        val hour = java.time.LocalTime.now().hour
        return if (AppSettings(context).quietNight && (hour >= 22 || hour < 8)) CHANNEL_QUIET else normal
    }

    /** 이미 출첵한 경우는 조용히 넘어가고, 나머지는 같은 날 같은 종류를 한 번만 알린다. */
    @SuppressLint("MissingPermission") // canNotify에서 알림 권한을 확인한다.
    fun notify(context: Context, store: Store, outcome: Outcome) {
        // 이미 출첵했거나 잠깐의 속도 제한은 알릴 필요가 없다.
        if (outcome is Outcome.AlreadySigned || outcome is Outcome.RateLimited) return
        // 출석 완료 알림은 끌 수 있다. 문제(로그인·캡차 등)는 항상 알린다.
        if (outcome is Outcome.Signed && !AppSettings(context).notifyCheckIn) return
        val key = "${outcome.javaClass.name}:${Store.checkInDay()}"
        if (key == store.lastNotified) return

        if (!canNotify(context)) return

        val intent = when (outcome) {
            Outcome.LoginExpired -> LoginActivity.loginIntent(context)
            Outcome.CaptchaRequired, Outcome.NotConfirmed -> LoginActivity.checkInPageIntent(context, store.accountId)
            else -> MainActivity.accountIntent(context, store.accountId)
        }
        val id = idFor(ID_CHECK_IN, store)
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = who(context, store) + when (outcome) {
            is Outcome.Signed -> "원신 출첵 완료"
            Outcome.LoginExpired -> "호요랩 로그인이 필요해요"
            Outcome.CaptchaRequired -> "출첵에 캡차가 필요해요"
            Outcome.NotConfirmed -> "출석이 반영되지 않았어요"
            else -> "원신 출첵 실패"
        }
        val notification = Notification.Builder(context, channel(context, CHANNEL))
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(CheckIn.describe(outcome))
            .setStyle(Notification.BigTextStyle().bigText(CheckIn.describe(outcome)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        store.lastNotified = key
    }

    /** 보충 출석 가능 알림. 누르면 앱이 바로 보충한다. 하루 한 번. */
    @SuppressLint("MissingPermission") // canNotify에서 알림 권한을 확인한다.
    fun notifyMakeUp(context: Context, store: Store, missed: Int) {
        val key = "MakeUp:${Store.checkInDay()}"
        if (key == store.lastMakeUpNotified || !canNotify(context)) return
        val id = idFor(ID_MAKE_UP, store)
        val pending = PendingIntent.getActivity(
            context, id, MainActivity.makeUpIntent(context, store.accountId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, channel(context, CHANNEL_MAKE_UP))
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(who(context, store) + "보충 출석을 할 수 있어요")
            .setContentText("이번 달에 놓친 날이 ${missed}일 있어요. 누르면 바로 보충해요.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        store.lastMakeUpNotified = key
    }

    /** 리딤 코드를 받았거나, 로그인 문제로 입력하지 못했을 때만 알린다. 같은 내용은 하루 한 번. */
    @SuppressLint("MissingPermission") // canNotify에서 알림 권한을 확인한다.
    fun notifyRedeem(context: Context, store: Store, report: RedeemReport) {
        val (title, text) = when {
            report.redeemed.isNotEmpty() && !AppSettings(context).notifyRedeem -> return
            report.redeemed.isNotEmpty() -> "리딤 코드 ${report.redeemed.size}개를 받았어요" +
                (if (report.redeemedRoles > 1) " (캐릭터 ${report.redeemedRoles}명)" else "") to
                "${report.redeemed.joinToString(", ")} · 게임 우편함에서 받으세요."
            report.stopReason != null && report.attempts.isNotEmpty() -> "리딤 코드를 입력하지 못했어요" to report.stopReason
            else -> return
        }
        val key = "$title:${Store.checkInDay()}"
        if (key == store.lastRedeemNotified || !canNotify(context)) return
        val id = idFor(ID_REDEEM, store)
        val pending = PendingIntent.getActivity(
            context, id, MainActivity.accountIntent(context, store.accountId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, channel(context, CHANNEL_REDEEM))
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(who(context, store) + title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        store.lastRedeemNotified = key
    }

    /** 로그인 만료 2주 전 예고. 누르면 로그인 화면이 열린다. 로그인 한 번에 한 번. */
    @SuppressLint("MissingPermission") // canNotify에서 알림 권한을 확인한다.
    fun notifyLoginExpiry(context: Context, store: Store) {
        val expiresAt = store.loginExpiresAt ?: return
        if (!canNotify(context)) return
        val id = idFor(ID_LOGIN_EXPIRY, store)
        val pending = PendingIntent.getActivity(
            context, id, LoginActivity.loginIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val day = java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREA).format(java.util.Date(expiresAt))
        val text = "${day}에 로그인한 지 1년이 돼서 출석과 리딤 코드 입력이 멈춰요. 누르면 지금 다시 로그인할 수 있어요."
        val notification = Notification.Builder(context, channel(context, CHANNEL))
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(who(context, store) + "호요랩 로그인이 곧 만료돼요")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        store.loginExpiryNotifiedFor = store.loginAt
    }

    /** 레진이 기준만큼 찼을 때, 파견이 모두 끝났을 때, 주전자 동전이 가득 찼을 때. 누르면 앱을 연다. */
    @SuppressLint("MissingPermission") // canNotify에서 알림 권한을 확인한다.
    fun notifyNote(context: Context, store: Store, note: DailyNote, alerts: NoteAlerts, target: Int) {
        if (!canNotify(context)) return
        fun post(kind: Int, title: String, text: String) {
            val id = idFor(kind, store)
            val pending = PendingIntent.getActivity(
                context, id, MainActivity.accountIntent(context, store.accountId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 레진·파견 알림을 받으면 대개 바로 게임을 켜므로 '원신 켜기' 버튼을 붙인다.
            val play = PendingIntent.getActivity(
                context, id + 1, Game.launchIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, channel(context, CHANNEL_NOTES))
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(who(context, store) + title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .addAction(Notification.Action.Builder(null, "원신 켜기", play).build())
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        }
        if (alerts.resin) {
            if (target >= note.maxResin) {
                post(ID_RESIN, "레진이 가득 찼어요", "레진 ${note.resin}/${note.maxResin} · 더 쌓이지 않으니 써 주세요.")
            } else {
                val left = note.resinFullAt - note.at
                post(ID_RESIN, "레진이 ${target}개 찼어요",
                    "지금 ${note.resin}/${note.maxResin} · ${Notes.duration(left)} 뒤 가득 차요 (${Notes.clock(note.resinFullAt)})")
            }
        }
        if (alerts.expeditions) {
            post(ID_EXPEDITIONS, "탐사 파견이 모두 끝났어요", "${note.expeditionsTotal}명 모두 돌아왔어요. 보상을 받고 다시 보내 주세요.")
        }
        if (alerts.coin) {
            post(ID_HOME_COIN, "주전자 동전이 가득 찼어요", "동전 ${"%,d".format(note.homeCoin)}/${"%,d".format(note.maxHomeCoin)} · 더 쌓이지 않으니 받아 주세요.")
        }
    }

    /** 계정이 여럿이면 어느 계정 알림인지 제목 앞에 붙인다. */
    private fun who(context: Context, store: Store) =
        if (AppSettings(context).accounts().size > 1) "${store.displayName} · " else ""

    /** 계정마다 알림이 서로 덮어쓰지 않도록 종류와 계정으로 ID를 만든다. */
    private fun idFor(kind: Int, store: Store) = kind * 10_000 + store.notificationSalt

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}
