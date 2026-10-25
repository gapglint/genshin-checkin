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
    /** 위에 덮인 화면들(로비, 그 위의 메뉴 화면). 뒤로 가기는 맨 위부터 닫는다. */
    private val stack = mutableListOf<View>()

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
        // 안드로이드 13 이상은 새 뒤로 가기 방식이라 onBackPressed가 불리지 않는다(매니페스트에서 켜 둠).
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
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
    override fun onBackPressed() = goBack()

    private fun goBack() {
        val top = stack.removeLastOrNull() ?: return finish()
        top.animate().translationX(top.width.toFloat()).alpha(0f).setDuration(180)
            .withEndAction { (top.parent as? FrameLayout)?.removeView(top) }.start()
    }

    private fun push(view: View, slide: Boolean) {
        window.decorView.findViewById<FrameLayout>(android.R.id.content).addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        stack += view
        if (slide) {
            view.translationX = resources.displayMetrics.widthPixels * 0.3f
            view.alpha = 0f
            view.animate().translationX(0f).alpha(1f).setDuration(220).start()
        }
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
     * 로비형 홈 미리보기(스크롤 없음). 그림은 화면 폭에 맞춰 위에 두고, 아래는 원소 색으로 이어진다.
     * 레진·출석 표시와 오른쪽 메뉴를 누르면 최애 색의 메뉴 화면으로 들어가고, 뒤로 가기로 로비에 돌아온다.
     */
    private fun showLobby(bmp: Bitmap, c: JSONObject) {
        val root = window.decorView.findViewById<FrameLayout>(android.R.id.content)
        val t = Theme(elementColor(c.optString("element")))
        val screenW = resources.displayMetrics.widthPixels
        val screenH = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val imgH = screenW * bmp.height / bmp.width
        // 메뉴 화면 머리에 쓸 작은 얼굴 그림(전적의 256px 그림)
        var bust: Bitmap? = null
        c.optString("image").takeIf { it.startsWith("http") }?.let { url -> pool.execute { bust = try { fetch(url) } catch (e: Exception) { null } } }

        val view = FrameLayout(this).apply { setBackgroundColor(t.dark); isClickable = true }
        view.addView(ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }, FrameLayout.LayoutParams(MATCH_PARENT, imgH))
        view.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(withAlpha(t.dark, 235), withAlpha(t.dark, 200), withAlpha(t.dark, 0)))
        }, FrameLayout.LayoutParams(MATCH_PARENT, dp(200)))
        val fadeTop = minOf(imgH, (screenH * 0.76f).toInt()) - dp(240)
        view.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(withAlpha(t.dark, 0), t.dark))
        }, FrameLayout.LayoutParams(MATCH_PARENT, dp(240)).apply { topMargin = fadeTop })
        view.addView(View(this).apply { setBackgroundColor(t.dark) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = fadeTop + dp(240) })

        fun open(title: String, body: LinearLayout.() -> Unit) = push(menuScreen(title, t, bust, c.optString("name"), body), slide = true)

        // 위: 레진·출석 표시(누르면 메뉴로)
        val hud = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hud.addView(pill("☾", "142", "/200", t).apply { setOnClickListener { open("레진") { resinBody(t) } } })
        hud.addView(pill("✓", "17", "일째", t).apply { setOnClickListener { open("출석") { calendarBody(t) } } },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        view.addView(hud, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(16), dp(44), 0, 0) })

        view.addView(label("좋은 아침이에요, 여행자 님.\n출석이랑 새 코드 1개 받아 뒀어요.", 13.5f, getColor(R.color.ink)).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val d = resources.displayMetrics.density
            background = GradientDrawable().apply { setColor(0xEEFFFFFF.toInt()); cornerRadii = floatArrayOf(14f, 14f, 14f, 14f, 14f, 14f, 4f, 4f).map { it * d }.toFloatArray() }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(16), dp(100), dp(90), 0) })

        // 오른쪽 메뉴
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val menus = listOf(
            Triple("🎁", "리딤") { open("리딤 코드") { redeemBody(t) } },
            Triple("✦", "보상") { open("보상 내역") { rewardsBody(t) } },
            Triple("❖", "픽업") { open("픽업") { pickupBody(t) } },
            Triple("▦", "이벤트") { open("이벤트") { eventsBody(t) } },
            Triple("✎", "꾸미기") { open("꾸미기") { decorateBody(t, c.optString("name")) } },
        )
        for ((icon, name, action) in menus) {
            side.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, dp(12))
                setOnClickListener { action() }
                addView(TextView(context).apply {
                    text = icon; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x7A0C121C); setStroke(dp(1), 0x40FFFFFF) }
                }, LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(label(name, 10.5f, Color.WHITE, bold = true, shadow = true))
            })
        }
        view.addView(side, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END).apply { setMargins(0, dp(150), dp(12), 0) })

        // 이름표 + 아래 패널(고정)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, 0, dp(18))
            addView(label("★ 최애", 11f, t.dark, bold = true).apply {
                setPadding(dp(9), dp(3), dp(9), dp(3))
                background = GradientDrawable().apply { setColor(t.light); cornerRadius = dp(99).toFloat() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(label(c.optString("name"), 34f, Color.WHITE, bold = true, shadow = true))
            addView(label("● ${ELEMENT_KO[c.optString("element").lowercase()] ?: c.optString("element")} · Lv.${c.optInt("level")} · 호감도 ${c.optInt("fetter")} · 운명의 자리 ${c.optInt("actived_constellation_num")}",
                12.5f, Color.WHITE, shadow = true))
        })
        bottom.addView(dock(t) { open("출석") { calendarBody(t) } }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(14), 0, dp(14), dp(22)) })
        view.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        push(view, slide = false)
    }

    /** 최애 원소 색 묶음: 짙은 색(바탕), 원소 색(강조), 파스텔(밝은 강조). */
    private inner class Theme(val el: Int) {
        val dark = blend(el, Color.BLACK, 0.72f)
        val deep = blend(el, Color.BLACK, 0.35f)
        val light = blend(el, Color.WHITE, 0.78f)
        val tint = blend(el, Color.WHITE, 0.9f)
    }

    private fun pill(icon: String, value: String, unit: String, t: Theme) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(5), dp(5), dp(12), dp(5))
        background = GradientDrawable().apply { setColor(0x30FFFFFF); setStroke(dp(1), 0x40FFFFFF); cornerRadius = dp(99).toFloat() }
        addView(label(icon, 11f, t.dark, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(label(" $value", 17f, Color.WHITE, numeral = true))
        addView(label(unit, 12f, 0xDDFFFFFF.toInt()))
    }

    /** 아래 패널: 오늘 출석(누르면 출석 메뉴), 레진 가득 시각, 원신 켜기. */
    private fun dock(t: Theme, onCheckIn: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(0x2EFFFFFF); setStroke(dp(1), 0x40FFFFFF); cornerRadius = dp(24).toFloat() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onCheckIn() }
            addView(label("✓", 19f, t.dark, bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.light) }
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
                addView(label("3:20", 30f, t.light, numeral = true))
                addView(label("레진 가득", 10.5f, 0xDDFFFFFF.toInt()))
            })
        })
        addView(label("원신 켜기", 15f, t.dark, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, dp(13))
            background = GradientDrawable().apply { setColor(t.light); cornerRadius = dp(15).toFloat() }
            setOnClickListener { Game.launchIntent(context)?.let { startActivity(it) } }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    /**
     * 메뉴 화면: 최애 원소 색 머리(제목, 최애 얼굴 그림) + 밝은 본문. 게임에서 로비 메뉴에 들어간 것처럼 오른쪽에서 들어온다.
     */
    private fun menuScreen(title: String, t: Theme, bust: Bitmap?, who: String, body: LinearLayout.() -> Unit): View {
        val screen = FrameLayout(this).apply { setBackgroundColor(t.tint); isClickable = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 머리: 원소 색 그라데이션, 왼쪽 제목, 오른쪽 아래 최애 얼굴
        col.addView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.deep, t.dark))
            if (bust != null) addView(ImageView(context).apply { setImageBitmap(bust); scaleType = ImageView.ScaleType.FIT_END; alpha = 0.95f },
                FrameLayout.LayoutParams(dp(150), dp(150), Gravity.END or Gravity.BOTTOM))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(40), dp(18), dp(20))
                addView(label("‹  로비", 14f, 0xDDFFFFFF.toInt(), bold = true).apply {
                    setPadding(0, dp(6), dp(20), dp(10))
                    setOnClickListener { goBack() }
                })
                addView(label(title, 28f, Color.WHITE, bold = true, shadow = true))
                addView(label("$who 와 함께", 12.5f, t.light).apply { setPadding(0, dp(4), 0, 0) })
            }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(190)))
        col.addView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(40))
                body()
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        screen.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return screen
    }

    // ---- 메뉴 화면 내용(예시 값) ----

    private fun LinearLayout.card(build: LinearLayout.() -> Unit) = addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
        build()
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

    private fun LinearLayout.stats(t: Theme, vararg items: Pair<String, String>) = addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, 0)
        for ((v, u) in items) addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(v, 24f, getColor(R.color.ink), numeral = true))
            addView(label(u, 11f, getColor(R.color.ink_muted)))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    })

    private fun LinearLayout.head(text: String, t: Theme) = addView(label(text, 13f, t.deep, bold = true))

    private fun LinearLayout.line(text: String) = addView(label(text, 13.5f, getColor(R.color.ink)).apply { setPadding(0, dp(8), 0, 0) })

    private fun LinearLayout.resinBody(t: Theme) {
        card {
            head("지금 레진", t)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(0, dp(6), 0, 0)
                addView(label("142", 44f, getColor(R.color.ink), numeral = true))
                addView(label(" / 200", 18f, getColor(R.color.ink_muted), numeral = true).apply { setPadding(0, 0, 0, dp(8)) })
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(label("오후 3:20 가득", 14f, t.deep, bold = true).apply { setPadding(0, 0, 0, dp(10)) })
            })
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { setColor(t.tint); cornerRadius = dp(4).toFloat() }
                addView(View(context).apply { background = GradientDrawable().apply { setColor(t.el); cornerRadius = dp(4).toFloat() } },
                    FrameLayout.LayoutParams((resources.displayMetrics.widthPixels - dp(68)) * 142 / 200, MATCH_PARENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(8)).apply { topMargin = dp(6) })
        }
        card { head("오늘 할 일", t); stats(t, "4/5" to "탐사 파견 완료", "1,800" to "주전자 동전", "4/4" to "일일 의뢰") }
        card { head("알림", t); line("레진 180개가 되면 알려 드려요"); line("탐사 파견이 모두 끝나면 알려 드려요") }
    }

    private fun LinearLayout.calendarBody(t: Theme) {
        card {
            head("10월 출석 보상 · 17 / 31", t)
            var row: LinearLayout? = null
            for (d in 1..35) {
                if ((d - 1) % 7 == 0) row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, 0) }.also { addView(it) }
                val done = d <= 17
                row!!.addView(label(if (d <= 31) "$d" else "", 14f, if (done) Color.WHITE else getColor(R.color.ink_muted), numeral = true).apply {
                    gravity = Gravity.CENTER
                    if (d <= 31) background = GradientDrawable().apply {
                        setColor(if (done) t.el else t.tint)
                        if (d == 18) setStroke(dp(2), t.el)
                        cornerRadius = dp(10).toFloat()
                    }
                }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            addView(label("다음 원석 4일 뒤 ×20", 12.5f, t.deep, bold = true).apply { setPadding(0, dp(12), 0, 0) })
        }
        card { head("오늘", t); line("새벽 01:03 자동 출석 · 원석 ×20"); line("보충권은 앱이 대신 받아 와요") }
    }

    private fun LinearLayout.redeemBody(t: Theme) {
        card { head("최근 입력한 코드", t); line("PJ2KEBRAYSRM · 원석 ×60 · 10/18"); line("HOUROFRUIN · 원석 ×100 · 10/23"); line("EXPERTMITYA · 원석 ×100 · 10/23") }
        card { head("자동 입력", t); line("새 코드가 올라오면 앱이 알아서 넣어요"); line("코드 직접 입력 ›") }
    }

    private fun LinearLayout.rewardsBody(t: Theme) {
        card { head("이번 달 받은 것", t); stats(t, "140" to "원석", "25,000" to "모라", "6" to "영웅의 경험") }
        card { head("최근", t); line("10/25 출석 · 원석 ×20"); line("10/23 리딤 · 원석 ×100"); line("10/18 리딤 · 원석 ×60") }
    }

    private fun LinearLayout.pickupBody(t: Theme) {
        card { head("지금 픽업 · 11월 3일까지", t); addView(label("에스코피에 · 스커크", 18f, getColor(R.color.ink), bold = true).apply { setPadding(0, dp(8), 0, 0) }) }
        card { head("기다리는 캐릭터", t); line("나히다 · 마지막 픽업 후 9버전째"); line("호두 · 17버전째") }
    }

    private fun LinearLayout.eventsBody(t: Theme) {
        card { head("진행 중인 이벤트", t); line("웹 이벤트 · 11월 2일까지"); line("탐험 이벤트 · 11월 5일까지"); line("누르면 호요랩 페이지가 로그인된 채로 열려요") }
    }

    private fun LinearLayout.decorateBody(t: Theme, who: String) {
        card { head("최애 캐릭터", t); line("$who ›") }
        card { head("홈 스타일", t); line("● 로비형   ○ 카드형") }
        card { head("부르는 이름 · 인사말", t); line("여행자 님 · 다정하게 ›") }
    }

    private val numerals by lazy { resources.getFont(R.font.hoyo_numerals) }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false, shadow: Boolean = false, numeral: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (numeral) typeface = numerals
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
