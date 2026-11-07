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
    private const val CHANNEL_RERUN = "rerun"
    private const val ID_REDEEM = 3
    private const val ID_CHECK_IN = 1
    private const val ID_MAKE_UP = 2
    private const val ID_LOGIN_EXPIRY = 4
    private const val ID_RESIN = 5
    private const val ID_EXPEDITIONS = 6
    private const val ID_HOME_COIN = 7
    private const val ID_RERUN = 8
    private const val ID_NEW_CODES = 9

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.core_channel_checkin), NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_MAKE_UP, context.getString(R.string.core_channel_make_up), NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_REDEEM, context.getString(R.string.core_channel_redeem), NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_NOTES, context.getString(R.string.core_channel_notes), NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_RERUN, context.getString(R.string.core_channel_rerun), NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_QUIET, context.getString(R.string.core_channel_quiet), NotificationManager.IMPORTANCE_LOW))
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
        val title = who(context, store) + context.getString(when (outcome) {
            is Outcome.Signed -> R.string.core_notif_signed
            Outcome.LoginExpired -> R.string.core_notif_login
            Outcome.CaptchaRequired -> R.string.core_notif_captcha
            Outcome.NotConfirmed -> R.string.core_notif_not_confirmed
            else -> R.string.core_notif_failed
        })
        val notification = Notification.Builder(context, channel(context, CHANNEL))
            .setSmallIcon(R.drawable.ic_notify)
            .favorite(context, title, CheckIn.describe(outcome), if (outcome is Outcome.Signed) Mood.GREET else Mood.WORRY, CheckIn.brief(outcome))
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
            .favorite(
                context, who(context, store) + context.getString(R.string.core_notif_makeup_title),
                context.resources.getQuantityString(R.plurals.core_notif_makeup_text, missed, missed), Mood.CHEER,
                context.resources.getQuantityString(R.plurals.core_notif_makeup_brief, missed, missed),
            )
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
            report.redeemed.isNotEmpty() -> (if (report.redeemedRoles > 1)
                context.resources.getQuantityString(R.plurals.core_notif_redeem_title_roles, report.redeemed.size, report.redeemed.size, report.redeemedRoles)
            else context.resources.getQuantityString(R.plurals.core_notif_redeem_title, report.redeemed.size, report.redeemed.size)) to
                context.getString(R.string.core_notif_redeem_text, report.redeemed.joinToString(", "))
            report.stopReason != null && report.attempts.isNotEmpty() -> context.getString(R.string.core_notif_redeem_failed) to report.stopReason
            else -> return
        }
        val brief = if (report.redeemed.isEmpty()) text
        else if (report.redeemed.size > 1) context.getString(R.string.core_notif_redeem_brief_more, report.redeemed.first(), report.redeemed.size - 1)
        else context.getString(R.string.core_notif_redeem_brief, report.redeemed.first())
        // 하루에 여러 번 돌 수 있어(새 코드가 올라올 때마다) 받은 코드까지 넣어 같은 알림만 거른다.
        val key = "$title:${Store.checkInDay()}:${report.redeemed.joinToString(",")}"
        if (key == store.lastRedeemNotified || !canNotify(context)) return
        val id = idFor(ID_REDEEM, store)
        val pending = PendingIntent.getActivity(
            context, id, MainActivity.accountIntent(context, store.accountId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, channel(context, CHANNEL_REDEEM))
            .setSmallIcon(R.drawable.ic_notify)
            .favorite(context, who(context, store) + title, text, if (report.redeemed.isNotEmpty()) Mood.REWARD else Mood.WORRY, brief)
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
        val day = java.text.SimpleDateFormat(context.getString(R.string.core_date_month_day_pattern), context.resources.configuration.locales[0])
            .format(java.util.Date(expiresAt))
        val text = context.getString(R.string.core_notif_expiry_text, day)
        val notification = Notification.Builder(context, channel(context, CHANNEL))
            .setSmallIcon(R.drawable.ic_notify)
            .favorite(
                context, who(context, store) + context.getString(R.string.core_notif_expiry_title), text, Mood.WORRY,
                context.getString(R.string.core_notif_expiry_brief, day),
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        store.loginExpiryNotifiedFor = store.loginAt
    }

    /** 레진이 기준만큼 찼을 때, 파견이 모두 끝났을 때, 주전자 동전이 가득 찼을 때. 누르면 앱을 연다. */
    @SuppressLint("MissingPermission") // canNotify에서 알림 권한을 확인한다.
    fun notifyNote(context: Context, store: Store, note: DailyNote, alerts: NoteAlerts) {
        if (!canNotify(context)) return
        fun post(kind: Int, title: String, text: String, brief: String) {
            val id = idFor(kind, store)
            val pending = PendingIntent.getActivity(
                context, id, MainActivity.accountIntent(context, store.accountId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 레진·파견 알림을 받으면 대개 바로 게임을 켜므로 '원신 켜기' 버튼을 붙인다.
            val play = Game.launchIntent(context)?.let {
                PendingIntent.getActivity(
                    context, id + 1, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val notification = Notification.Builder(context, channel(context, CHANNEL_NOTES))
                .setSmallIcon(R.drawable.ic_notify)
                .favorite(context, who(context, store) + title, text, Mood.CHEER, brief)
                .setContentIntent(pending)
                .apply { if (play != null) addAction(Notification.Action.Builder(null, context.getString(R.string.core_notif_play), play).build()) }
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        }
        // 제목은 기준이 아니라 지금 실제 개수로 쓴다(처음 받았을 때 이미 기준을 넘어 있을 수 있다).
        if (alerts.resin) {
            if (note.resin >= note.maxResin) {
                post(ID_RESIN, context.getString(R.string.core_notif_resin_full_title),
                    context.getString(R.string.core_notif_resin_full_text, note.resin, note.maxResin),
                    context.getString(R.string.core_notif_resin_full_brief, note.resin, note.maxResin))
            } else {
                val left = note.resinFullAt - note.at
                post(ID_RESIN, context.getString(R.string.core_notif_resin_title, note.resin),
                    context.getString(R.string.core_notif_resin_text, note.resin, note.maxResin, Notes.duration(left), Notes.clock(note.resinFullAt)),
                    context.getString(R.string.core_notif_resin_brief, note.resin, note.maxResin, Notes.clock(note.resinFullAt)))
            }
        }
        if (alerts.expeditions) {
            post(ID_EXPEDITIONS, context.getString(R.string.core_notif_expeditions_title),
                context.getString(R.string.core_notif_expeditions_text, note.expeditionsTotal),
                context.getString(R.string.core_notif_expeditions_brief, note.expeditionsTotal))
        }
        if (alerts.coin) {
            post(ID_HOME_COIN, context.getString(R.string.core_notif_coin_title),
                context.getString(R.string.core_notif_coin_text, "%,d".format(note.homeCoin), "%,d".format(note.maxHomeCoin)),
                context.getString(R.string.core_notif_coin_brief, "%,d".format(note.homeCoin)))
        }
    }

    /** 기다리던 캐릭터의 픽업이 열리거나(예정) 진행 중일 때. 누르면 복각표를 연다. 캐릭터·픽업마다 한 번(Reruns가 기억). */
    @SuppressLint("MissingPermission") // canNotify에서 알림 권한을 확인한다.
    fun notifyRerun(context: Context, c: RerunCharacter, b: Banner, today: java.time.LocalDate) {
        if (!canNotify(context)) return
        fun day(d: java.time.LocalDate) = Notes.monthDay(d)
        // 한국어 문구는 한국어 이름(ko), 영어 문구는 영어 이름(name)을 쓴다.
        val (title, period) = if (b.start.isAfter(today)) {
            context.getString(R.string.core_notif_rerun_soon_title, c.ko, c.name) to
                context.getString(R.string.core_notif_rerun_soon_text, day(b.start), day(b.end))
        } else {
            context.getString(R.string.core_notif_rerun_now_title, c.ko, c.name) to context.getString(R.string.core_notif_rerun_now_text, day(b.end))
        }
        val text = if (b.chronicled) context.getString(R.string.core_notif_rerun_chronicled, period) else period
        val id = ID_RERUN * 10_000 + (c.name.hashCode() and 0x1FFF)
        val pending = PendingIntent.getActivity(
            context, id, RerunActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, channel(context, CHANNEL_RERUN))
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    /** 로그인 전 새 리딤 코드 알림. 누르면 앱의 코드 목록(입력 페이지로 가는 줄)을 연다. */
    @SuppressLint("MissingPermission") // canNotify에서 알림 권한을 확인한다.
    fun notifyNewCodes(context: Context, codes: List<PublicCode>) {
        if (!canNotify(context) || codes.isEmpty()) return
        val title = context.resources.getQuantityString(
            if (codes.any { it.official }) R.plurals.core_notif_new_codes_live else R.plurals.core_notif_new_codes, codes.size, codes.size,
        )
        val text = codes.joinToString("\n") { c -> c.code + (PublicCodes.rewardText(c.reward)?.let { " · $it" } ?: "") }
        val pending = PendingIntent.getActivity(
            context, ID_NEW_CODES, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, channel(context, CHANNEL_REDEEM))
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text.substringBefore("\n").let { if (codes.size > 1) context.getString(R.string.core_notif_new_codes_more, it) else it })
            .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.core_notif_new_codes_big, text)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_NEW_CODES, notification)
    }

    /** 알림에 붙일 최애 대사의 분위기. 제목 앞부분으로 위키 대사를 고른다. */
    enum class Mood { GREET, REWARD, WORRY, CHEER }

    /**
     * 제목과 본문을 넣는다. 접힌 알림은 한 줄에 들어가는 짧은 문구([brief]), 펼치면 자세한 본문([text]).
     * 로비형을 쓰고 최애가 있으면 최애 그림과 상황에 맞는 대사 한 줄을 붙인다.
     * 사이트가 얼굴 위치를 찾아 둔 캐릭터는 오른쪽에 큰 그림(접힌 알림에도 보인다), 아니면 작은 얼굴 동그라미.
     */
    private fun Notification.Builder.favorite(context: Context, title: String, text: String, mood: Mood, brief: String = text): Notification.Builder {
        setContentTitle(title)
        setContentText(brief)
        val look = Lobby.favoriteLook(context)
        val fav = AppSettings(context).favorite
        val line = if (look == null || fav == null) null else {
            val voices = Lobby.unlockedVoices(context, fav.name)
            when (mood) {
                Mood.GREET -> Lobby.greetingLine(voices.filter { it.text.length <= 70 }, fav.name, java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
                Mood.REWARD -> Lobby.pick(voices, "보물상자 오픈").filter { it.text.length <= 70 }.randomOrNull()
                Mood.WORRY -> Lobby.pick(voices, "동료 HP 감소", "HP 부족").filter { it.text.length <= 70 }.randomOrNull()
                Mood.CHEER -> Lobby.pick(voices, "파티 가입").filter { it.text.length <= 70 }.randomOrNull()
            }
        }
        val quote = if (line != null) "${fav!!.name} 「${line.text}」" else null
        val art = if (look != null && fav != null) Lobby.cachedNotifArt(context, fav.name) else null
        if (art != null) {
            setStyle(Notification.DecoratedCustomViewStyle())
            setCustomContentView(artViews(context, R.layout.notification_art, title, brief, null, art.small))
            setCustomBigContentView(artViews(context, R.layout.notification_art_big, title, text, quote, art.big))
            return this
        }
        look?.second?.let { setLargeIcon(it) }
        setStyle(Notification.BigTextStyle().bigText(if (quote != null) "$quote\n$text" else text))
        return this
    }

    /** 최애 그림 알림의 본문(왼쪽 글, 오른쪽 그림). 글자 색은 시스템 알림 글꼴을 따라 밝은·어두운 화면 모두 맞는다. */
    internal fun artViews(context: Context, layout: Int, title: String, text: String, quote: String?, art: android.graphics.Bitmap) =
        android.widget.RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.notifTitle, title)
            setTextViewText(R.id.notifText, text)
            if (quote != null) {
                setTextViewText(R.id.notifQuote, quote)
                setViewVisibility(R.id.notifQuote, android.view.View.VISIBLE)
            }
            setImageViewBitmap(R.id.notifArt, art)
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
