package kr.sumi.checkin

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** 복각표에 쓰는 버전 하나. label은 화면에 보일 이름(6.x는 'Luna I'처럼). */
data class GameVersion(val v: String, val label: String, val start: LocalDate)

/** 5성 캐릭터의 마지막 픽업. chronicled는 집대성(여러 5성 모음) 기원, standard는 상시 기원에도 나오는 캐릭터. */
data class RerunCharacter(
    val name: String,
    val ko: String,
    val element: String,
    val last: String,
    val chronicled: Boolean,
    val standard: Boolean,
)

data class RerunData(val versions: List<GameVersion>, val characters: List<RerunCharacter>) {
    /** today 기준 지금 버전: 시작일이 지난 버전 중 가장 늦은 것. */
    fun current(today: LocalDate): GameVersion? = versions.lastOrNull { !it.start.isAfter(today) }

    /** 마지막 픽업 뒤 지난 버전 수. 지금 버전에 픽업 중이면 0. 모르는 버전이면 null. */
    fun since(character: RerunCharacter, today: LocalDate): Int? {
        val now = versions.indexOf(current(today) ?: return null)
        val last = versions.indexOfFirst { it.v == character.last }
        if (last < 0) return null
        return (now - last).coerceAtLeast(0)
    }

    fun label(v: String) = versions.firstOrNull { it.v == v }?.label ?: v

    /** 오래 안 나온 순(같으면 이름순). 상시 캐릭터는 따로 보여 주므로 standard로 거른다. */
    fun ranked(today: LocalDate, standard: Boolean): List<Pair<RerunCharacter, Int>> =
        characters.filter { it.standard == standard }
            .mapNotNull { c -> since(c, today)?.let { c to it } }
            .sortedWith(compareByDescending<Pair<RerunCharacter, Int>> { it.second }.thenBy { it.first.ko })
}

/**
 * 복각표 데이터. 사이트(apps.sumi.kr)에 하루 한 번 위키에서 다시 만들어 두는 작은 JSON 파일을 받는다.
 * 앱은 하루에 한 번만 묻고, 바뀌지 않았으면(304) 받지 않는다. 받은 파일은 저장해 두고 오프라인에서도 쓴다.
 * 그림은 쓰지 않는다(캐릭터 이름·원소·버전 같은 사실 정보만).
 */
object Reruns {
    private const val URL_DATA = "https://apps.sumi.kr/checkin/data/reruns.json"
    private const val PREFS = "reruns"
    private const val REFRESH_MS = 12 * 60 * 60_000L

    fun cached(context: Context): RerunData? =
        prefs(context).getString("json", null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

    fun isFresh(context: Context) = System.currentTimeMillis() - prefs(context).getLong("checked_at", 0) < REFRESH_MS

    /** 오래됐으면 새로 받는다. 바뀌었으면 true. 네트워크 오류는 IOException. */
    @Throws(IOException::class)
    fun refresh(context: Context, force: Boolean = false): Boolean {
        if (!force && isFresh(context)) return false
        val prefs = prefs(context)
        val conn = URL(URL_DATA).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "SumiLab-Checkin/1.0 (Android)")
            prefs.getString("etag", null)?.takeIf { prefs.contains("json") }?.let { conn.setRequestProperty("If-None-Match", it) }
            val changed = when (conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> false
                in 200..299 -> {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    parse(JSONObject(text)) // 깨진 파일은 저장하지 않는다(예외로 끝남)
                    prefs.edit().putString("json", text).putString("etag", conn.getHeaderField("ETag")).apply()
                    true
                }
                else -> throw IOException("HTTP ${conn.responseCode}")
            }
            prefs.edit().putLong("checked_at", System.currentTimeMillis()).apply()
            return changed
        } catch (e: org.json.JSONException) {
            throw IOException("복각표 자료를 읽지 못했어요")
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(json: JSONObject): RerunData {
        val versions = json.getJSONArray("versions").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                GameVersion(it.getString("v"), it.optString("label", it.getString("v")), LocalDate.parse(it.getString("start")))
            }
        }
        val characters = json.getJSONArray("characters").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                RerunCharacter(
                    name = it.getString("name"),
                    ko = it.optString("ko", it.getString("name")),
                    element = it.optString("element"),
                    last = it.getString("last"),
                    chronicled = it.optBoolean("chronicled"),
                    standard = it.optBoolean("standard"),
                )
            }
        }
        return RerunData(versions, characters)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
