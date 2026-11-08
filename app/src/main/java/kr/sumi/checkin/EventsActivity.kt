package kr.sumi.checkin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
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
    // 앱 안에서 고른 언어로(Android 12까지. 13부터는 시스템이 한다).
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLocale.wrap(base))

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var opening = false
    private var renders = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.drawBehindSystemBars(window)
        val header = LayoutInflater.from(this).inflate(R.layout.header_bar, null)
        header.findViewById<TextView>(R.id.headerTitle).text = getString(R.string.events_title)
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
        LobbyMenu.dress(this, header as LinearLayout, root, getString(R.string.events_title)) { finish() }
        setContentView(root)
        MainActivity.applySystemBarInsets(root)
        render()
        Events.markSeen(this)
        if (!Events.isFresh(this) || Events.cached(this).isEmpty()) Thread {
            val error = try { Events.refresh(this, force = true); null } catch (e: IOException) { e.message ?: getString(R.string.events_network_error) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Events.markSeen(this)
                render(error)
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render(error: String? = null) {
        val events = Events.live(this)
        status.text = when {
            events.isNotEmpty() -> getString(R.string.events_status)
            error != null -> getString(R.string.events_load_failed, error)
            Events.cached(this).isEmpty() -> getString(R.string.events_loading)
            else -> getString(R.string.events_none)
        }
        if (events.isEmpty()) { renders++; list.removeAllViews(); return }
        // 이미 그려 둔 목록은 새 배너를 받는 동안 그대로 둔다. 배너를 받은 뒤 한 번에 그린다(오래 걸리면 3초 뒤 남은 그림은 자리만 두고 그린다).
        val generation = ++renders
        if (list.childCount == 0) list.addView(android.widget.ProgressBar(this), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(48)
        })
        EventBanners.prefetch(this, events.map { it.banner }, 3_000) {
            if (isFinishing || generation != renders) return@prefetch
            list.removeAllViews()
            list.addView(EventsList(this, ServerClock.now()) { open(it) }.build(events))
        }
    }

    /** 안내 글이면 글 안의 웹이벤트 주소를 찾아(네트워크) 지금 계정으로 연다. 로그인 전이면 브라우저로. */
    private fun open(e: GameEvent) {
        if (opening) return
        opening = true
        Thread {
            val url = Events.target(e, Events.cached(this))
            runOnUiThread {
                opening = false
                val account = AppSettings(this).current()?.accountId
                if (account != null) {
                    startActivity(LoginActivity.pageIntent(this, account, url, getString(R.string.events_title)))
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

/**
 * 이벤트 카드들을 그린다(화면 스냅숏 테스트에서도 쓰려고 액티비티와 나눴다). nowMs는 남은 날 계산 기준.
 * images는 배너 그림을 넣는 함수(앱에서는 EventBanners가 받아서 넣고, 테스트에서는 비워 둔다).
 */
class EventsList(
    private val context: Context,
    private val nowMs: Long,
    private val images: (String, ImageView) -> Unit = { url, view -> EventBanners.load(context, url, view) },
    private val onOpen: (GameEvent) -> Unit,
) {
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun build(events: List<GameEvent>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        for (e in events) addView(card(e), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })
    }

    private fun card(e: GameEvent) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_group)
        clipToOutline = true
        foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
        setOnClickListener { onOpen(e) }
        contentDescription = e.name
        addView(FrameLayout(context).apply {
            addView(RatioImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(context.getColor(R.color.hero)) // 그림을 받기 전·못 받았을 때 자리
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                images(e.banner, this)
            }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            daysLeft(e)?.let { left ->
                val soon = left <= 3
                addView(TextView(context).apply {
                    text = if (left <= 0) context.getString(R.string.events_ends_today)
                        else context.resources.getQuantityString(R.plurals.events_days_left, left.toInt(), left.toInt())
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (soon) context.getColor(R.color.on_gold) else Color.WHITE)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (soon) context.getColor(R.color.gold) else 0xB3141A2E.toInt())
                    }
                }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                    setMargins(dp(10), dp(10), 0, 0)
                })
            }
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            val tags = tags(e)
            if (tags.isNotEmpty()) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((label, bg, fg) in tags) addView(TextView(context).apply {
                    text = label
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(context.getColor(fg))
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                    background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(context.getColor(bg)) }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6); bottomMargin = dp(6) })
            })
            addView(TextView(context).apply {
                text = e.name
                textSize = 15f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getColor(R.color.ink))
            })
            addView(TextView(context).apply {
                text = until(e)
                textSize = 12f
                setTextColor(context.getColor(R.color.ink_muted))
                setPadding(0, dp(4), 0, 0)
            })
        })
    }

    /** 주소와 제목으로 고른 표시: 웹이벤트, 원석 보상. */
    private fun tags(e: GameEvent) = listOfNotNull(
        Triple(context.getString(R.string.events_tag_web), R.color.hero, R.color.hero_ink).takeIf { Events.isWebEvent(e) },
        Triple(context.getString(R.string.events_tag_primogem), R.color.gold, R.color.on_gold).takeIf { Events.hasPrimogems(e) },
    )

    private fun daysLeft(e: GameEvent): Long? =
        if (e.end == 0L) null else java.time.Duration.between(java.time.Instant.ofEpochMilli(nowMs), java.time.Instant.ofEpochSecond(e.end)).toDays()

    private fun until(e: GameEvent): String {
        if (e.end == 0L) return context.getString(R.string.events_permanent)
        // 이벤트는 서버 시각(UTC+8) 23:59에 끝나는 일이 많아, 한국 시간으로 적으면 다음 날 새벽 0:59가 된다. 서버 날짜로 적는다.
        val end = java.time.Instant.ofEpochSecond(e.end).atZone(ServerClock.ZONE)
        return context.getString(R.string.events_until, end.monthValue, end.dayOfMonth,
            end.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH))
    }
}

/** 폭에 맞춰 16:9 높이를 잡는 그림(호요랩 이벤트 배너 비율). */
private class RatioImageView(context: Context) : ImageView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 9 / 16)
    }
}
