package kr.sumi.checkin

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 리딤 보상 아이템의 한국어 이름. 위키는 보상을 영어 이름으로 적어서, 처음 보는 아이템은
 * 위키 아이템 문서의 다른 언어 표기(|ko = …)를 한 번에 묶어 물어보고 기기에 저장해 둔다. 한 번 배운 이름은 다시 묻지 않는다.
 * 못 찾으면 영어 이름 그대로 보여 준다.
 */
object ItemNames {
    private const val PREFS = "item_names"
    private const val API = "https://genshin-impact.fandom.com/api.php"
    private const val MAX_TITLES = 40

    @Volatile private var learned: Map<String, String> = emptyMap()
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
        learned = prefs(context).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap().orEmpty()
    }

    fun korean(name: String): String? = learned[name]

    /** 위키 코드 문서의 보상에서 아직 모르는 아이템 이름을 찾아 배운다(네트워크). 실패해도 조용히 넘어간다. */
    fun learnFromWiki(wiki: JSONObject, known: Set<String>) {
        val context = app ?: return
        val names = Redeem.wikiEntries(wiki).mapNotNull { it.reward }.flatMap { raw ->
            raw.split(";").map { cleanName(it.substringBefore("*")) }
        }.filter { it.isNotEmpty() && it !in known && it !in learned }.distinct().take(MAX_TITLES)
        if (names.isEmpty()) return
        val found = runCatching { fetch(names) }.getOrNull() ?: return
        if (found.isEmpty()) return
        prefs(context).edit().apply { found.forEach { (k, v) -> putString(k, v) } }.apply()
        learned = learned + found
    }

    private fun fetch(names: List<String>): Map<String, String> {
        val titles = URLEncoder.encode(names.joinToString("|"), "UTF-8")
        val url = "$API?action=query&prop=revisions&rvprop=content&rvslots=main&format=json&formatversion=2&redirects=1&titles=$titles"
        val conn = URL(url).openConnection() as HttpURLConnection
        val json = try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "SumiLab-Checkin/1.0 (Android)")
            if (conn.responseCode !in 200..299) return emptyMap()
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
        return parse(json)
    }

    /** 위키 응답에서 문서 제목(영어 이름) → 한국어 이름. 넘겨주기(redirect)는 원래 이름으로 되돌린다. */
    internal fun parse(json: JSONObject): Map<String, String> {
        val query = json.optJSONObject("query") ?: return emptyMap()
        val back = mutableMapOf<String, String>()
        for (key in listOf("normalized", "redirects")) query.optJSONArray(key)?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { back[it.getString("to")] = back[it.getString("from")] ?: it.getString("from") }
        }
        val pages = query.optJSONArray("pages") ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for (i in 0 until pages.length()) {
            val page = pages.getJSONObject(i)
            val content = page.optJSONArray("revisions")?.optJSONObject(0)?.optJSONObject("slots")?.optJSONObject("main")?.optString("content")
                ?: continue
            val ko = koName(content) ?: continue
            val title = page.getString("title")
            out[back[title] ?: title] = ko
        }
        return out
    }

    /** 문서의 '|ko = …' 줄. {{tt|절운|絕雲}}처럼 풀이가 붙은 틀은 앞 글자만 남긴다. */
    internal fun koName(content: String): String? {
        val line = content.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("|ko") && it.substringBefore("=").trim() == "|ko" }
            ?: return null
        var value = line.substringAfter("=").trim()
        while (true) {
            val start = value.indexOf("{{")
            val end = value.indexOf("}}", start + 2)
            if (start < 0 || end < 0) break
            val parts = value.substring(start + 2, end).split("|")
            value = value.substring(0, start) + (if (parts.size > 1) parts[1] else "") + value.substring(end + 2)
        }
        return cleanName(value).takeIf { it.isNotEmpty() }
    }

    private fun cleanName(text: String) = listOf("[[", "]]", "{{", "}}").fold(text) { t, mark -> t.replace(mark, "") }.trim()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
