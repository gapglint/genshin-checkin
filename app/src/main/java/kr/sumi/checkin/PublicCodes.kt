package kr.sumi.checkin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

/** 로그인 없이 보여 주는 리딤 코드 하나. reward는 화면에 보일 보상 요약(모르면 null). */
data class PublicCode(val code: String, val reward: String?)

/**
 * 로그인 전 화면의 '지금 쓸 수 있는 리딤 코드'. 위키와 호요랩 공식 목록(공개)만 보고, 서버는 아시아 기준으로 고른다.
 * 6시간에 한 번만 받아 저장해 둔다. 누르면 공식 입력 페이지(genshin.hoyoverse.com/gift)를 코드가 채워진 채 연다.
 */
object PublicCodes {
    private const val PREFS = "public_codes"
    private const val REFRESH_MS = 6 * 60 * 60_000L
    private const val REGION = "os_asia"

    fun giftUrl(code: String) = "https://genshin.hoyoverse.com/m/ko/gift?code=$code"

    fun cached(context: Context): List<PublicCode> =
        prefs(context).getString("json", null)?.let { runCatching { fromJson(JSONArray(it)) }.getOrNull() }.orEmpty()

    fun isFresh(context: Context) = System.currentTimeMillis() - prefs(context).getLong("checked_at", 0) < REFRESH_MS

    @Throws(IOException::class)
    fun refresh(context: Context) {
        val (wiki, official) = Redeem.publishedCodes()
        if (wiki == null && official.isEmpty()) throw IOException("코드 목록을 받지 못했어요")
        val codes = from(wiki, official, ServerClock.today())
        prefs(context).edit().putString("json", toJson(codes).toString()).putLong("checked_at", System.currentTimeMillis()).apply()
    }

    /** 묶음(같은 보상의 대체 코드)은 첫 코드만 보여 준다. */
    internal fun from(wiki: JSONObject?, official: List<String>, today: LocalDate): List<PublicCode> {
        val rewards = wiki?.let { Redeem.wikiRewards(it) }.orEmpty()
        return Redeem.codeGroups(wiki, official, REGION, today).map { it.first() }.distinct().map { code ->
            PublicCode(code, rewards[code]?.let { RewardHistory.formatTotals(Redeem.rewardItems(it), max = 3) })
        }
    }

    /** 입력 페이지를 열어 본 코드. 다시 볼 때 흐리게 보여 준다. */
    fun opened(context: Context): Set<String> = prefs(context).getStringSet("opened", emptySet()).orEmpty()

    fun markOpened(context: Context, code: String) {
        prefs(context).edit().putStringSet("opened", opened(context) + code).apply()
    }

    private fun toJson(codes: List<PublicCode>) = JSONArray(codes.map { JSONObject().put("code", it.code).put("reward", it.reward ?: "") })

    private fun fromJson(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
        .map { PublicCode(it.getString("code"), it.optString("reward").ifEmpty { null }) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
