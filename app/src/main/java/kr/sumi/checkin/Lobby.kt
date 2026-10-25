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
    private const val ART_W = 384
    private const val ART_H = 288

    private fun notifArtFile(context: Context) = File(dir(context), "notif_art.png")

    /** 알림에 붙일 최애 그림. 받아 둔 것만 쓴다(알림은 바로 만들어야 하니까). 없거나 공식 그림이 꺼져 있으면 null. */
    fun cachedNotifArt(context: Context, name: String): Bitmap? {
        if (!officialArtAllowed(context)) return null
        val file = notifArtFile(context)
        if (!file.exists() || prefs(context).getString("notif_art_name", null) != name) return null
        return BitmapFactory.decodeFile(file.path)
    }

    /**
     * 최애의 공식 세로 그림을 받아 얼굴 둘레만 잘라 알림용으로 저장한다(최애 하나만, 수십 KB).
     * 얼굴 위치는 사이트가 매일 찾아 portraits.json에 둔다. 위치가 없는 캐릭터는 지우고 작은 얼굴 동그라미를 쓴다.
     */
    fun loadNotifArt(context: Context, name: String) {
        if (!officialArtAllowed(context)) return
        val p = prefs(context)
        val entry = (portraits(context) ?: return).optJSONObject(name)
        val file = notifArtFile(context)
        if (entry == null) {
            file.delete()
            p.edit().remove("notif_art_name").remove("notif_art_pic").apply()
            return
        }
        val pic = entry.optString("pic")
        if (file.exists() && p.getString("notif_art_name", null) == name && p.getString("notif_art_pic", null) == pic) return
        val face = entry.optJSONArray("face") ?: return
        try {
            val bytes = getBytes(pic)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 720) sample *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            val art = notifArt(src, face.getDouble(0).toFloat(), face.getDouble(1).toFloat(), face.getDouble(2).toFloat(), face.getDouble(3).toFloat())
            file.outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) }
            p.edit().putString("notif_art_name", name).putString("notif_art_pic", pic).apply()
        } catch (e: Exception) {
        }
    }

    /**
     * 얼굴(그림 크기에 대한 비율) 둘레를 4:3으로 잘라, 왼쪽은 글자 쪽으로 서서히 투명해지고 모서리는 둥글게.
     * 접힌 알림은 이 그림의 가운데(얼굴)만 넓게 잘라 보여 준다.
     */
    internal fun notifArt(src: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val faceW = (right - left) * w
        val cropW = minOf(w, faceW * 3.2f)
        val cropH = minOf(h, cropW * ART_H / ART_W)
        val cx = (left + right) / 2 * w
        val cy = (top + bottom) / 2 * h + (bottom - top) * h * 0.1f
        val x0 = (cx - cropW / 2).coerceIn(0f, w - cropW)
        val y0 = (cy - cropH / 2).coerceIn(0f, h - cropH)
        val out = Bitmap.createBitmap(ART_W, ART_H, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val dst = android.graphics.RectF(0f, 0f, ART_W.toFloat(), ART_H.toFloat())
        val radius = ART_W * 0.08f
        c.drawRoundRect(dst, radius, radius, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, android.graphics.Rect(x0.toInt(), y0.toInt(), (x0 + cropW).toInt(), (y0 + cropH).toInt()), dst, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        paint.shader = android.graphics.LinearGradient(0f, 0f, ART_W * 0.45f, 0f,
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

    /** 호요랩 위키 캐릭터 문서(이름으로 찾는다). 못 찾으면 null. */
    private fun wikiPage(context: Context, name: String): JSONObject? {
        val p = prefs(context)
        // 위키 캐릭터 목록(이름 → 문서 번호)은 일주일에 한 번만 받는다.
        val ids = p.getString("wiki_ids", null)?.takeIf { System.currentTimeMillis() - p.getLong("wiki_ids_at", 0) < 7 * DAY_MS }
            ?.let { JSONObject(it) }
            ?: JSONObject().also { map ->
                for (page in 1..6) {
                    val body = """{"filters":[],"menu_id":"2","page_num":$page,"page_size":50,"use_es":true}"""
                    val items = JSONObject(get(WIKI_LIST, body)).optJSONObject("data")?.optJSONArray("list") ?: break
                    for (i in 0 until items.length()) items.getJSONObject(i).let { map.put(it.optString("name"), it.optString("entry_page_id")) }
                    if (items.length() < 50) break
                }
                p.edit().putString("wiki_ids", map.toString()).putLong("wiki_ids_at", System.currentTimeMillis()).apply()
            }
        val id = ids.optString(name).takeIf { it.isNotEmpty() } ?: return null
        return JSONObject(get(WIKI_PAGE + id))
    }

    // ---------- 캐릭터 대사 ----------

    /** 캐릭터 대사 한 줄: 제목(예: "아침 인사…"), 한국어 대사, 한국어 음성 주소(없을 수 있음). */
    data class VoiceLine(val title: String, val text: String, val audio: String?)

    /** 위키 문서의 음성(voice) 목록. 태그와 '돌파 Lv.… 개방' 같은 안내는 뗀다. */
    internal fun voiceLines(page: JSONObject): List<VoiceLine> {
        val modules = page.optJSONObject("data")?.optJSONObject("page")?.optJSONArray("modules") ?: return emptyList()
        for (i in 0 until modules.length()) {
            val comps = modules.getJSONObject(i).optJSONArray("components") ?: continue
            for (j in 0 until comps.length()) {
                val comp = comps.getJSONObject(j)
                if (comp.optString("component_id") != "voice") continue
                val list = JSONObject(comp.optString("data", "{}")).optJSONArray("list") ?: return emptyList()
                return (0 until list.length()).map { list.getJSONObject(it) }.mapNotNull { v ->
                    val text = v.optString("desc").replace(Regex("<br\\s*/?>"), " ").replace(Regex("<[^>]+>"), "")
                        .replace(Regex("돌파 Lv\\.「\\d+」 달성 후 개방"), "").replace(Regex("\\(「[^」]*」 전용 음성\\)"), "")
                        .replace("&nbsp;", " ").trim()
                    val audios = v.optJSONArray("audios")
                    val ko = (0 until (audios?.length() ?: 0)).map { audios!!.getJSONObject(it) }.firstOrNull { it.optString("name") == "한국어" }?.optString("url")
                    if (text.isEmpty()) null else VoiceLine(v.optString("title").trim(), text, ko?.takeIf { it.startsWith("https://") })
                }
            }
        }
        return emptyList()
    }

    private fun voicesFile(context: Context, name: String) = File(dir(context), "voices_${name.hashCode().toUInt()}.json")

    private fun saveVoices(context: Context, name: String, lines: List<VoiceLine>) {
        if (lines.isEmpty()) return
        // 다른 캐릭터 대사는 지운다(최애 하나만 둔다).
        dir(context).listFiles { f -> f.name.startsWith("voices_") }?.forEach { it.delete() }
        voicesFile(context, name).writeText(org.json.JSONArray(lines.map { JSONObject().put("t", it.title).put("x", it.text).put("a", it.audio ?: "") }).toString())
    }

    /** 저장해 둔 최애 대사. 공식 자료가 꺼져 있으면 비어 있다. */
    fun voices(context: Context, name: String): List<VoiceLine> {
        if (!officialArtAllowed(context)) return emptyList()
        val file = voicesFile(context, name)
        if (!file.exists()) return emptyList()
        return try {
            val a = org.json.JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { VoiceLine(it.optString("t"), it.optString("x"), it.optString("a").ifEmpty { null }) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 대사가 아직 없으면 받아 둔다(공식 그림을 안 써도 대사는 쓴다). */
    fun loadVoices(context: Context, name: String) {
        if (!officialArtAllowed(context) || voicesFile(context, name).exists()) return
        try { wikiPage(context, name)?.let { saveVoices(context, name, voiceLines(it)) } } catch (e: Exception) {}
    }

    /** 상황에 맞는 대사 묶음(제목 앞부분으로 고른다). */
    fun pick(lines: List<VoiceLine>, vararg prefixes: String): List<VoiceLine> =
        lines.filter { l -> prefixes.any { l.title.startsWith(it) } }

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
