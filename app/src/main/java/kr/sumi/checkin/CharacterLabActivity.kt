package kr.sumi.checkin

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 실험(exp/lobby-characters 브랜치 전용): 호요랩 전적에서 내 캐릭터 목록과 그림 주소를 받아,
 * 그림마다 크기를 보여 주고 눌러서 로비형 홈 배경으로 어떻게 보일지 미리 본다. 로그인 정보는 화면에 쓰지 않는다.
 */
class CharacterLabActivity : Activity() {
    private lateinit var list: LinearLayout
    private val pool = Executors.newFixedThreadPool(4)
    private var preview: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.drawBehindSystemBars(window)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        val root = FrameLayout(this).apply {
            setBackgroundResource(R.color.bg)
            addView(ScrollView(this@CharacterLabActivity).apply { addView(list) })
        }
        setContentView(root)
        MainActivity.applySystemBarInsets(root)
        note("캐릭터 그림 실험", 20f, true)
        val store = AppSettings(this).accounts().firstOrNull()
        if (store == null) return note("로그인한 계정이 없어요.")
        note("불러오는 중...")
        Thread { load(store) }.start()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Android 13 미만용")
    override fun onBackPressed() {
        val p = preview
        if (p != null) { (p.parent as? FrameLayout)?.removeView(p); preview = null } else super.onBackPressed()
    }

    private fun load(store: Store) {
        val client = CheckInClient(store.cookie ?: return)
        val role = store.redeemRole ?: store.accountRole ?: Redeem.roles(client).firstOrNull()
        if (role == null) return runOnUiThread { note("캐릭터를 찾지 못했어요.") }
        // 어느 주소가 그림을 주는지 몰라 둘 다 받아 본다. 응답 코드와 그림 필드만 보여 준다.
        val tries = listOf(
            "index" to { client.get("$BASE/index?server=${role.region}&role_id=${role.uid}", Notes.headers()) },
            "character/list" to { client.post("$BASE/character/list", """{"role_id":"${role.uid}","server":"${role.region}","sort_type":1}""", Notes.headers()) },
        )
        for ((name, call) in tries) {
            val json = try { call() } catch (e: Exception) { runOnUiThread { note("$name: 오류 ${e.message}") }; continue }
            val data = json.optJSONObject("data")
            val items = data?.optJSONArray("avatars") ?: data?.optJSONArray("list") ?: JSONArray()
            runOnUiThread { note("$name: retcode ${json.optInt("retcode")} · ${json.optString("message")} · 캐릭터 ${items.length()}명", 14f, true) }
            for (i in 0 until items.length()) {
                val c = items.getJSONObject(i)
                val images = imageFields(c)
                runOnUiThread { addCharacter(c, images) }
            }
        }
    }

    /** 그림 주소로 보이는 문자열 필드(한 단계 안쪽 객체까지). */
    private fun imageFields(c: JSONObject, prefix: String = ""): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (key in c.keys()) {
            when (val v = c.opt(key)) {
                is String -> if (v.startsWith("http") && Regex("\\.(png|jpe?g|webp)", RegexOption.IGNORE_CASE).containsMatchIn(v)) out += "$prefix$key" to v
                is JSONObject -> if (prefix.isEmpty()) out += imageFields(v, "$key.")
            }
        }
        return out
    }

    private fun addCharacter(c: JSONObject, images: List<Pair<String, String>>) {
        note("${c.optString("name")} · ${c.optString("element")} · ★${c.optInt("rarity")} · Lv.${c.optInt("level")}", 15f, true, top = 18)
        list.addView(TextView(this).apply {
            text = "▶ 위키 세로 그림으로 로비 보기"
            textSize = 14f
            setTextColor(getColor(R.color.accent))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener {
                text = "▶ 위키 그림 받는 중..."
                val name = c.optString("name")
                pool.execute {
                    val result = try { wikiPortrait(name) } catch (e: Exception) { null }
                    runOnUiThread {
                        if (result == null) { text = "▶ 위키에서 그림을 못 찾았어요"; return@runOnUiThread }
                        text = "▶ 위키 세로 그림 ${result.width}×${result.height} · 다시 보기"
                        showLobby(result, c)
                    }
                }
            }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        list.addView(HorizontalScrollView(this).apply { addView(row) })
        for ((key, url) in images) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), dp(10), 0) }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply { setColor(0xFFDDE1EB.toInt()); cornerRadius = dp(8).toFloat() }
            }
            val label = TextView(this).apply { text = key; textSize = 11f; setTextColor(getColor(R.color.ink_muted)) }
            cell.addView(image, LinearLayout.LayoutParams(dp(110), dp(150)))
            cell.addView(label, LinearLayout.LayoutParams(dp(110), WRAP_CONTENT))
            row.addView(cell)
            pool.execute {
                val bmp = try { fetch(url) } catch (e: Exception) { null }
                runOnUiThread {
                    if (bmp == null) { label.text = "$key\n못 받음"; return@runOnUiThread }
                    image.setImageBitmap(bmp)
                    label.text = "$key\n${bmp.width}×${bmp.height}"
                    image.setOnClickListener { showLobby(bmp, c) }
                }
            }
        }
    }

    /** 호요랩 위키 캐릭터 목록(이름 → 문서 번호). 한 번만 받는다. */
    private var wikiIds: Map<String, String>? = null

    /** 호요랩 위키 캐릭터 문서의 '갤러리' 세로 그림(대개 1080×1920 이상). */
    private fun wikiPortrait(name: String): Bitmap? {
        val ids = wikiIds ?: run {
            val map = mutableMapOf<String, String>()
            for (page in 1..6) {
                val body = """{"filters":[],"menu_id":"2","page_num":$page,"page_size":50,"use_es":true}"""
                val items = wikiJson("https://sg-wiki-api.hoyolab.com/hoyowiki/genshin/wapi/get_entry_page_list", body)
                    .optJSONObject("data")?.optJSONArray("list") ?: break
                for (i in 0 until items.length()) items.getJSONObject(i).let { map[it.optString("name")] = it.optString("entry_page_id") }
                if (items.length() < 50) break
            }
            map.also { wikiIds = it }
        }
        val id = ids[name] ?: return null
        val page = wikiJson("https://sg-wiki-api-static.hoyolab.com/hoyowiki/genshin/wapi/entry_page?entry_page_id=$id", null)
            .optJSONObject("data")?.optJSONObject("page") ?: return null
        val modules = page.optJSONArray("modules") ?: return null
        for (i in 0 until modules.length()) {
            val comps = modules.getJSONObject(i).optJSONArray("components") ?: continue
            for (j in 0 until comps.length()) {
                val comp = comps.getJSONObject(j)
                if (comp.optString("component_id") != "gallery_character") continue
                val pic = JSONObject(comp.optString("data")).optString("pic")
                if (pic.isNotEmpty()) return fetch(pic)
            }
        }
        return null
    }

    private fun wikiJson(url: String, body: String?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("x-rpc-language", CheckInClient.LANG)
            conn.setRequestProperty("Referer", "https://wiki.hoyolab.com/")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** 주소에 한자 등이 섞여 있으면 그 글자만 인코딩한다. */
    private fun ascii(url: String) = buildString {
        for (ch in url) if (ch.code < 128) append(ch) else append(java.net.URLEncoder.encode(ch.toString(), "UTF-8"))
    }

    private fun fetch(url: String): Bitmap? {
        val conn = URL(ascii(url)).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 로비형 홈 미리보기. 그림은 화면 폭에 맞춰 위에 두고, 아래는 원소 색으로 이어진다.
     * 아래 패널을 위로 밀면(스크롤) 지금의 카드들이 올라오고, 그림은 천천히 올라가며 어두워진다.
     */
    private fun showLobby(bmp: Bitmap, c: JSONObject) {
        val root = window.decorView.findViewById<FrameLayout>(android.R.id.content)
        val el = elementColor(c.optString("element"))
        val dark = blend(el, Color.BLACK, 0.72f)
        val light = blend(el, Color.WHITE, 0.78f)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val imgH = screenW * bmp.height / bmp.width

        val view = FrameLayout(this).apply { setBackgroundColor(dark); isClickable = true }
        val image = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }
        view.addView(image, FrameLayout.LayoutParams(MATCH_PARENT, imgH))
        // 위: 로고가 묻히고 상단 표시가 읽히게 원소 색으로 감싼다. 아래: 그림 끝을 원소 색으로 이어 준다.
        view.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(withAlpha(dark, 235), withAlpha(dark, 200), withAlpha(dark, 0)))
        }, FrameLayout.LayoutParams(MATCH_PARENT, dp(200)))
        val fadeTop = minOf(imgH, (screenH * 0.76f).toInt()) - dp(240)
        view.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(withAlpha(dark, 0), dark))
        }, FrameLayout.LayoutParams(MATCH_PARENT, dp(240)).apply { topMargin = fadeTop })
        view.addView(View(this).apply { setBackgroundColor(dark) },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = fadeTop + dp(240) })
        // 밀어 올릴수록 그림을 덮는 어두운 막
        val dim = View(this).apply { setBackgroundColor(dark); alpha = 0f }
        view.addView(dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 오른쪽 메뉴(고정, 밀면 사라짐)
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((icon, label) in listOf("🎁" to "리딤", "✦" to "보상", "❖" to "픽업", "▦" to "이벤트", "✎" to "꾸미기")) {
            side.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, dp(12))
                addView(TextView(context).apply {
                    text = icon; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x7A0C121C); setStroke(dp(1), 0x40FFFFFF) }
                }, LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(label(label, 10.5f, Color.WHITE, bold = true, shadow = true))
            })
        }
        // 말풍선(고정, 밀면 사라짐)
        val bubble = label("좋은 아침이에요, 여행자 님.\n출석이랑 새 코드 1개 받아 뒀어요.", 13.5f, getColor(R.color.ink)).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(0xEEFFFFFF.toInt()); cornerRadii = floatArrayOf(14f, 14f, 14f, 14f, 14f, 14f, 4f, 4f).map { it * resources.displayMetrics.density }.toFloatArray() }
        }

        // 스크롤되는 내용: [빈 공간 + 이름표] [아래 패널] [카드들]
        val dockH = dp(172)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(FrameLayout(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("★ 최애", 11f, dark, bold = true).apply {
                    setPadding(dp(9), dp(3), dp(9), dp(3))
                    background = GradientDrawable().apply { setColor(light); cornerRadius = dp(99).toFloat() }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                addView(label(c.optString("name"), 34f, Color.WHITE, bold = true, shadow = true))
                addView(label("● ${ELEMENT_KO[c.optString("element").lowercase()] ?: c.optString("element")} · Lv.${c.optInt("level")} · 호감도 ${c.optInt("fetter")} · 운명의 자리 ${c.optInt("actived_constellation_num")}",
                    12.5f, Color.WHITE, shadow = true))
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(dp(20), 0, 0, dp(20)) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, screenH - dockH - dp(20)))
        content.addView(dock(dark, light), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(14), 0, dp(14), dp(16)) })
        content.addView(detailCards(el, light), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        view.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        view.addView(side, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END).apply { setMargins(0, dp(150), dp(12), 0) })
        view.addView(bubble, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(16), dp(96), dp(90), 0) })
        view.addView(label("☾ 142/200     ✓ 17일째", 14f, Color.WHITE).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(0x30FFFFFF); setStroke(dp(1), 0x40FFFFFF); cornerRadius = dp(99).toFloat() }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(16), dp(44), 0, 0) })

        // 밀어 올리는 만큼: 그림은 천천히 올라가고 어두워지며, 말풍선·메뉴는 사라진다.
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val t = (y / (screenH * 0.5f)).coerceIn(0f, 1f)
            image.translationY = -y * 0.35f
            dim.alpha = t * 0.85f
            side.alpha = 1f - t * 2f
            bubble.alpha = 1f - t * 2f
        }
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        preview = view
    }

    /** 아래 패널: 오늘 출석, 레진 가득 시각, 원신 켜기, 밀어서 자세히. */
    private fun dock(dark: Int, light: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(10))
        background = GradientDrawable().apply { setColor(0x2EFFFFFF); setStroke(dp(1), 0x40FFFFFF); cornerRadius = dp(24).toFloat() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("✓", 19f, dark, bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(light) }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(label("오늘 출석 완료", 15.5f, Color.WHITE, bold = true))
                addView(label("원석 ×20 · 다음 자동 출석 내일 01:03", 11.5f, 0xDDFFFFFF.toInt()))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(label("3:20", 28f, light))
                addView(label("레진 가득", 10.5f, 0xDDFFFFFF.toInt()))
            })
        })
        addView(label("원신 켜기", 15f, dark, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, dp(13))
            background = GradientDrawable().apply { setColor(light); cornerRadius = dp(15).toFloat() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        addView(label("⌃ 밀어서 자세히", 11f, 0xBBFFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** 밀어 올리면 나오는 지금의 카드들(예시 값). */
    private fun detailCards(el: Int, light: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(18), dp(16), dp(40))
        background = GradientDrawable().apply {
            setColor(getColor(R.color.bg))
            val r = dp(26).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        addView(View(context).apply { background = GradientDrawable().apply { setColor(0xFFC5CAD6.toInt()); cornerRadius = dp(3).toFloat() } },
            LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
        fun card(title: String, vararg lines: String) = addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(getColor(R.color.surface)); cornerRadius = dp(20).toFloat() }
            addView(label(title, 14f, getColor(R.color.ink), bold = true))
            for (l in lines) addView(label(l, 13f, getColor(R.color.ink_muted)).apply { setPadding(0, dp(6), 0, 0) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        card("레진 142 / 200 · 오후 3:20 가득", "탐사 파견 4/5 완료", "주전자 동전 1,800 / 2,400", "일일 의뢰 4 / 4 · 보상 받음")
        // 이번 달 출석 달력(간단히)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(getColor(R.color.surface)); cornerRadius = dp(20).toFloat() }
            addView(label("10월 출석 보상 · 17 / 31", 14f, getColor(R.color.ink), bold = true))
            var row: LinearLayout? = null
            for (d in 1..35) {
                if ((d - 1) % 7 == 0) row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }.also { addView(it) }
                row!!.addView(label(if (d <= 31) "$d" else "", 11f, if (d <= 17) Color.WHITE else getColor(R.color.ink_muted)).apply {
                    gravity = Gravity.CENTER
                    if (d <= 31) background = GradientDrawable().apply { setColor(if (d <= 17) el else 0xFFF0F2F7.toInt()); cornerRadius = dp(8).toFloat() }
                }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        card("이번 달 받은 것", "원석 140 · 모라 25,000 · 영웅의 경험 6", "10/18 리딤 PJ2KEBRAYSRM · 원석 ×60")
        card("지금 픽업 · 11월 3일까지", "에스코피에 · 스커크", "가장 오래 기다린 캐릭터 · 호두 17버전째")
        card("메인 화면 편집", "카드 순서·숨기기·홈 스타일(카드형 / 로비형)")
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false, shadow: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (shadow) setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    private fun withAlpha(c: Int, a: Int) = (c and 0xFFFFFF) or (a shl 24)

    private fun elementColor(element: String) = getColor(when (element.lowercase()) {
        "anemo" -> R.color.element_anemo
        "geo" -> R.color.element_geo
        "electro" -> R.color.element_electro
        "dendro" -> R.color.element_dendro
        "hydro" -> R.color.element_hydro
        "pyro" -> R.color.element_pyro
        "cryo" -> R.color.element_cryo
        else -> R.color.hero
    })

    private fun note(text: String, size: Float = 13f, bold: Boolean = false, top: Int = 6) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(getColor(R.color.ink))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(top), 0, 0)
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val ELEMENT_KO = mapOf("anemo" to "바람", "geo" to "바위", "electro" to "번개", "dendro" to "풀", "hydro" to "물", "pyro" to "불", "cryo" to "얼음")
        private const val BASE = "https://bbs-api-os.hoyolab.com/game_record/genshin/api"
    }
}
