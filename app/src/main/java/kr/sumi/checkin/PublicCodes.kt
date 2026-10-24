package kr.sumi.checkin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

/**
 * 로그인 없이 보여 주는 리딤 코드 하나. reward는 위키 보상 표기 그대로(모르면 null). 화면에 보일 때 한국어 이름으로 바꾼다.
 * server는 쓸 수 있는 서버('전 서버', '아시아' 등), official은 호요랩 공식 목록에만 있는 코드(대개 특별 방송 코드).
 */
data class PublicCode(
    val code: String,
    val reward: String?,
    val server: String,
    val discovered: LocalDate? = null,
    val expiry: LocalDate? = null,
    val official: Boolean = false,
    /** 만료일을 모르는 채 오래된 코드(만료됐을 수 있음). */
    val old: Boolean = false,
)

/**
 * 로그인 전 화면의 '지금 쓸 수 있는 리딤 코드'. 위키와 호요랩 공식 목록(공개)만 본다.
 * 로그인 전에는 사용자의 서버를 모르니 모든 서버의 코드를 보여 주고, 줄마다 쓸 수 있는 서버를 적는다.
 * 6시간에 한 번만 받아 저장해 둔다. 누르면 공식 입력 페이지(genshin.hoyoverse.com/gift)를 코드가 채워진 채 연다.
 */
object PublicCodes {
    private const val PREFS = "public_codes"
    private const val KEY = "json_v4"
    /** 사이트가 1시간마다 정리해 두는 목록(제보된 만료 코드 제외, 한국어 보상 이름). */
    private const val SITE_URL = "https://apps.sumi.kr/checkin/data/codes.json"
    private const val REFRESH_MS = 6 * 60 * 60_000L
    private val SERVER_NAMES = mapOf("SEA" to "아시아", "NA" to "미국", "EU" to "유럽", "SAR" to "TW·HK·MO")

    fun giftUrl(code: String) = "https://genshin.hoyoverse.com/m/ko/gift?code=$code"

    /** 화면에 보일 보상 요약(아이템 이름은 한국어로, 최대 3가지). */
    fun rewardText(raw: String?) = raw?.let { if ('*' in it) RewardHistory.formatTotals(Redeem.rewardItems(it), max = 3) else it }

    fun cached(context: Context): List<PublicCode> =
        prefs(context).getString(KEY, null)?.let { runCatching { fromJson(JSONArray(it)) }.getOrNull() }.orEmpty()

    fun isFresh(context: Context) =
        prefs(context).contains(KEY) && System.currentTimeMillis() - prefs(context).getLong("checked_at", 0) < REFRESH_MS

    /**
     * 사이트의 정리된 목록을 받는다(위키에 남은 만료 코드를 걸러 둔 것). 사이트에 못 닿으면 위키와 공식 목록에서 직접 만든다.
     * 둘 다 안 되면 지난 목록을 그대로 둔다(코드가 사라졌다 생기는 일 없게).
     */
    @Throws(IOException::class)
    fun refresh(context: Context) {
        val codes = runCatching { fromSite(fetch(SITE_URL)) }.getOrNull() ?: run {
            val (wiki, official) = Redeem.publishedCodes()
            if (wiki == null) throw IOException("코드 목록을 받지 못했어요")
            from(wiki, official, ServerClock.today())
        }
        prefs(context).edit().putString(KEY, toJson(codes).toString()).putLong("checked_at", System.currentTimeMillis()).apply()
    }

    /** 사이트 codes.json의 codes 배열. 보상은 이미 한국어 요약이다. */
    internal fun fromSite(json: JSONObject): List<PublicCode> {
        val a = json.getJSONArray("codes")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            PublicCode(
                code = it.getString("code"),
                reward = it.optString("reward").takeIf { r -> r.isNotEmpty() && r != "null" },
                server = it.optString("server").ifEmpty { "전 서버" },
                discovered = it.optString("discovered").takeIf { d -> d.length == 10 }?.let(LocalDate::parse),
                expiry = it.optString("expiry").takeIf { d -> d.length == 10 }?.let(LocalDate::parse),
                official = it.optBoolean("live"),
                old = it.optBoolean("old"),
            )
        }
    }

    private fun fetch(url: String): JSONObject {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "SumiLab-Checkin/1.0 (Android)")
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 공식 목록에만 있는 코드(대개 방송 코드)를 맨 위에, 나머지는 위키 순서(새 코드가 위)대로.
     * 만료된 코드와 중국 서버 전용 코드는 뺀다. 묶음(같은 보상의 대체 코드)은 첫 코드만 보여 준다.
     */
    internal fun from(wiki: JSONObject?, official: List<String>, today: LocalDate): List<PublicCode> {
        val entries = wiki?.let { Redeem.wikiEntries(it) }.orEmpty()
            .filter { it.expiry == null || !it.expiry.isBefore(today) }
            .mapNotNull { e -> serverLabel(e.servers)?.let { e to it } }
        val known = entries.flatMap { it.first.codes }.toSet()
        val fromOfficial = official.distinct().filter { it !in known }.map { PublicCode(it, null, "전 서버", official = true) }
        val fromWiki = entries.map { (e, server) ->
            PublicCode(
                code = e.codes.first(),
                reward = e.reward,
                server = server,
                discovered = e.discovered,
                expiry = e.expiry,
            )
        }
        return (fromOfficial + fromWiki).distinctBy { it.code }
    }

    /** 위키 서버 표기를 화면 글자로. 중국 서버에서만 쓰는 코드는 null(보여 주지 않음). */
    internal fun serverLabel(servers: List<String>): String? {
        if (servers.isEmpty() || "G" in servers || "A" in servers) return "전 서버"
        val names = servers.mapNotNull { SERVER_NAMES[it] }
        return names.takeIf { it.isNotEmpty() }?.joinToString("·")
    }

    /**
     * 로그인 전에도 새 코드 알림과 기다리는 캐릭터 알림이 오도록 6시간 확인 작업과 금요일 밤 확인을 건다.
     * 로그인한 계정이 있으면 출석 예약이 같은 일을 하므로 건드리지 않는다. 둘 다 필요 없으면 끈다.
     */
    fun schedule(context: Context) {
        val settings = AppSettings(context)
        if (settings.hasAccounts) return
        if (settings.codeAlerts || Reruns.watched(context).isNotEmpty()) Jobs.schedulePeriodic(context)
        else Jobs.cancel(context, Jobs.PERIODIC)
        if (settings.codeAlerts) RedeemAlarm.schedule(context) else RedeemAlarm.cancel(context)
    }

    /**
     * 로그인 전 새 코드 알림: 목록을 새로 받고, 지난번에 본 적 없는 코드가 있으면 한 번 알린다.
     * 처음 확인할 때는 이미 있던 코드를 알리지 않고 기억만 한다. 네트워크 오류는 IOException.
     */
    @Throws(IOException::class)
    fun checkNew(context: Context) {
        refresh(context)
        val codes = cached(context)
        val prefs = prefs(context)
        val first = !prefs.contains("alerted")
        val seen = prefs.getStringSet("alerted", emptySet()).orEmpty()
        val fresh = newCodes(codes, seen, first)
        if (fresh.isNotEmpty() && AppSettings(context).codeAlerts) Notifier.notifyNewCodes(context, fresh)
        // 한쪽 출처가 잠깐 빠졌다 돌아와도 다시 알리지 않게 본 코드는 계속 기억한다(오래된 것부터 300개까지).
        prefs.edit().putStringSet("alerted", (seen + codes.map { it.code }).toList().takeLast(300).toSet()).apply()
    }

    /** 알릴 새 코드: 처음이면 없음, 아니면 본 적 없는 코드. */
    internal fun newCodes(codes: List<PublicCode>, seen: Set<String>, first: Boolean) =
        if (first) emptyList() else codes.filter { it.code !in seen }

    /** 입력 페이지를 열어 본 코드. 다시 볼 때 흐리게 보여 준다. */
    fun opened(context: Context): Set<String> = prefs(context).getStringSet("opened", emptySet()).orEmpty()

    fun markOpened(context: Context, code: String) {
        prefs(context).edit().putStringSet("opened", opened(context) + code).apply()
    }

    private fun toJson(codes: List<PublicCode>) = JSONArray(codes.map {
        JSONObject().put("code", it.code).put("reward", it.reward ?: "").put("server", it.server)
            .put("discovered", it.discovered?.toString() ?: "").put("expiry", it.expiry?.toString() ?: "").put("official", it.official)
            .put("old", it.old)
    })

    private fun fromJson(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map {
        PublicCode(
            code = it.getString("code"),
            reward = it.optString("reward").ifEmpty { null },
            server = it.optString("server").ifEmpty { "전 서버" },
            discovered = it.optString("discovered").ifEmpty { null }?.let(LocalDate::parse),
            expiry = it.optString("expiry").ifEmpty { null }?.let(LocalDate::parse),
            official = it.optBoolean("official"),
            old = it.optBoolean("old"),
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
