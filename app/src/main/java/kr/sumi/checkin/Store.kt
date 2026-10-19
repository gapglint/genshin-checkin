package kr.sumi.checkin

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** SharedPreferences를 고치고 저장한다(androidx.core의 edit { }와 같은 모양, 라이브러리 없이). */
internal inline fun SharedPreferences.edit(commit: Boolean = false, block: SharedPreferences.Editor.() -> Unit) {
    val editor = edit()
    editor.block()
    if (commit) editor.commit() else editor.apply()
}

/**
 * 계정 하나의 저장소: 쿠키, 출석 기록, 보충·리딤 상태.
 * 계정마다 다른 파일(hoyo_acc_<HoYoLAB UID>)에 두고, 다른 앱은 읽을 수 없다. 매니페스트에서 백업도 막아 두었다.
 */
class Store(private val context: Context, val accountId: String) {
    private val prefs = context.getSharedPreferences(fileName(accountId), Context.MODE_PRIVATE)

    var cookie: String?
        get() = prefs.getString("cookie", null)
        set(value) = prefs.edit { putString("cookie", value) }

    var lastResult: String?
        get() = prefs.getString("last_result", null)
        set(value) = prefs.edit { putString("last_result", value) }

    /** 오늘(서버 기준) 출석에서 생긴 문제 안내. 출석이 확인되면 지운다. */
    var lastProblem: String?
        get() = prefs.getString("last_problem", null)
        set(value) = prefs.edit { putString("last_problem", value) }

    var lastProblemDay: String?
        get() = prefs.getString("last_problem_day", null)
        set(value) = prefs.edit { putString("last_problem_day", value) }

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

    val signedToday get() = signedDay == checkInDay().toString()

    /** 오늘 받은 출첵 보상 이름. 화면에 보여 준다. */
    var lastReward: String?
        get() = prefs.getString("last_reward", null)
        set(value) = prefs.edit { putString("last_reward", value) }

    /** 보충 출석이 가능하다고 확인한 날과 그때 놓친 날 수. 화면의 보충 안내에 쓴다. */
    var makeUpDay: String?
        get() = prefs.getString("make_up_day", null)
        set(value) = prefs.edit { putString("make_up_day", value) }

    /** 출석 보상 달력: 이번 달 보상 목록, 놓친 날 수, 받아 온 날(서버 날짜). */
    var calendarAwards: List<CalendarSlot>
        get() = prefs.getString("calendar_awards", null)?.let { CheckInCalendar.fromJson(it) } ?: emptyList()
        set(value) = prefs.edit { putString("calendar_awards", CheckInCalendar.toJson(value)) }
    var calendarMissed: Int
        get() = prefs.getInt("calendar_missed", 0)
        set(value) = prefs.edit { putInt("calendar_missed", value) }
    var calendarDay: String?
        get() = prefs.getString("calendar_day", null)
        set(value) = prefs.edit { putString("calendar_day", value) }

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

    /** 리딤 보상을 받을 캐릭터. */
    /**
     * 리딤 보상을 받을 캐릭터들. 서버가 여러 개면 여러 명을 고를 수 있고, 새 코드는 캐릭터마다 입력한다.
     * 예전 버전에서 하나만 골랐으면 그 캐릭터 하나로 시작한다.
     */
    var redeemRoles: List<GameRole>
        get() = prefs.getString("redeem_roles", null)?.let { raw ->
            val array = JSONArray(raw)
            (0 until array.length()).map { roleOf(array.getJSONObject(it)) }
        } ?: listOfNotNull(getRole("redeem_role"))
        set(value) = prefs.edit { putString("redeem_roles", JSONArray(value.map { roleJson(it) }).toString()) }

    /** 첫 번째로 고른 캐릭터(하나만 필요한 곳용). */
    val redeemRole: GameRole? get() = redeemRoles.firstOrNull()

    /** 화면에 닉네임을 보여 줄 대표 캐릭터. */
    var accountRole: GameRole?
        get() = getRole("account_role")
        set(value) = putRole("account_role", value)

    /**
     * 그 캐릭터로 한 번 입력해 본 코드. 다시 입력하지 않는다. 코드는 서버(UID)마다 따로 쓸 수 있어 캐릭터별로 기억한다.
     * 예전 버전은 계정 하나에 목록 하나였으므로, 그때 고른 캐릭터는 그 목록을 이어 쓴다.
     */
    fun redeemTried(role: GameRole): Set<String> {
        val key = "redeem_tried_${role.uid}"
        if (prefs.contains(key)) return prefs.getStringSet(key, emptySet())!!.toSet()
        return if (role.uid == getRole("redeem_role")?.uid) prefs.getStringSet("redeem_tried", emptySet())!!.toSet() else emptySet()
    }

    fun setRedeemTried(role: GameRole, codes: Set<String>) = prefs.edit { putStringSet("redeem_tried_${role.uid}", codes) }

    /** 이미 코드를 찾아본 커뮤니티 글 ID. */
    var redeemScannedPosts: Set<String>
        get() = prefs.getStringSet("redeem_scanned_posts", emptySet())!!.toSet()
        set(value) = prefs.edit { putStringSet("redeem_scanned_posts", value) }

    var lastRedeemAt: Long
        get() = prefs.getLong("last_redeem_at", 0)
        set(value) = prefs.edit { putLong("last_redeem_at", value) }

    var lastRedeemSummary: String?
        get() = prefs.getString("last_redeem_summary", null)
        set(value) = prefs.edit { putString("last_redeem_summary", value) }

    /** 이 앱이 받은 리딤 코드 기록(최신이 앞). 호요랩에는 리딤 내역 조회가 없어 직접 남긴다. */
    var redeemLog: List<RedeemedCode>
        get() = prefs.getString("redeem_log", null)?.let { raw ->
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getJSONObject(it) }.map {
                RedeemedCode(it.optString("code"), it.optLong("at"), it.optString("reward").ifEmpty { null }, it.optString("role").ifEmpty { null })
            }
        } ?: emptyList()
        set(value) = prefs.edit {
            putString("redeem_log", JSONArray(value.map {
                JSONObject().put("code", it.code).put("at", it.at).apply {
                    it.reward?.let { r -> put("reward", r) }
                    it.role?.let { r -> put("role", r) }
                }
            }).toString())
        }

    /** 보상 내역에서 '모두 확인'을 누를 때 가장 최근이던 출석·보충 보상의 키. 이보다 앞(최신)이면 새 보상. */
    var historySeenAwardKey: String?
        get() = prefs.getString("history_seen_award", null)
        set(value) = prefs.edit { putString("history_seen_award", value) }

    /** '모두 확인'을 누른 시각. 이후에 받은 리딤 코드가 새 보상. */
    var historySeenRedeemAt: Long
        get() = prefs.getLong("history_seen_redeem", 0)
        set(value) = prefs.edit { putLong("history_seen_redeem", value) }

    /** 메인 화면에 보일 새 보상 수(마지막으로 센 값)와 센 시각. */
    var historyUnseen: Int
        get() = prefs.getInt("history_unseen", 0)
        set(value) = prefs.edit { putInt("history_unseen", value) }

    var historyCountedAt: Long
        get() = prefs.getLong("history_counted_at", 0)
        set(value) = prefs.edit { putLong("history_counted_at", value) }

    var lastRedeemNotified: String?
        get() = prefs.getString("last_redeem_notified", null)
        set(value) = prefs.edit { putString("last_redeem_notified", value) }

    /** 이 앱에서 마지막으로 로그인한 시각. 0.9.6 전에 로그인했으면 0(모름). */
    var loginAt: Long
        get() = prefs.getLong("login_at", 0)
        set(value) = prefs.edit { putLong("login_at", value) }

    /** 로그인 만료 예정 시각. 로그인 시각을 모르면 null. */
    val loginExpiresAt: Long? get() = loginAt.takeIf { it > 0 }?.plus(LOGIN_LIFETIME_MS)

    /** 만료 예고 알림을 보낸 로그인(그때의 loginAt). 같은 로그인에는 한 번만 알린다. */
    var loginExpiryNotifiedFor: Long
        get() = prefs.getLong("login_expiry_notified", 0)
        set(value) = prefs.edit { putLong("login_expiry_notified", value) }

    /** 화면·알림에 쓸 이름: 닉네임, 없으면 UID. */
    val displayName get() = accountRole?.nickname ?: "UID $accountId"

    /** 알림 ID가 계정끼리 겹치지 않게 하는 값. */
    val notificationSalt get() = (accountId.hashCode() and 0x7fffffff) % 10_000

    private fun getRole(key: String) = prefs.getString(key, null)?.let { roleOf(JSONObject(it)) }

    private fun putRole(key: String, role: GameRole?) = prefs.edit { putString(key, role?.let { roleJson(it).toString() }) }

    private fun roleOf(j: JSONObject) =
        GameRole(j.optString("uid"), j.optString("region"), j.optString("regionName"), j.optString("nickname"), j.optInt("level"))

    private fun roleJson(role: GameRole) = JSONObject().put("uid", role.uid).put("region", role.region)
        .put("regionName", role.regionName).put("nickname", role.nickname).put("level", role.level)

    /** 이 계정의 모든 기록을 지운다. */
    fun wipe() {
        prefs.edit(commit = true) { clear() }
        context.deleteSharedPreferences(fileName(accountId))
    }

    companion object {
        private fun fileName(id: String) = "hoyo_acc_$id"

        /**
         * 호요랩 로그인 토큰(ltoken_v2)의 수명. 로그인한 때부터 1년이고, 페이지를 열어도 늘어나지 않는다(2026-10 실측).
         * 리딤 코드 입력도 같은 토큰을 쓰므로 만료되면 출석과 리딤이 함께 멈춘다.
         */
        const val LOGIN_LIFETIME_MS = 365L * 24 * 3600 * 1000

        /** 만료 며칠 전에 다시 로그인하라고 알릴지. */
        const val LOGIN_EXPIRY_WARN_MS = 14L * 24 * 3600 * 1000

        /** 지금 만료 예고 알림을 보낼 때인지. 로그인 시각을 모르거나 이 로그인에 이미 알렸으면 false. */
        internal fun loginExpiryDue(loginAt: Long, notifiedFor: Long, now: Long): Boolean =
            loginAt > 0 && notifiedFor != loginAt && now >= loginAt + LOGIN_LIFETIME_MS - LOGIN_EXPIRY_WARN_MS

        /** 서버 기준 출석 날짜. 호요랩 출첵 하루는 UTC+8 자정(한국 시간 새벽 1시)에 바뀐다. */
        fun checkInDay(): LocalDate = ServerClock.today()
    }
}

/** 모든 계정이 함께 쓰는 설정과 계정 목록. */
class AppSettings(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hoyo", Context.MODE_PRIVATE)

    init {
        migrateSingleAccount()
    }

    /** 보충 출석이 가능할 때 알릴지. */
    var makeUpAlerts: Boolean
        get() = prefs.getBoolean("make_up_alerts", true)
        set(value) = prefs.edit { putBoolean("make_up_alerts", value) }

    /** 매일 출석했을 때 알릴지(로그인·캡차 같은 문제는 이 설정과 관계없이 알린다). */
    var notifyCheckIn: Boolean
        get() = prefs.getBoolean("notify_check_in", true)
        set(value) = prefs.edit { putBoolean("notify_check_in", value) }

    /** 리딤 코드를 받았을 때 알릴지. */
    var notifyRedeem: Boolean
        get() = prefs.getBoolean("notify_redeem", true)
        set(value) = prefs.edit { putBoolean("notify_redeem", value) }

    /** 밤(오후 10시~오전 8시, 폰 시각)에는 소리·진동 없이 알림만 남길지. */
    var quietNight: Boolean
        get() = prefs.getBoolean("quiet_night", true)
        set(value) = prefs.edit { putBoolean("quiet_night", value) }

    /** 리딤 코드 자동 입력. */
    var redeemEnabled: Boolean
        get() = prefs.getBoolean("redeem_enabled", true)
        set(value) = prefs.edit { putBoolean("redeem_enabled", value) }

    /** 서버 시각 - 폰 시각(ms). 앱을 다시 켜도 서버 기준으로 계산하려고 남겨 둔다. */
    var clockOffset: Long
        get() = prefs.getLong("clock_offset", 0)
        set(value) = prefs.edit { putLong("clock_offset", value) }

    /** 다음 자동 출석 예정 시각(서버 기준 ms). 화면 표시용. */
    var nextRunAt: Long
        get() = prefs.getLong("next_run_at", 0)
        set(value) = prefs.edit { putLong("next_run_at", value) }

    /** 등록한 계정의 HoYoLAB UID, 추가한 순서대로. */
    var accountIds: List<String>
        get() = prefs.getString("account_ids", "")!!.split(",").filter { it.isNotEmpty() }
        private set(value) = prefs.edit { putString("account_ids", value.joinToString(",")) }

    /** 화면에 보이는 계정. */
    var currentId: String?
        get() = prefs.getString("current_account", null)?.takeIf { it in accountIds } ?: accountIds.firstOrNull()
        set(value) = prefs.edit { putString("current_account", value) }

    /** 로그인 정보가 있는 계정들. */
    fun accounts(): List<Store> = accountIds.map { Store(context, it) }.filter { it.cookie != null }

    fun current(): Store? = currentId?.let { Store(context, it) }?.takeIf { it.cookie != null }

    fun account(id: String): Store? = id.takeIf { it in accountIds }?.let { Store(context, it) }

    val hasAccounts get() = accounts().isNotEmpty()

    /** 계정을 추가하거나(처음 보는 UID) 다시 로그인한 쿠키로 갱신한다. 그 계정을 화면에 띄운다. */
    fun saveLogin(id: String, cookie: String): Store {
        if (id !in accountIds) accountIds = accountIds + id
        val store = Store(context, id)
        store.cookie = cookie
        store.loginAt = ServerClock.now()
        currentId = id
        return store
    }

    fun remove(id: String) {
        Store(context, id).wipe()
        accountIds = accountIds - id
        if (prefs.getString("current_account", null) == id) currentId = accountIds.firstOrNull()
    }

    /** 계정 하나만 쓰던 예전 버전의 저장소를 첫 번째 계정으로 옮긴다. */
    private fun migrateSingleAccount() {
        val oldCookie = prefs.getString("cookie", null) ?: return
        val id = LoginActivity.uidOf(oldCookie) ?: "account1"
        val shared = setOf("make_up_alerts", "redeem_enabled", "clock_offset", "next_run_at", "account_ids", "current_account")
        val target = context.getSharedPreferences("hoyo_acc_$id", Context.MODE_PRIVATE)
        target.edit(commit = true) {
            for ((key, value) in prefs.all) {
                if (key in shared) continue
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Set<*> -> putStringSet(key, value as Set<String>)
                }
            }
        }
        prefs.edit(commit = true) {
            for (key in prefs.all.keys) if (key !in shared) remove(key)
            putString("account_ids", id)
            putString("current_account", id)
        }
    }
}

/**
 * 이 앱이 받은 리딤 코드. reward는 위키의 보상 표기('Primogem*60;Mora*10000'), 모르면 null.
 * role은 받은 캐릭터('아시아 서버 · 여행자'). 예전 기록은 null.
 */
data class RedeemedCode(val code: String, val at: Long, val reward: String?, val role: String? = null)
