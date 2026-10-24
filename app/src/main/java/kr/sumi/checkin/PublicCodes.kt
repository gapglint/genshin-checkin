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
)

/**
 * 로그인 전 화면의 '지금 쓸 수 있는 리딤 코드'. 위키와 호요랩 공식 목록(공개)만 본다.
 * 로그인 전에는 사용자의 서버를 모르니 모든 서버의 코드를 보여 주고, 줄마다 쓸 수 있는 서버를 적는다.
 * 6시간에 한 번만 받아 저장해 둔다. 누르면 공식 입력 페이지(genshin.hoyoverse.com/gift)를 코드가 채워진 채 연다.
 */
object PublicCodes {
    private const val PREFS = "public_codes"
    private const val KEY = "json_v3"
    private const val REFRESH_MS = 6 * 60 * 60_000L
    private val SERVER_NAMES = mapOf("SEA" to "아시아", "NA" to "미국", "EU" to "유럽", "SAR" to "TW·HK·MO")

    fun giftUrl(code: String) = "https://genshin.hoyoverse.com/m/ko/gift?code=$code"

    /** 화면에 보일 보상 요약(아이템 이름은 한국어로, 최대 3가지). */
    fun rewardText(raw: String?) = raw?.let { RewardHistory.formatTotals(Redeem.rewardItems(it), max = 3) }

    fun cached(context: Context): List<PublicCode> =
        prefs(context).getString(KEY, null)?.let { runCatching { fromJson(JSONArray(it)) }.getOrNull() }.orEmpty()

    fun isFresh(context: Context) =
        prefs(context).contains(KEY) && System.currentTimeMillis() - prefs(context).getLong("checked_at", 0) < REFRESH_MS

    @Throws(IOException::class)
    fun refresh(context: Context) {
        val (wiki, official) = Redeem.publishedCodes()
        if (wiki == null && official.isEmpty()) throw IOException("코드 목록을 받지 못했어요")
        val codes = from(wiki, official, ServerClock.today())
        prefs(context).edit().putString(KEY, toJson(codes).toString()).putLong("checked_at", System.currentTimeMillis()).apply()
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

    /** 입력 페이지를 열어 본 코드. 다시 볼 때 흐리게 보여 준다. */
    fun opened(context: Context): Set<String> = prefs(context).getStringSet("opened", emptySet()).orEmpty()

    fun markOpened(context: Context, code: String) {
        prefs(context).edit().putStringSet("opened", opened(context) + code).apply()
    }

    private fun toJson(codes: List<PublicCode>) = JSONArray(codes.map {
        JSONObject().put("code", it.code).put("reward", it.reward ?: "").put("server", it.server)
            .put("discovered", it.discovered?.toString() ?: "").put("expiry", it.expiry?.toString() ?: "").put("official", it.official)
    })

    private fun fromJson(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map {
        PublicCode(
            code = it.getString("code"),
            reward = it.optString("reward").ifEmpty { null },
            server = it.optString("server").ifEmpty { "전 서버" },
            discovered = it.optString("discovered").ifEmpty { null }?.let(LocalDate::parse),
            expiry = it.optString("expiry").ifEmpty { null }?.let(LocalDate::parse),
            official = it.optBoolean("official"),
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
