package kr.sumi.checkin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 호요랩 전적의 내 캐릭터 하나. image는 전적이 주는 256px 얼굴 그림. */
data class OwnedCharacter(
    val id: Int,
    val name: String,
    /** 원소 영문 이름(소문자), 예: "dendro". */
    val element: String,
    val rarity: Int,
    val level: Int,
    val fetter: Int,
    val constellation: Int,
    val image: String,
)

/**
 * 로비형 홈(고르는 기능). 최애 캐릭터를 정하면 그 원소 색으로 화면이 바뀌고, 배경으로
 * 공식 그림(호요랩 위키의 세로 그림, 공식 서버에서 받아 폰에만 저장) · 내 사진 · 원소 무늬 중 하나를 쓴다.
 *
 * 저작권 위험을 줄이려고: 기본은 카드형이고 로비형은 사용자가 켤 때만, 공식 그림은 앱에 넣지 않고 받아만 보여 주며,
 * 사이트 설정(config.json)으로 앱 업데이트 없이 공식 그림을 모두 끌 수 있다(끄면 내 사진이나 원소 무늬로 바뀐다).
 * 스토어·아이콘·홍보 이미지에는 쓰지 않는다.
 */
object Lobby {
    const val STYLE_CARD = "card"
    const val STYLE_LOBBY = "lobby"
    const val BG_OFFICIAL = "official"
    const val BG_PHOTO = "photo"
    const val BG_PATTERN = "pattern"

    /** 공식 그림 끄기 스위치 등 앱 설정. 사이트에 두어 앱 업데이트 없이 바꾼다. */
    private const val CONFIG_URL = "https://apps.sumi.kr/checkin/data/config.json"
    private const val INDEX_URL = "https://bbs-api-os.hoyolab.com/game_record/genshin/api/index"
    private const val WIKI_LIST = "https://sg-wiki-api.hoyolab.com/hoyowiki/genshin/wapi/get_entry_page_list"
    private const val WIKI_PAGE = "https://sg-wiki-api-static.hoyolab.com/hoyowiki/genshin/wapi/entry_page?entry_page_id="
    private const val PREFS = "lobby"
    private const val DAY_MS = 24 * 60 * 60_000L

    val ELEMENT_KO = mapOf(
        "anemo" to "바람", "geo" to "바위", "electro" to "번개", "dendro" to "풀",
        "hydro" to "물", "pyro" to "불", "cryo" to "얼음",
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(context: Context) = File(context.filesDir, "lobby").apply { mkdirs() }
    fun photoFile(context: Context) = File(dir(context), "photo.jpg")

    // ---------- 공식 그림 끄기 스위치 ----------

    /** 공식 그림을 써도 되는지(사이트 설정). 받아 둔 값이 없으면 true. */
    fun officialArtAllowed(context: Context) = prefs(context).getBoolean("official_art", true)

    /** 사이트 설정을 12시간에 한 번 받아 둔다. 실패하면 받아 둔 값을 그대로 쓴다. */
    fun refreshConfig(context: Context) {
        val p = prefs(context)
        if (System.currentTimeMillis() - p.getLong("config_at", 0) < DAY_MS / 2) return
        try {
            val json = JSONObject(get(CONFIG_URL))
            p.edit().putBoolean("official_art", json.optBoolean("lobbyOfficialArt", true))
                .putLong("config_at", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
        }
    }

    // ---------- 내 캐릭터 ----------

    internal fun parseCharacters(json: JSONObject): List<OwnedCharacter> {
        val list = json.optJSONObject("data")?.optJSONArray("avatars") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }.map {
            OwnedCharacter(
                id = it.optInt("id"),
                name = it.optString("name"),
                element = it.optString("element").lowercase(),
                rarity = it.optInt("rarity"),
                level = it.optInt("level"),
                fetter = it.optInt("fetter"),
                constellation = it.optInt("actived_constellation_num"),
                image = it.optString("image"),
            )
        }.filter { it.name.isNotEmpty() }
    }

    /** 받아 둔 내 캐릭터 목록(계정별). */
    fun cachedCharacters(context: Context, store: Store): List<OwnedCharacter> =
        prefs(context).getString("chars_${store.accountId}", null)?.let { parseCharacters(JSONObject(it)) }.orEmpty()

    /** 내 캐릭터 목록을 호요랩 전적에서 받는다(하루에 한 번까지). 네트워크 오류는 IOException. */
    @Throws(IOException::class)
    fun refreshCharacters(context: Context, store: Store, force: Boolean = false): List<OwnedCharacter> {
        val p = prefs(context)
        val key = "chars_${store.accountId}"
        if (!force && System.currentTimeMillis() - p.getLong("${key}_at", 0) < DAY_MS) return cachedCharacters(context, store)
        val client = CheckInClient(store.cookie ?: return emptyList())
        val role = store.redeemRole ?: store.accountRole ?: return emptyList()
        val json = client.get("$INDEX_URL?server=${role.region}&role_id=${role.uid}", Notes.headers())
        if (json.optInt("retcode", -1) != 0) throw IOException(json.optString("message"))
        p.edit().putString(key, json.toString()).putLong("${key}_at", System.currentTimeMillis()).apply()
        return parseCharacters(json)
    }

    // ---------- 복각표 얼굴 그림 ----------

    private const val FACES_URL = "https://apps.sumi.kr/checkin/data/faces.json"
    private val faceMemory = android.util.LruCache<String, Bitmap>(128)

    /** 저장해 둔 얼굴 그림만(받지 않는다). 공식 그림이 꺼져 있으면 null. 화면을 바로 그릴 때 쓴다. */
    fun cachedFace(context: Context, ko: String): Bitmap? {
        if (!officialArtAllowed(context)) return null
        faceMemory.get(ko)?.let { return it }
        val file = File(File(dir(context), "faces"), "${ko.hashCode().toUInt()}.webp")
        return if (file.exists()) BitmapFactory.decodeFile(file.path)?.also { faceMemory.put(ko, it) } else null
    }

    /**
     * 복각표 얼굴 그림(호요랩 위키의 공식 그림, 사이트의 faces.json에 주소). 받아서 128px로 줄여 폰에만 저장한다(장당 수 KB).
     * 공식 그림이 꺼져 있거나 주소가 없으면 null(원소 아이콘으로 보인다).
     */
    fun face(context: Context, ko: String): Bitmap? {
        cachedFace(context, ko)?.let { return it }
        if (!officialArtAllowed(context)) return null
        val url = faceUrls(context)[ko] ?: return null
        return try {
            val bytes = getBytes(url)
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val small = Bitmap.createScaledBitmap(src, 128, 128 * src.height / src.width, true)
            val dir = File(dir(context), "faces").apply { mkdirs() }
            File(dir, "${ko.hashCode().toUInt()}.webp").outputStream().use {
                @Suppress("DEPRECATION")
                small.compress(if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 85, it)
            }
            faceMemory.put(ko, small)
            small
        } catch (e: Exception) {
            null
        }
    }

    /** 사이트의 얼굴 그림 주소 목록(일주일에 한 번 받는다). */
    private fun faceUrls(context: Context): Map<String, String> {
        val p = prefs(context)
        val cached = p.getString("faces", null)
        val json = if (cached != null && System.currentTimeMillis() - p.getLong("faces_at", 0) < 7 * DAY_MS) JSONObject(cached)
        else try {
            JSONObject(get(FACES_URL)).also { p.edit().putString("faces", it.toString()).putLong("faces_at", System.currentTimeMillis()).apply() }
        } catch (e: Exception) {
            cached?.let { JSONObject(it) } ?: return emptyMap()
        }
        return json.keys().asSequence().associateWith { json.optString(it) }
    }

    // ---------- 알림의 큰 최애 그림 ----------

    private const val PORTRAITS_URL = "https://apps.sumi.kr/checkin/data/portraits.json"

    /** 알림 그림 두 가지: 접힌 알림의 2:1 띠(머리 전체), 펼친 알림의 4:3(얼굴과 어깨). */
    class NotifArt(val small: Bitmap, val big: Bitmap)

    private fun notifArtFile(context: Context, kind: String) = File(dir(context), "notif_art_$kind.png")

    /** 알림에 붙일 최애 그림. 받아 둔 것만 쓴다(알림은 바로 만들어야 하니까). 없거나 공식 그림이 꺼져 있으면 null. */
    fun cachedNotifArt(context: Context, name: String): NotifArt? {
        if (!officialArtAllowed(context) || prefs(context).getString("notif_art_name", null) != name) return null
        val small = notifArtFile(context, "small").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } ?: return null
        val big = notifArtFile(context, "big").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } ?: return null
        return NotifArt(small, big)
    }

    /**
     * 최애의 공식 세로 그림을 받아 얼굴 둘레만 잘라 알림용으로 저장한다(최애 하나만, 수십 KB).
     * 얼굴 위치는 사이트가 매일 찾아 portraits.json에 둔다. 위치가 없는 캐릭터는 지우고 작은 얼굴 동그라미를 쓴다.
     */
    fun loadNotifArt(context: Context, name: String) {
        if (!officialArtAllowed(context)) return
        val p = prefs(context)
        val entry = (portraits(context) ?: return).optJSONObject(name)
        if (entry == null) {
            notifArtFile(context, "small").delete()
            notifArtFile(context, "big").delete()
            p.edit().remove("notif_art_name").remove("notif_art_pic").apply()
            return
        }
        val pic = entry.optString("pic")
        if (cachedNotifArt(context, name) != null && p.getString("notif_art_pic", null) == pic) return
        val face = entry.optJSONArray("face") ?: return
        try {
            val bytes = getBytes(pic)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 720) sample *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            val art = notifArts(src, FloatArray(4) { face.getDouble(it).toFloat() })
            notifArtFile(context, "small").outputStream().use { art.small.compress(Bitmap.CompressFormat.PNG, 100, it) }
            notifArtFile(context, "big").outputStream().use { art.big.compress(Bitmap.CompressFormat.PNG, 100, it) }
            p.edit().putString("notif_art_name", name).putString("notif_art_pic", pic).apply()
        } catch (e: Exception) {
        }
    }

    /** 세로 그림과 얼굴 상자로 알림 그림 두 가지를 만든다(여러 캐릭터 그림에서 머리가 잘리지 않게 맞춘 비율). */
    internal fun notifArts(src: Bitmap, face: FloatArray) =
        NotifArt(notifArt(src, face, 288, 144, 2.2f, 0.3f), notifArt(src, face, 384, 288, 2.7f, 0.2f))

    /**
     * 얼굴 상자(그림 크기에 대한 비율: 왼, 위, 오른, 아래) 둘레를 [outW]x[outH]로 자른다. 높이는 얼굴 높이의 [heightFaces]배,
     * 가운데는 얼굴 중심에서 얼굴 높이의 [up]배만큼 위(머리카락·모자가 잘리지 않게). 왼쪽은 글자 쪽으로 서서히 투명, 모서리는 둥글게.
     */
    private fun notifArt(src: Bitmap, face: FloatArray, outW: Int, outH: Int, heightFaces: Float, up: Float): Bitmap {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val faceH = (face[3] - face[1]) * h
        var cropH = minOf(h, faceH * heightFaces)
        val cropW = minOf(w, cropH * outW / outH)
        cropH = cropW * outH / outW
        val cx = (face[0] + face[2]) / 2 * w
        val cy = (face[1] + face[3]) / 2 * h - faceH * up
        val x0 = (cx - cropW / 2).coerceIn(0f, w - cropW)
        val y0 = (cy - cropH / 2).coerceIn(0f, h - cropH)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val dst = android.graphics.RectF(0f, 0f, outW.toFloat(), outH.toFloat())
        val radius = outH * 0.1f
        c.drawRoundRect(dst, radius, radius, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, android.graphics.Rect(x0.toInt(), y0.toInt(), (x0 + cropW).toInt(), (y0 + cropH).toInt()), dst, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        paint.shader = android.graphics.LinearGradient(0f, 0f, outW * 0.4f, 0f,
            intArrayOf(0x00000000, 0x99000000.toInt(), 0xFF000000.toInt()), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(dst, paint)
        return out
    }

    /** 사이트의 얼굴 위치 목록(하루에 한 번 받는다). 못 받으면 지난 것, 그것도 없으면 null. */
    private fun portraits(context: Context): JSONObject? {
        val p = prefs(context)
        val cached = p.getString("portraits", null)
        if (cached != null && System.currentTimeMillis() - p.getLong("portraits_at", 0) < DAY_MS) return JSONObject(cached)
        return try {
            JSONObject(get(PORTRAITS_URL)).also { p.edit().putString("portraits", it.toString()).putLong("portraits_at", System.currentTimeMillis()).apply() }
        } catch (e: Exception) {
            cached?.let { JSONObject(it) }
        }
    }

    // ---------- 그림 ----------

    /** 로비 배경으로 쓸 그림. 정한 방식대로 받거나(캐시) 읽고, 쓸 수 없으면 null(원소 무늬로 그린다). */
    fun background(context: Context, settings: AppSettings, maxWidth: Int): Bitmap? {
        val photo = photoFile(context)
        return when {
            settings.lobbyBackground == BG_PHOTO && photo.exists() -> decode(photo, maxWidth)
            settings.lobbyBackground == BG_OFFICIAL && officialArtAllowed(context) ->
                settings.favorite?.let { fav -> portraitFile(context, fav.name)?.let { decode(it, maxWidth) } }
            else -> null
        }
    }

    /** 저장해 둔 공식 세로 그림만(받지 않는다). 화면을 바로 그릴 때 쓴다. */
    fun cachedBackground(context: Context, settings: AppSettings, maxWidth: Int): Bitmap? {
        val photo = photoFile(context)
        return when {
            settings.lobbyBackground == BG_PHOTO && photo.exists() -> decode(photo, maxWidth)
            settings.lobbyBackground == BG_OFFICIAL && officialArtAllowed(context) ->
                settings.favorite?.let { fav -> File(dir(context), portraitName(fav.name)).takeIf { it.exists() }?.let { decode(it, maxWidth) } }
            else -> null
        }
    }

    private fun portraitName(name: String) = "portrait_${name.hashCode().toUInt()}.jpg"

    /** 호요랩 위키 캐릭터 문서의 갤러리 세로 그림을 받아 저장한다(한 번만). 못 찾으면 null. */
    private fun portraitFile(context: Context, name: String): File? {
        val file = File(dir(context), portraitName(name))
        if (file.exists()) return file
        return try {
            val url = wikiPortraitUrl(context, name) ?: return null
            val bytes = getBytes(url)
            // 다른 캐릭터 그림은 지운다(최애 하나만 둔다).
            dir(context).listFiles { f -> f.name.startsWith("portrait_") }?.forEach { it.delete() }
            file.writeBytes(bytes)
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun wikiPortraitUrl(context: Context, name: String): String? = wikiPage(context, name)?.let { page ->
        // 같은 문서에 있는 대사도 이때 저장해 둔다(문서를 두 번 받지 않게).
        saveVoices(context, name, voiceLines(page))
        galleryPortrait(page)
    }

    /** 호요랩 위키의 캐릭터 한 명: 한국어 이름, 문서 번호, 원소(영문 소문자, 모르면 빈 문자열), 얼굴 그림 주소. */
    data class WikiCharacter(val name: String, val id: String, val element: String, val icon: String)

    /** 위키 목록 한 쪽의 항목들. 여행자·별인형처럼 기원에서 얻지 않는 캐릭터는 뺀다. */
    internal fun parseWikiList(items: org.json.JSONArray): List<WikiCharacter> = (0 until items.length()).map { items.getJSONObject(it) }.mapNotNull { o ->
        val name = o.optString("name")
        if (name.isEmpty() || name.startsWith("여행자") || name.startsWith("별인형")) return@mapNotNull null
        val element = o.optJSONObject("filter_values")?.optJSONObject("character_vision")?.optJSONArray("value_types")
            ?.optJSONObject(0)?.optString("enum_string").orEmpty().lowercase().takeIf { it in ELEMENT_KO } ?: ""
        WikiCharacter(name, o.optString("entry_page_id"), element, o.optString("icon_url").takeIf { it.startsWith("https://") } ?: "")
    }

    /**
     * 호요랩 위키의 전체 캐릭터(최근 캐릭터가 앞). 일주일에 한 번 받고, 못 받으면 지난 목록.
     * 아직 없는 최애를 고를 때와, 캐릭터 문서를 찾을 때 쓴다.
     */
    fun wikiCharacters(context: Context): List<WikiCharacter> {
        val p = prefs(context)
        val cached = p.getString("wiki_chars", null)
        fun decode(text: String) = org.json.JSONArray(text).let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { WikiCharacter(it.optString("n"), it.optString("i"), it.optString("e"), it.optString("c")) }
        }
        if (cached != null && System.currentTimeMillis() - p.getLong("wiki_chars_at", 0) < 7 * DAY_MS) return decode(cached)
        return try {
            val list = mutableListOf<WikiCharacter>()
            for (page in 1..8) {
                val body = """{"filters":[],"menu_id":"2","page_num":$page,"page_size":50,"use_es":true}"""
                val items = JSONObject(get(WIKI_LIST, body)).optJSONObject("data")?.optJSONArray("list") ?: break
                list += parseWikiList(items)
                if (items.length() < 50) break
            }
            if (list.isEmpty()) throw IOException("위키 목록이 비어 있어요")
            p.edit().putString("wiki_chars", org.json.JSONArray(list.map { JSONObject().put("n", it.name).put("i", it.id).put("e", it.element).put("c", it.icon) }).toString())
                .putLong("wiki_chars_at", System.currentTimeMillis()).apply()
            list
        } catch (e: Exception) {
            cached?.let { decode(it) }.orEmpty()
        }
    }

    /** 아직 없는 최애를 복각표의 '기다리는 캐릭터'(픽업 알림)에 넣는다. 복각표에 없는 캐릭터(4성 등)는 그대로. */
    fun watchRerun(context: Context, name: String) {
        Reruns.cached(context)?.characters?.firstOrNull { it.ko == name }?.let { Reruns.setWatched(context, it.name, true) }
    }

    /** 아직 없는 최애의 로비 한 줄: 픽업 중이면 그 소식, 아니면 마지막 픽업 뒤 지난 버전 수(복각표 자료). */
    fun waitingLine(context: Context, name: String, today: java.time.LocalDate) = waitingLine(Reruns.cached(context), name, today)

    internal fun waitingLine(data: RerunData?, name: String, today: java.time.LocalDate): String {
        if (data == null) return "아직 만나지 못했어요"
        val c = data.characters.firstOrNull { it.ko == name } ?: return "아직 만나지 못했어요"
        if (data.live(today).any { c.name in it.chars }) return "아직 만나지 못했어요 · 지금 픽업 중!"
        if (data.upcoming(today).any { c.name in it.chars }) return "아직 만나지 못했어요 · 곧 픽업이 열려요"
        val since = data.since(c, today) ?: return "아직 만나지 못했어요"
        return "아직 만나지 못했어요 · 마지막 픽업 ${since}버전 전"
    }

    /** 호요랩 위키 캐릭터 문서(이름으로 찾는다). 못 찾으면 null. */
    private fun wikiPage(context: Context, name: String): JSONObject? {
        val id = wikiCharacters(context).firstOrNull { it.name == name }?.id?.takeIf { it.isNotEmpty() } ?: return null
        return JSONObject(get(WIKI_PAGE + id))
    }

    // ---------- 캐릭터 대사 ----------

    /**
     * 캐릭터 대사 한 줄: 제목(예: "아침 인사…"), 한국어 대사, 한국어 음성 주소(없을 수 있음),
     * 게임에서 열리는 호감도(0이면 처음부터), 스토리를 깨야 열리는지, 여행자 성별 전용인지.
     */
    data class VoiceLine(
        val title: String, val text: String, val audio: String?,
        val fetter: Int = 0, val story: Boolean = false, val gendered: Boolean = false,
    )

    /** 위키 대사 칸의 개방 조건(예: "호감도 Lv.4 달성 후 개방", "「물의 아이」 스토리 클리어"). 문단 하나가 통째로 조건(뒤에 ※주의가 붙기도)이거나 대사 끝에 붙어 있다. */
    private val UNLOCK_TAIL = Regex("(호감도|돌파) Lv\\.?\\s*「?\\d+」?\\s*달성 후 개방$")
    private val UNLOCK_PARAGRAPH = Regex("^((호감도|돌파) Lv|※|.{0,40}(스토리 (클리어|완료)|후 개방)$)")
    private val FETTER = Regex("호감도 Lv\\.?\\s*(\\d+)")
    private val GENDERED = Regex("\\(「[^」]*」 전용 음성\\)")

    /** 대사 칸(HTML) 하나를 대사와 개방 조건으로 나눈다. 조건은 문단(또는 줄) 하나이거나 대사 끝에 붙어 있다. 남은 줄은 한 칸 띄워 잇는다. */
    internal fun voiceLine(title: String, desc: String, audio: String?): VoiceLine? {
        var fetter = 0
        var story = false
        val kept = mutableListOf<String>()
        val paragraphs = desc.replace(Regex("<br\\s*/?>|</p>"), "\n").replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").split("\n")
        for (raw in paragraphs) {
            var p = raw.trim()
            val conditions = mutableListOf<String>()
            UNLOCK_TAIL.find(p)?.let { conditions += it.value; p = p.substring(0, it.range.first).trim() }
            if (UNLOCK_PARAGRAPH.containsMatchIn(p)) { conditions += p; p = "" }
            for (c in conditions) {
                FETTER.find(c)?.let { fetter = maxOf(fetter, it.groupValues[1].toInt()) }
                if (c.contains("스토리") || c.contains("임무") || c.contains("클리어")) story = true
            }
            p = p.replace(GENDERED, "").trim()
            if (p.isNotEmpty()) kept += p
        }
        val text = kept.joinToString(" ")
        return if (text.isEmpty()) null else VoiceLine(title, text, audio, fetter, story, GENDERED.containsMatchIn(desc))
    }

    /** 위키 문서의 음성(voice) 목록. */
    internal fun voiceLines(page: JSONObject): List<VoiceLine> {
        val modules = page.optJSONObject("data")?.optJSONObject("page")?.optJSONArray("modules") ?: return emptyList()
        for (i in 0 until modules.length()) {
            val comps = modules.getJSONObject(i).optJSONArray("components") ?: continue
            for (j in 0 until comps.length()) {
                val comp = comps.getJSONObject(j)
                if (comp.optString("component_id") != "voice") continue
                val list = try { JSONObject(comp.optString("data", "{}")).optJSONArray("list") } catch (e: Exception) { null } ?: return emptyList()
                return (0 until list.length()).map { list.getJSONObject(it) }.mapNotNull { v ->
                    val audios = v.optJSONArray("audios")
                    val ko = (0 until (audios?.length() ?: 0)).map { audios!!.getJSONObject(it) }.firstOrNull { it.optString("name") == "한국어" }?.optString("url")
                    voiceLine(v.optString("title").trim(), v.optString("desc"), ko?.takeIf { it.startsWith("https://") })
                }
            }
        }
        return emptyList()
    }

    private fun voicesFile(context: Context, name: String) = File(dir(context), "voices_v3_${name.hashCode().toUInt()}.json")

    private fun saveVoices(context: Context, name: String, lines: List<VoiceLine>) {
        if (lines.isEmpty()) return
        // 다른 캐릭터 대사는 지운다(최애 하나만 둔다).
        dir(context).listFiles { f -> f.name.startsWith("voices_") }?.forEach { it.delete() }
        voicesFile(context, name).writeText(org.json.JSONArray(lines.map {
            JSONObject().put("t", it.title).put("x", it.text).put("a", it.audio ?: "").put("f", it.fetter).put("s", it.story).put("g", it.gendered)
        }).toString())
    }

    /** 저장해 둔 최애 대사 전부(잠긴 것 포함). 공식 자료가 꺼져 있으면 비어 있다. 보여 줄 때는 [unlockedVoices]. */
    fun voices(context: Context, name: String): List<VoiceLine> {
        if (!officialArtAllowed(context)) return emptyList()
        val file = voicesFile(context, name)
        if (!file.exists()) return emptyList()
        return try {
            val a = org.json.JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                VoiceLine(it.optString("t"), it.optString("x"), it.optString("a").ifEmpty { null }, it.optInt("f"), it.optBoolean("s"), it.optBoolean("g"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 게임에서 이미 열렸을 대사만: 내 호감도(전적, 계정 중 가장 높은 것. 없으면 0)까지 열리는 것.
     * 스토리를 깨야 열리는 대사는 진행도를 알 수 없어 늘 빼고(스포일러), 여행자 성별 전용 대사와 너무 긴 대사도 뺀다.
     */
    fun unlockedVoices(context: Context, name: String): List<VoiceLine> {
        val fetter = AppSettings(context).accounts().maxOfOrNull { store -> cachedCharacters(context, store).firstOrNull { it.name == name }?.fetter ?: 0 } ?: 0
        return unlocked(voices(context, name), fetter)
    }

    internal fun unlocked(lines: List<VoiceLine>, fetter: Int) =
        lines.filter { !it.story && !it.gendered && it.fetter <= fetter && it.text.length <= 120 }

    /** 대사가 아직 없으면 받아 둔다(공식 그림을 안 써도 대사는 쓴다). */
    fun loadVoices(context: Context, name: String) {
        if (!officialArtAllowed(context) || voicesFile(context, name).exists()) return
        try { wikiPage(context, name)?.let { saveVoices(context, name, voiceLines(it)) } } catch (e: Exception) {}
    }

    /** 상황에 맞는 대사 묶음(제목 앞부분으로 고른다). */
    fun pick(lines: List<VoiceLine>, vararg prefixes: String): List<VoiceLine> =
        lines.filter { l -> prefixes.any { l.title.startsWith(it) } }

    /**
     * 평소 이야기 대사(잡담·하고 싶은 이야기·취미·고민·자기 이야기·우리에 대해 등). 위키가 캐릭터마다 같은 제목을 쓴다.
     * 날씨 대사(실제 날씨와 어긋남)와 다른 캐릭터 이야기(분위기가 무거운 것이 섞임), 전투 대사는 넣지 않는다.
     */
    fun talk(lines: List<VoiceLine>, name: String): List<VoiceLine> {
        val names = listOf(name, name.split(" ").last()).distinct()
        val own = names.flatMap { listOf("${it}의 취미", "${it}의 고민", "${it}에 대해 알기", "${it} 자신에 대해") }
        return pick(lines, "잡담", "하고 싶은 이야기", "흥미있는 일", "좋아하는 음식", "우리에 대해", "「신의 눈」에 대해", *own.toTypedArray())
    }

    /** 시간대 공식 인사 묶음의 제목. */
    fun timeGreeting(hour: Int) = when (hour) { in 5..10 -> "아침 인사"; in 11..16 -> "점심 인사"; in 17..21 -> "저녁 인사"; else -> "굿나잇" }

    /** 인사 대사 하나: 반쯤은 시간대 공식 인사, 반쯤은 평소 이야기(공식 인사가 없으면 평소 이야기만). */
    fun greetingLine(lines: List<VoiceLine>, name: String, hour: Int, random: kotlin.random.Random = kotlin.random.Random): VoiceLine? {
        val official = pick(lines, timeGreeting(hour))
        val chat = talk(lines, name)
        val pool = if (official.isNotEmpty() && (chat.isEmpty() || random.nextBoolean())) official else chat
        return pool.randomOrNull(random) ?: pick(lines, "첫 만남").firstOrNull()
    }

    /** 위키 문서에서 갤러리(gallery_character)의 세로 그림 주소. */
    internal fun galleryPortrait(page: JSONObject): String? {
        val modules = page.optJSONObject("data")?.optJSONObject("page")?.optJSONArray("modules") ?: return null
        for (i in 0 until modules.length()) {
            val comps = modules.getJSONObject(i).optJSONArray("components") ?: continue
            for (j in 0 until comps.length()) {
                val comp = comps.getJSONObject(j)
                if (comp.optString("component_id") != "gallery_character") continue
                return JSONObject(comp.optString("data", "{}")).optString("pic").takeIf { it.startsWith("http") }
            }
        }
        return null
    }

    /** 전적의 작은 얼굴 그림(메뉴 화면 머리용). 받아서 저장해 둔다. */
    fun bust(context: Context, url: String): Bitmap? {
        if (!url.startsWith("http")) return null
        val file = File(dir(context), "bust_${url.hashCode().toUInt()}.png")
        return try {
            if (!file.exists()) file.writeBytes(getBytes(url))
            BitmapFactory.decodeFile(file.path)
        } catch (e: Exception) {
            null
        }
    }

    /** 저장해 둔 작은 얼굴 그림만(받지 않는다). 알림·위젯처럼 바로 그려야 할 때 쓴다. */
    fun cachedBust(context: Context, url: String): Bitmap? {
        if (!url.startsWith("http") || !officialArtAllowed(context)) return null
        val file = File(dir(context), "bust_${url.hashCode().toUInt()}.png")
        return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }

    /** 얼굴 그림을 원소 색 테두리가 있는 동그라미로(알림 큰 아이콘·위젯용). */
    fun circle(src: Bitmap, ring: Int, size: Int = 144): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        paint.color = ring
        c.drawCircle(r, r, r, paint)
        paint.color = LobbyTheme.blend(ring, android.graphics.Color.WHITE, 0.55f)
        c.drawCircle(r, r, r - size * 0.05f, paint)
        val inset = size * 0.05f
        val shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        val scale = (size - 2 * inset) / minOf(src.width, src.height)
        shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale); postTranslate(inset, inset) })
        paint.color = android.graphics.Color.WHITE
        paint.shader = shader
        c.drawCircle(r, r, r - inset, paint)
        return out
    }

    /**
     * 로비형을 쓰고 최애가 있으면 (원소 색, 얼굴 동그라미). 아니면 null. 알림·위젯이 쓴다.
     * 공식 자료가 꺼져 있으면 얼굴은 null이고 색만 쓴다.
     */
    fun favoriteLook(context: Context): Pair<Int, Bitmap?>? {
        val settings = AppSettings(context)
        val fav = settings.favorite ?: return null
        if (settings.homeStyle != STYLE_LOBBY) return null
        val color = context.getColor(elementColorRes(fav.element))
        return color to cachedBust(context, fav.image)?.let { circle(it, color) }
    }

    /** 고른 사진을 화면 크기에 맞게 줄여 앱 안에만 저장한다(원본 사진은 건드리지 않는다). */
    @Throws(IOException::class)
    fun savePhoto(context: Context, uri: Uri, maxWidth: Int) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw IOException("사진을 열 수 없어요")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IOException("사진을 읽을 수 없어요")
        photoFile(context).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun decode(file: File, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------- 네트워크 ----------

    /** 주소에 한자 등이 섞여 있으면 그 글자만 인코딩한다(위키 그림 주소에 캐릭터 이름이 들어 있다). */
    internal fun ascii(url: String) = buildString {
        for (ch in url) if (ch.code < 128) append(ch) else append(URLEncoder.encode(ch.toString(), "UTF-8"))
    }

    private fun open(url: String): HttpURLConnection = (URL(ascii(url)).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", "SumiLab-Checkin/1.0 (Android)")
        setRequestProperty("x-rpc-language", CheckInClient.LANG)
        setRequestProperty("Referer", "https://wiki.hoyolab.com/")
    }

    private fun get(url: String, postBody: String? = null): String {
        val conn = open(url)
        try {
            if (postBody != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(postBody.toByteArray()) }
            }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun getBytes(url: String): ByteArray {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** 원소 영문 이름 → 원소 색 리소스. */
    fun elementColorRes(element: String) = when (element.lowercase()) {
        "anemo" -> R.color.element_anemo
        "geo" -> R.color.element_geo
        "electro" -> R.color.element_electro
        "dendro" -> R.color.element_dendro
        "hydro" -> R.color.element_hydro
        "pyro" -> R.color.element_pyro
        "cryo" -> R.color.element_cryo
        else -> R.color.hero
    }

    /** 원소 영문 이름 → 원소 아이콘. */
    fun elementIcon(element: String) = when (element.lowercase()) {
        "anemo" -> R.drawable.ic_element_anemo
        "geo" -> R.drawable.ic_element_geo
        "electro" -> R.drawable.ic_element_electro
        "dendro" -> R.drawable.ic_element_dendro
        "hydro" -> R.drawable.ic_element_hydro
        "pyro" -> R.drawable.ic_element_pyro
        "cryo" -> R.drawable.ic_element_cryo
        else -> R.drawable.ic_item_primogem
    }
}
