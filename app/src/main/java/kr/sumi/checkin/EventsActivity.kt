package kr.sumi.checkin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

/**
 * 진행 중인 호요랩 원신 이벤트 목록. 누르면 지금 계정으로 로그인된 채 앱 안에서 연다.
 * 보상 받기는 사람이 직접 누른다(이벤트마다 방식이 달라 자동으로 받지 않는다).
 */
class EventsActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.drawBehindSystemBars(window)
        val header = LayoutInflater.from(this).inflate(R.layout.header_bar, null)
        header.findViewById<TextView>(R.id.headerTitle).text = "이벤트"
        header.findViewById<View>(R.id.headerBack).setOnClickListener { finish() }
        status = header.findViewById(R.id.headerStatus)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.bg)
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(this@EventsActivity).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        MainActivity.applySystemBarInsets(root)
        render()
        Events.markSeen(this)
        if (!Events.isFresh(this) || Events.cached(this).isEmpty()) Thread {
            val error = try { Events.refresh(this, force = true); null } catch (e: IOException) { e.message ?: "네트워크 오류" }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Events.markSeen(this)
                render(error)
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render(error: String? = null) {
        list.removeAllViews()
        val events = Events.live(this)
        status.text = when {
            events.isNotEmpty() -> "호요랩의 원신 이벤트예요. 누르면 로그인된 상태로 열려요. 보상 받기는 직접 눌러 주세요."
            error != null -> "불러오지 못했어요: $error"
            Events.cached(this).isEmpty() -> "불러오는 중..."
            else -> "지금 진행 중인 이벤트가 없어요."
        }
        if (events.isEmpty()) return
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_group)
            clipToOutline = true
            dividerDrawable = getDrawable(R.drawable.divider_inset)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            for (e in events) addView(row(e))
        })
    }

    private fun row(e: GameEvent) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(12), dp(14))
        foreground = getDrawable(android.R.drawable.list_selector_background)
        setOnClickListener { open(e) }
        addView(LinearLayout(this@EventsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@EventsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((label, bg, fg) in tags(e)) addView(TextView(this@EventsActivity).apply {
                    text = label
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(fg))
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                    background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(getColor(bg)) }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6); bottomMargin = dp(6) })
            })
            addView(TextView(this@EventsActivity).apply {
                text = e.name
                textSize = 15f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.ink))
            })
            addView(TextView(this@EventsActivity).apply {
                text = period(e)
                textSize = 12f
                setTextColor(getColor(R.color.ink_muted))
                setPadding(0, dp(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(android.widget.ImageView(this@EventsActivity).apply {
            setImageResource(R.drawable.ic_chevron)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
    }

    /** 제목으로 고른 표시: 웹이벤트, 원석 보상. */
    private fun tags(e: GameEvent) = listOfNotNull(
        Triple("웹이벤트", R.color.hero, R.color.hero_ink).takeIf { e.path.startsWith("http") || "웹이벤트" in e.name || "웹 이벤트" in e.name },
        Triple("원석", R.color.gold, R.color.on_gold).takeIf { "원석" in e.name },
    )

    private fun period(e: GameEvent): String {
        if (e.end == 0L) return "상시"
        val zone = java.time.ZoneId.systemDefault()
        val end = java.time.Instant.ofEpochSecond(e.end).atZone(zone)
        val left = java.time.Duration.between(java.time.Instant.ofEpochMilli(ServerClock.now()), end.toInstant()).toDays()
        return "${end.monthValue}월 ${end.dayOfMonth}일까지 · " + if (left <= 0) "오늘 끝나요" else "${left}일 남음"
    }

    /** 안내 글이면 글 안의 웹이벤트 주소를 찾아(네트워크) 지금 계정으로 연다. 로그인 전이면 브라우저로. */
    private fun open(e: GameEvent) {
        if (opening) return
        opening = true
        Thread {
            val url = Events.target(e)
            runOnUiThread {
                opening = false
                val account = AppSettings(this).current()?.accountId
                if (account != null) {
                    startActivity(LoginActivity.pageIntent(this, account, url, "이벤트"))
                } else try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (x: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, url, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    companion object {
        fun intent(context: Context) = Intent(context, EventsActivity::class.java)
    }
}
