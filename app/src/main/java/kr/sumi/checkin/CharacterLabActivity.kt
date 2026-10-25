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

    private fun fetch(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** 로비형 홈 미리보기: 그림을 화면 가득(누르면 잘라 채우기 ↔ 전체 보기), 위아래 어둡게, 정보는 예시 문구. */
    private fun showLobby(bmp: Bitmap, c: JSONObject) {
        val root = window.decorView.findViewById<FrameLayout>(android.R.id.content)
        val tint = elementColor(c.optString("element"))
        val view = FrameLayout(this).apply {
            setBackgroundColor(tint)
            isClickable = true
            val image = ImageView(context).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            image.setOnClickListener {
                image.scaleType = if (image.scaleType == ImageView.ScaleType.CENTER_CROP) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            }
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x66000000, 0x00000000, 0x00000000, 0xCC0A140A.toInt()))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(TextView(context).apply {
                text = "☾ 142/200    ✓ 17일째"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply { setColor(0x55141C28); cornerRadius = dp(99).toFloat() }
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(16), dp(48), 0, 0) })
            addView(TextView(context).apply {
                text = "좋은 아침이에요, 여행자 님.\n오늘 출석이랑 새 코드 1개 받아 뒀어요."
                textSize = 14f
                setTextColor(getColor(R.color.ink))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply { setColor(0xEEFFFFFF.toInt()); cornerRadius = dp(16).toFloat() }
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(16), dp(100), dp(80), 0) })
            addView(TextView(context).apply {
                text = "오늘 출석 완료 · 원석 ×20\n레진 가득 오후 3:20\n\n(그림을 누르면 잘라 채우기 ↔ 전체 보기, 뒤로 가기로 닫기)"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = GradientDrawable().apply { setColor(0x66141C28); cornerRadius = dp(22).toFloat() }
            }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(40)) })
        }
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        preview = view
    }

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
        private const val BASE = "https://bbs-api-os.hoyolab.com/game_record/genshin/api"
    }
}
