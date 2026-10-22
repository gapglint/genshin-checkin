package kr.sumi.checkin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 호요랩 원신 이벤트 하나. path는 웹이벤트 주소(https://…) 또는 안내 글(/article/번호). 시각은 초. banner는 호요랩 배너 그림 주소. */
data class GameEvent(val id: String, val name: String, val start: Long, val end: Long, val path: String, val live: Boolean, val banner: String = "")

/**
 * 호요랩 원신 이벤트 목록(웹이벤트, 원석 보상 토론 등). 로그인 없이 받는 공개 목록이다.
 * 6시간에 한 번만 받아 저장해 둔다. 눌러서 열 때는 저장된 로그인으로 앱 안에서 연다(LoginActivity 페이지 모드).
 */
object Events {
    private const val LIST_URL = "https://bbs-api-os.hoyolab.com/community/community_contribution/wapi/event/list?gids=2&size=20"
    private const val BBS = "https://bbs-api-os.hoyolab.com"
    private const val PREFS = "events"
    private const val REFRESH_MS = 6 * 60 * 60_000L
    private val HEADERS = mapOf("x-rpc-client_type" to "4", "x-rpc-app_version" to "2.50.0", "x-rpc-language" to "ko-kr")
    /** 안내 글 안의 참여 링크. 공식 짧은 주소(hoyo.link)가 '이벤트 참여하기' 링크라 먼저 보고(실제 주소로 풀어서), 없으면 웹이벤트 주소. */
    private val SHORT_LINK = Regex("""https://hoyo\.link/[A-Za-z0-9]+""")
    private val EVENT_LINK = Regex("""https://act\.hoyo(?:lab|verse)\.com/[^\s"'<>\\]+""")

    fun cached(context: Context): List<GameEvent> =
        prefs(context).getString("json", null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }.orEmpty()

    fun isFresh(context: Context) = System.currentTimeMillis() - prefs(context).getLong("checked_at", 0) < REFRESH_MS

    /** 진행 중인 이벤트(호요랩이 진행 중으로 표시했거나 기간 안). 끝나는 날이 가까운 순. */
    fun live(context: Context, nowSec: Long = ServerClock.now() / 1000): List<GameEvent> =
        cached(context).filter { it.live || (it.start <= nowSec && (it.end == 0L || nowSec < it.end)) }
            .filter { it.end == 0L || nowSec < it.end }
            .sortedBy { if (it.end == 0L) Long.MAX_VALUE else it.end }

    /** 아직 목록을 열어 보지 않은 진행 중 이벤트 수(아이콘의 점). */
    fun unseen(context: Context): Int {
        val seen = prefs(context).getStringSet("seen", emptySet()).orEmpty()
        return live(context).count { it.id !in seen }
    }

    fun markSeen(context: Context) {
        prefs(context).edit().putStringSet("seen", live(context).map { it.id }.toSet()).apply()
    }

    @Throws(IOException::class)
    fun refresh(context: Context, force: Boolean = false): Boolean {
        if (!force && isFresh(context)) return false
        val json = get(LIST_URL)
        if (json.optInt("retcode", -1) != 0) throw IOException(json.optString("message", "이벤트 목록을 받지 못했어요"))
        parse(json)
        prefs(context).edit().putString("json", json.toString()).putLong("checked_at", System.currentTimeMillis()).apply()
        return true
    }

    /**
     * 열 주소: 웹이벤트 주소면 그대로, 안내 글이면 글 안의 참여 링크, 없으면 글 페이지(토픽 토론처럼 글에서 참여하는 이벤트).
     * 참여 링크는 웹이벤트 페이지(act.hoyolab.com, act.hoyoverse.com)로 가는 것만 쓴다. 충전센터처럼 호요랩 로그인이
     * 통하지 않는 곳이나, 글 끝에 홍보로 붙은 다른 이벤트(others의 주소)는 건너뛴다. 네트워크 오류면 글 페이지로 연다.
     */
    fun target(event: GameEvent, others: List<GameEvent> = emptyList()): String {
        if (event.path.startsWith("http")) return event.path
        val postId = Regex("""/article/(\d+)""").find(event.path)?.groupValues?.get(1)
            ?: return "https://www.hoyolab.com${event.path}"
        val page = "https://www.hoyolab.com/article/$postId"
        val content = try {
            get("$BBS/community/post/wapi/getPostFull?post_id=$postId").optJSONObject("data")?.optJSONObject("post")?.optJSONObject("post")
        } catch (e: IOException) {
            null
        } ?: return page
        val text = (content.optString("content") + " " + content.optString("structured_content")).replace("\\/", "/")
        val promoted = others.filter { it.id != event.id && it.path.startsWith("http") }.map { it.path.substringBefore('?') }.toSet()
        val links = SHORT_LINK.findAll(text).map { it.value }.distinct().mapNotNull { runCatching { resolve(it) }.getOrNull() } +
            EVENT_LINK.findAll(text).map { it.value.replace("\\u0026", "&").replace("&amp;", "&") }
        return links.firstOrNull { isEventPage(it) && it.substringBefore('?') !in promoted } ?: page
    }

    internal fun isEventPage(url: String) = EVENT_LINK.find(url)?.range?.first == 0

    /** 짧은 주소(hoyo.link)가 가리키는 실제 주소. 몇 번 넘겨주기를 따라간다. */
    private fun resolve(url: String): String {
        var current = url
        repeat(4) {
            if (!SHORT_LINK.matches(current) && !current.startsWith("https://hoyo.link/")) return current
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                current = conn.getHeaderField("Location") ?: return current
            } finally {
                conn.disconnect()
            }
        }
        return current
    }

    internal fun parse(json: JSONObject): List<GameEvent> {
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).map { list.getJSONObject(it) }.map {
            GameEvent(
                id = it.optString("id"),
                name = it.optString("name").trim(),
                start = it.optString("start").toLongOrNull() ?: 0,
                end = it.optString("end").toLongOrNull() ?: 0,
                path = it.optString("web_path").ifEmpty { it.optString("app_path") },
                // 3 = 진행 중, 4 = 끝남 (호요랩 표시)
                live = it.optInt("status") == 3,
                banner = it.optString("banner_url").takeIf { u -> u.startsWith("https://") }.orEmpty(),
            )
        }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
    }

    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", CheckInClient.USER_AGENT)
            HEADERS.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: org.json.JSONException) {
            throw IOException("이벤트 목록을 읽지 못했어요")
        } finally {
            conn.disconnect()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
