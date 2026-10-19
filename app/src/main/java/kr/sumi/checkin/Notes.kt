package kr.sumi.checkin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 호요랩 실시간 메모 한 번. 남은 시간은 받은 시각(at, 서버 기준 ms)부터 센 초.
 * 레진은 8분에 하나씩 차므로 한 번 받아 두면 언제 몇 개가 되는지 계산할 수 있다.
 */
data class DailyNote(
    val at: Long,
    val resin: Int,
    val maxResin: Int,
    val resinFullSec: Long,
    val expeditionsDone: Int,
    val expeditionsTotal: Int,
    /** 진행 중인 파견이 모두 끝나기까지 남은 초. 모두 끝났으면 0. */
    val expeditionsLeftSec: Long,
    val homeCoin: Int,
    val maxHomeCoin: Int,
    val homeCoinFullSec: Long,
    val commissionsDone: Int,
    val commissionsTotal: Int,
    val commissionRewardClaimed: Boolean,
) {
    val resinFullAt get() = at + resinFullSec * 1000
    val expeditionsDoneAt get() = at + expeditionsLeftSec * 1000
    val homeCoinFullAt get() = at + homeCoinFullSec * 1000

    /** now 시각의 레진 추정. 가득 찬 뒤에는 늘지 않고, 이미 넘쳐 있으면(농축 레진 등) 그대로. */
    fun resinAt(now: Long): Int {
        if (resin >= maxResin) return resin
        val left = resinFullAt - now
        if (left <= 0) return maxResin
        return (maxResin - ((left + Notes.RESIN_MS - 1) / Notes.RESIN_MS).toInt()).coerceIn(resin, maxResin)
    }

    /** 레진이 target개가 되는 시각. 이미 그만큼 있으면 받은 시각. */
    fun resinReachAt(target: Int): Long {
        if (resin >= target) return at
        return maxOf(at, resinFullAt - (maxResin - target).coerceAtLeast(0) * Notes.RESIN_MS)
    }

    fun toJson(): String = JSONObject()
        .put("at", at).put("resin", resin).put("maxResin", maxResin).put("resinFullSec", resinFullSec)
        .put("expeditionsDone", expeditionsDone).put("expeditionsTotal", expeditionsTotal).put("expeditionsLeftSec", expeditionsLeftSec)
        .put("homeCoin", homeCoin).put("maxHomeCoin", maxHomeCoin).put("homeCoinFullSec", homeCoinFullSec)
        .put("commissionsDone", commissionsDone).put("commissionsTotal", commissionsTotal)
        .put("commissionRewardClaimed", commissionRewardClaimed).toString()

    companion object {
        fun fromJson(raw: String): DailyNote? = try {
            val j = JSONObject(raw)
            DailyNote(
                j.getLong("at"), j.getInt("resin"), j.getInt("maxResin"), j.getLong("resinFullSec"),
                j.getInt("expeditionsDone"), j.getInt("expeditionsTotal"), j.getLong("expeditionsLeftSec"),
                j.getInt("homeCoin"), j.getInt("maxHomeCoin"), j.getLong("homeCoinFullSec"),
                j.getInt("commissionsDone"), j.getInt("commissionsTotal"), j.getBoolean("commissionRewardClaimed"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** 이미 알린 상태. 레진을 쓰거나 파견을 다시 보내면 풀린다(같은 일을 두 번 알리지 않는다). */
data class NoteFlags(val resin: Boolean = false, val expeditions: Boolean = false, val coin: Boolean = false)

/** 이번에 알릴 것. */
data class NoteAlerts(val resin: Boolean, val expeditions: Boolean, val coin: Boolean) {
    val any get() = resin || expeditions || coin
}

sealed class NoteResult {
    data class Ok(val note: DailyNote) : NoteResult()
    object NoCharacter : NoteResult()
    object LoginExpired : NoteResult()
    data class Failed(val message: String) : NoteResult()
}

/**
 * 실시간 메모(레진·탐사 파견·주전자 동전·일일 의뢰)를 받아 와서, 레진이 정한 개수가 되는 때 같은 시각에 알린다.
 * 매번 묻지 않고 받은 값으로 다음 알림 시각을 계산해 알람을 걸고, 그때 다시 받아 확인한다(그사이 레진을 썼을 수 있다).
 */
object Notes {
    const val RESIN_MS = 8 * 60_000L

    private const val NOTE_URL = "https://sg-public-api.hoyolab.com/event/game_record/genshin/api/dailyNote"
    private const val SWITCH_URL = "https://bbs-api-os.hoyolab.com/game_record/card/wapi/changeDataSwitch"
    /** 호요랩 웹이 전적 기록 요청에 붙이는 서명(DS)의 소금값. genshin.py 등 공개 라이브러리와 같은 값. */
    private const val DS_SALT = "6s25p5ox5y14umn1p61aqyyvbvvl3lrt"
    /** 실시간 메모가 꺼져 있음. */
    private const val NOT_PUBLIC = 10102
    /** 호요랩이 사람 확인(캡차)을 요구함. */
    private val CAPTCHA = setOf(1034, 5003, 10035, 10041)
    private val LOGIN = setOf(-100, 10001, 10103)
    /** 다음 알림 시각을 모를 때(파견도 레진도 기다릴 게 없을 때)도 이만큼 뒤에는 다시 본다. */
    private const val IDLE_RECHECK_MS = 6 * 60 * 60_000L
    private const val MIN_DELAY_MS = 60_000L

    /** 남은 시간을 '7시간 36분', '36분'처럼. */
    fun duration(ms: Long): String {
        val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(0)
        val days = minutes / (24 * 60)
        val hours = minutes / 60 % 24
        val mins = minutes % 60
        return when {
            days > 0 -> "${days}일 ${hours}시간"
            hours > 0 && mins > 0 -> "${hours}시간 ${mins}분"
            hours > 0 -> "${hours}시간"
            else -> "${mins}분"
        }
    }

    /** 서버 기준 시각을 폰 시간대의 '오후 3:20'으로. 오늘이 아니면 '내일 오전 9:05'. */
    fun clock(serverMs: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val at = java.time.Instant.ofEpochMilli(serverMs).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(ServerClock.now()).atZone(zone).toLocalDate()
        val day = when (at.toLocalDate()) {
            today -> ""
            today.plusDays(1) -> "내일 "
            else -> "${at.monthValue}월 ${at.dayOfMonth}일 "
        }
        val half = if (at.hour < 12) "오전" else "오후"
        val hour = (at.hour % 12).let { if (it == 0) 12 else it }
        return "%s%s %d:%02d".format(day, half, hour, at.minute)
    }

    /** 요청 서명: "시각,무작위 6글자,md5(salt=…&t=…&r=…)". */
    internal fun ds(t: Long = System.currentTimeMillis() / 1000, r: String = randomLetters()): String {
        val md5 = MessageDigest.getInstance("MD5").digest("salt=$DS_SALT&t=$t&r=$r".toByteArray())
        return "$t,$r," + md5.joinToString("") { "%02x".format(it) }
    }

    private fun randomLetters() = buildString {
        val letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        repeat(6) { append(letters[Random.nextInt(letters.length)]) }
    }

    private fun headers() = mapOf(
        "DS" to ds(),
        "x-rpc-app_version" to "1.5.0",
        "x-rpc-client_type" to "5",
        "x-rpc-language" to CheckInClient.LANG,
    )

    internal fun parse(json: JSONObject, at: Long): DailyNote? {
        val d = json.optJSONObject("data") ?: return null
        val expeditions = d.optJSONArray("expeditions")
        val left = (0 until (expeditions?.length() ?: 0)).maxOfOrNull {
            expeditions!!.getJSONObject(it).optString("remained_time").toLongOrNull() ?: 0
        } ?: 0
        return DailyNote(
            at = at,
            resin = d.optInt("current_resin"),
            maxResin = d.optInt("max_resin", 200),
            resinFullSec = d.optString("resin_recovery_time").toLongOrNull() ?: 0,
            expeditionsDone = (0 until (expeditions?.length() ?: 0)).count { expeditions!!.getJSONObject(it).optString("status") == "Finished" },
            expeditionsTotal = d.optInt("current_expedition_num"),
            expeditionsLeftSec = left,
            homeCoin = d.optInt("current_home_coin"),
            maxHomeCoin = d.optInt("max_home_coin"),
            homeCoinFullSec = d.optString("home_coin_recovery_time").toLongOrNull() ?: 0,
            commissionsDone = d.optInt("finished_task_num"),
            commissionsTotal = d.optInt("total_task_num"),
            commissionRewardClaimed = d.optBoolean("is_extra_task_reward_received"),
        )
    }

    /** 알림 기준 개수. 0이나 최대치 이상이면 '가득 찼을 때'. */
    internal fun target(note: DailyNote, threshold: Int) =
        if (threshold <= 0 || threshold >= note.maxResin) note.maxResin else threshold

    /** 지금 알릴 것과, 알린 뒤의 상태. 기준 아래로 내려가면(레진을 썼으면) 다시 알릴 수 있게 푼다. */
    internal fun evaluate(note: DailyNote, flags: NoteFlags, resinAlert: Boolean, threshold: Int, extra: Boolean): Pair<NoteAlerts, NoteFlags> {
        val resinDue = note.resin >= target(note, threshold)
        val expeditionsDue = note.expeditionsTotal > 0 && note.expeditionsDone >= note.expeditionsTotal
        val coinDue = note.maxHomeCoin > 0 && note.homeCoin >= note.maxHomeCoin
        val alerts = NoteAlerts(
            resin = resinAlert && resinDue && !flags.resin,
            expeditions = extra && expeditionsDue && !flags.expeditions,
            coin = extra && coinDue && !flags.coin,
        )
        val next = NoteFlags(
            resin = resinDue && (flags.resin || alerts.resin),
            expeditions = expeditionsDue && (flags.expeditions || alerts.expeditions),
            coin = coinDue && (flags.coin || alerts.coin),
        )
        return alerts to next
    }

    /** 다음에 다시 받아 볼 시각. 알릴 일이 생길 가장 이른 때, 없으면 null. */
    internal fun nextAlertAt(note: DailyNote, flags: NoteFlags, resinAlert: Boolean, threshold: Int, extra: Boolean): Long? = listOfNotNull(
        note.resinReachAt(target(note, threshold)).takeIf { resinAlert && !flags.resin },
        note.expeditionsDoneAt.takeIf { extra && !flags.expeditions && note.expeditionsTotal > note.expeditionsDone },
        note.homeCoinFullAt.takeIf { extra && !flags.coin && note.maxHomeCoin > 0 && note.homeCoin < note.maxHomeCoin },
    ).minOrNull()

    /** 실시간 메모를 볼 캐릭터: 리딤 캐릭터, 없으면 대표 캐릭터. 둘 다 아직 모르면 불러와 대표 캐릭터로 저장한다. */
    private fun roleOf(store: Store, client: CheckInClient): GameRole? {
        store.redeemRole?.let { return it }
        store.accountRole?.let { return it }
        return GameRole.representative(Redeem.roles(client), null)?.also { store.accountRole = it }
    }

    /** 받아 오기만 한다. 실시간 메모가 꺼져 있으면 한 번 켜고 다시 받는다. */
    @Throws(IOException::class)
    fun fetch(store: Store): NoteResult {
        val client = CheckInClient(store.cookie ?: return NoteResult.LoginExpired)
        val role = roleOf(store, client) ?: return NoteResult.NoCharacter
        val url = "$NOTE_URL?server=${role.region}&role_id=${role.uid}"
        var json = client.get(url, headers())
        if (json.optInt("retcode") == NOT_PUBLIC) {
            client.post(SWITCH_URL, """{"switch_id":3,"is_public":true,"game_id":"2"}""", headers())
            Thread.sleep(CheckInClient.REQUEST_GAP_MS)
            json = client.get(url, headers())
        }
        val code = json.optInt("retcode", Int.MIN_VALUE)
        return when {
            code == 0 -> parse(json, ServerClock.now())?.let { NoteResult.Ok(it) } ?: NoteResult.Failed("받은 내용을 읽지 못했어요")
            code in LOGIN || code in CheckInClient.NOT_LOGGED_IN -> NoteResult.LoginExpired
            code == NOT_PUBLIC -> NoteResult.Failed("호요랩 전적 기록에서 '실시간 메모'를 켜 주세요")
            code in CAPTCHA -> NoteResult.Failed("호요랩이 확인을 요구해요. 잠시 뒤 다시 시도할게요")
            else -> NoteResult.Failed("${json.optString("message")} (retcode $code)")
        }
    }

    /** 받아 와서 저장하고, 지금 알릴 게 있으면 알린다. 화면 표시용 문제 안내도 남긴다. */
    @Throws(IOException::class)
    fun refresh(context: Context, store: Store): NoteResult {
        val result = fetch(store)
        val settings = AppSettings(context)
        when (result) {
            is NoteResult.Ok -> {
                store.note = result.note
                store.noteProblem = null
                val (alerts, flags) = evaluate(result.note, store.noteFlags, settings.resinAlert, settings.resinThreshold, settings.notesExtra)
                store.noteFlags = flags
                if (alerts.any) Notifier.notifyNote(context, store, result.note, alerts, target(result.note, settings.resinThreshold))
            }
            NoteResult.NoCharacter -> store.noteProblem = "원신 캐릭터를 찾지 못했어요"
            NoteResult.LoginExpired -> store.noteProblem = "로그인이 풀렸어요. 다시 로그인해 주세요"
            is NoteResult.Failed -> store.noteProblem = result.message
        }
        return result
    }

    fun enabled(settings: AppSettings) = settings.resinAlert || settings.notesExtra

    /** 모든 계정을 받아 보고 다음 알람을 건다. 알림이 모두 꺼져 있으면 알람만 지운다. */
    fun checkAll(context: Context) {
        val settings = AppSettings(context)
        if (!enabled(settings)) return cancel(context)
        var failed = false
        for (store in settings.accounts()) {
            try {
                refresh(context, store)
            } catch (e: Exception) {
                // 네트워크 오류나 이상한 응답(JSON이 아님 등). 이 계정은 넘기고 조금 뒤 다시 본다.
                failed = true
            }
            Thread.sleep(CheckInClient.REQUEST_GAP_MS)
        }
        schedule(context, retrySoon = failed)
    }

    /** 저장된 메모로 가장 이른 알림 시각을 구해 알람을 건다. 네트워크가 실패했으면 15분 뒤에 다시 본다. */
    fun schedule(context: Context, retrySoon: Boolean = false) {
        val settings = AppSettings(context)
        if (!enabled(settings) || !settings.hasAccounts) return cancel(context)
        val now = ServerClock.now()
        val next = settings.accounts().mapNotNull { store ->
            store.note?.let { nextAlertAt(it, store.noteFlags, settings.resinAlert, settings.resinThreshold, settings.notesExtra) }
        }.minOrNull()
        var at = minOf(next ?: (now + IDLE_RECHECK_MS), now + IDLE_RECHECK_MS)
        if (retrySoon) at = minOf(at, now + 15 * 60_000L)
        val trigger = SystemClock.elapsedRealtime() + maxOf(at - now, MIN_DELAY_MS)
        val alarms = context.getSystemService(AlarmManager::class.java)
        // 몇 분 늦어도 괜찮은 알림이라 정확한 알람 대신 절전 중에도 울리는 알람을 쓴다.
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pendingIntent(context))
    }

    fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 1, Intent(context, NotesAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** 알림 시각이 되면 실시간 메모를 다시 받아 확인하고 다음 알람을 건다. */
class NotesAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                Notes.checkAll(app)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
