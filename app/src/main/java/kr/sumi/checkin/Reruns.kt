package kr.sumi.checkin

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** 복각표에 쓰는 버전 하나. label은 화면에 보일 이름(6.x는 'Luna I'처럼). */
data class GameVersion(val v: String, val label: String, val start: LocalDate)

/**
 * 5성 캐릭터의 마지막 픽업. chronicled는 집록(여러 5성 모음) 기원, standard는 상시 기원에도 나오는 캐릭터.
 * first는 처음 픽업된 버전, runs는 혼자 픽업된 횟수(1이면 아직 복각 없음).
 */
data class RerunCharacter(
    val name: String,
    val ko: String,
    val element: String,
    val last: String,
    val chronicled: Boolean,
    val standard: Boolean,
    val first: String? = null,
    val runs: Int = 0,
    /** 5 또는 4. 4성의 runs는 픽업에 나온 기원 수. */
    val rarity: Int = 5,
    /** 마지막(또는 발표된 다음) 픽업 시작일. */
    val lastStart: LocalDate? = null,
)

/** 복각표 칸의 상태: 지금 픽업 중, 이번 버전 전반에 나왔다 끝남, 이번 버전 후반 예정, 다음 버전 이후 예정, 그 밖(지난 버전 수). */
enum class RerunPhase { NOW, FIRST_HALF, SECOND_HALF, LATER, PAST }

/** 픽업 하나(시작~종료일, 5성 캐릭터들, 함께 나오는 4성들). 5성이 여럿이면 집록 기원. */
data class Banner(
    val version: String, val start: LocalDate, val end: LocalDate, val chars: List<String>, val chronicled: Boolean,
    val four: List<String> = emptyList(),
)

data class RerunData(
    val versions: List<GameVersion>,
    val characters: List<RerunCharacter>,
    val banners: List<Banner> = emptyList(),
    /** 픽업 4성(복각표에서 '4성도 보기'를 켰을 때, 기다리는 4성 알림). */
    val fours: List<RerunCharacter> = emptyList(),
) {
    /** 오늘 진행 중인 픽업. */
    fun live(today: LocalDate) = banners.filter { !it.start.isAfter(today) && !it.end.isBefore(today) }

    /** 아직 시작하지 않은, 위키에 미리 올라온 픽업 중 가장 가까운 묶음(같은 날 시작하는 것들). */
    fun upcoming(today: LocalDate): List<Banner> {
        val next = banners.filter { it.start.isAfter(today) }.minOfOrNull { it.start } ?: return emptyList()
        return banners.filter { it.start == next }
    }

    fun character(name: String) = characters.firstOrNull { it.name == name } ?: fours.firstOrNull { it.name == name }

    /** 한국어 이름으로(5성·4성 모두). */
    fun byKo(ko: String) = characters.firstOrNull { it.ko == ko } ?: fours.firstOrNull { it.ko == ko }

    /** 4성을 오래 안 나온 순으로. */
    fun rankedFours(today: LocalDate): List<Pair<RerunCharacter, Int>> =
        fours.mapNotNull { c -> since(c, today)?.let { c to it } }
            .sortedWith(compareByDescending<Pair<RerunCharacter, Int>> { it.second }.thenBy { it.first.ko })

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

    /**
     * 칸에 붙일 상태. 이번 버전에 나오는 캐릭터는 시작일로 전반·후반을 가르고, 지금 진행 중이면 NOW.
     * 다음 버전 이후로 발표된 캐릭터는 LATER(위키에 종료일이 아직 없어 픽업 목록에는 없을 수 있다).
     */
    fun phase(c: RerunCharacter, today: LocalDate): RerunPhase {
        if (live(today).any { c.name in it.chars || c.name in it.four }) return RerunPhase.NOW
        val now = current(today) ?: return RerunPhase.PAST
        val last = versions.indexOfFirst { it.v == c.last }
        val cur = versions.indexOf(now)
        return when {
            last > cur -> RerunPhase.LATER
            last < cur -> RerunPhase.PAST
            c.lastStart != null && c.lastStart.isAfter(today) -> RerunPhase.SECOND_HALF
            else -> RerunPhase.FIRST_HALF
        }
    }

    /** 메인 화면 카드와 홈 화면 위젯에 쓰는 요약: 지금(없으면 다음) 픽업 캐릭터, 기간, 가장 오래 기다린 캐릭터. */
    fun card(today: LocalDate): RerunCardUi {
        fun day(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일"
        val live = live(today)
        val next = if (live.isEmpty()) upcoming(today) else emptyList()
        val shown = live.ifEmpty { next }
        val chars = shown.flatMap { it.chars }.distinct().mapNotNull { character(it) }.map { it.ko to it.element }
        val period = when {
            live.isNotEmpty() -> live.minOf { it.end }.let { "${day(it)}까지 · ${java.time.temporal.ChronoUnit.DAYS.between(today, it)}일 남음" }
            next.isNotEmpty() -> "${day(next.minOf { it.start })}부터"
            else -> ""
        }
        val oldest = ranked(today, standard = false).firstOrNull()?.let { (c, since) -> "가장 오래 기다린 캐릭터 · ${c.ko} ${since}버전째" }
        return RerunCardUi(if (live.isNotEmpty()) "지금 픽업" else if (next.isNotEmpty()) "다음 픽업" else "복각표", period, chars, oldest)
    }

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
                    PickupWidget.updateAll(context)
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
        fun people(a: org.json.JSONArray?, rarity: Int) = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.map {
                RerunCharacter(
                    name = it.getString("name"),
                    ko = it.optString("ko", it.getString("name")),
                    element = it.optString("element"),
                    last = it.getString("last"),
                    chronicled = it.optBoolean("chronicled"),
                    standard = it.optBoolean("standard"),
                    first = it.optString("first").ifEmpty { null },
                    runs = it.optInt("runs"),
                    rarity = rarity,
                    lastStart = it.optString("lastStart").takeIf { s -> s.isNotEmpty() }?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() },
                )
            }
        val characters = people(json.getJSONArray("characters"), 5)
        val fours = people(json.optJSONArray("fours"), 4)
        val banners = json.optJSONArray("banners")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { b ->
                val chars = b.getJSONArray("chars").let { c -> (0 until c.length()).map { c.getString(it) } }
                val four = b.optJSONArray("four")?.let { c -> (0 until c.length()).map { c.getString(it) } }.orEmpty()
                Banner(b.optString("version"), LocalDate.parse(b.getString("start")), LocalDate.parse(b.getString("end")), chars, b.optBoolean("chronicled"), four)
            }
        }.orEmpty()
        return RerunData(versions, characters, banners, fours)
    }

    /** 픽업 알림을 받을 캐릭터(영어 이름). */
    fun watched(context: Context): Set<String> = prefs(context).getStringSet("watch", emptySet()).orEmpty()

    fun setWatched(context: Context, name: String, on: Boolean) {
        val now = watched(context).toMutableSet().apply { if (on) add(name) else remove(name) }
        prefs(context).edit().putStringSet("watch", now).apply()
    }

    /**
     * 기다리는 캐릭터의 픽업이 자료에 올라왔으면(앞으로 열리거나 지금 진행 중) 한 번만 알린다.
     * 같은 캐릭터·같은 픽업은 다시 알리지 않는다. 알릴 것: (캐릭터, 픽업) 목록을 돌려주고 알린 것으로 기억한다.
     */
    internal fun dueAlerts(data: RerunData, watched: Set<String>, notified: Set<String>, today: LocalDate): List<Pair<RerunCharacter, Banner>> =
        data.banners.filter { !it.end.isBefore(today) }
            .flatMap { b -> (b.chars + b.four).filter { it in watched && "$it|${b.start}" !in notified }.mapNotNull { n -> data.character(n)?.let { it to b } } }
            // 같은 날 열리는 5성 픽업들에는 4성이 똑같이 들어 있어, 캐릭터·시작일마다 한 번만.
            .distinctBy { (c, b) -> "${c.name}|${b.start}" }

    /** 자료를 받은 뒤 부른다. 알림이 꺼져 있거나 기다리는 캐릭터가 없으면 아무것도 안 한다. */
    fun checkWatch(context: Context) {
        val watched = watched(context)
        if (watched.isEmpty() || !AppSettings(context).rerunAlert) return
        val data = cached(context) ?: return
        val prefs = prefs(context)
        val notified = prefs.getStringSet("notified", emptySet()).orEmpty()
        val due = dueAlerts(data, watched, notified, ServerClock.today())
        if (due.isEmpty()) return
        for ((c, b) in due) Notifier.notifyRerun(context, c, b, ServerClock.today())
        prefs.edit().putStringSet("notified", notified + due.map { (c, b) -> "${c.name}|${b.start}" }).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
