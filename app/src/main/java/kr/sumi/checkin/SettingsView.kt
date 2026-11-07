package kr.sumi.checkin

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** 설정 화면에 보일 것. 메인 화면 상태([MainUiState])에서 만든다. */
data class SettingsUi(
    val loggedIn: Boolean,
    val nickname: String?,
    val server: String?,
    val uid: String?,
    val loginExpiry: String?,
    val needsExactAlarm: Boolean,
    val needsBattery: Boolean,
    val needsHibernation: Boolean,
    val nextRun: String?,
    /** 켜 둔 알림 수(전체 [notifyTotal] 중), 밤에는 무음인지. */
    val notifyOn: Int,
    val notifyTotal: Int,
    val quietNight: Boolean,
    /** 홈 꾸미기 타일 아래 한 줄, 예: "로비형 · 치치". */
    val homeStyle: String,
    val cardCount: Int,
    /** 앱 언어 타일에 보일 지금 언어(휴대전화 언어 또는 고른 언어). 없으면 타일을 숨긴다. */
    val language: String?,
    val gameApp: String?,
    val gameAppCount: Int,
    val version: String,
)

/** 알림 화면의 켜고 끄기 하나. */
data class NotifyToggle(val key: String, val icon: Int, val title: String, val on: Boolean)

/**
 * 설정. 줄을 늘어놓는 대신:
 * 위에 '제때 출석하려면' 상태(링으로 몇 가지 됐는지, 남은 것만 버튼), 계정 카드, 타일(알림·홈 꾸미기·메인 화면·언어),
 * 맨 아래 작은 링크. 알림은 같은 화면 안의 두 번째 쪽([showNotifications])에서 스위치로 켜고 끈다.
 */
class SettingsView(context: Context) : LinearLayout(context) {
    interface Actions {
        fun onExactAlarm()
        fun onBattery()
        fun onHibernation()
        fun onAccount()
        fun onLogin()
        fun onNotifications()
        fun onDecorate()
        fun onMainDisplay()
        fun onLanguage()
        fun onGameApp()
        fun onAbout()
        fun onPrivacy()
        fun onSource()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = context.getColor(id)

    init {
        orientation = VERTICAL
    }

    fun bind(ui: SettingsUi, actions: Actions) {
        removeAllViews()
        if (ui.loggedIn) {
            readyCard(ui, actions)
            accountCard(ui, actions)
        } else {
            addView(card {
                setOnClickListener { actions.onLogin() }
                addView(text(context.getString(R.string.main_login), 15f, color(R.color.ink), bold = true))
                addView(text(context.getString(R.string.main_settings_login_sub), 12.5f, color(R.color.ink_muted)).apply { setPadding(0, dp(3), 0, 0) })
            }, lp(top = 12))
        }
        tiles(ui, actions)
        footer(ui, actions)
    }

    /** 제때 출석하려면: 필요한 허용 중 몇 가지가 됐는지 링으로, 남은 것만 누를 수 있는 칩으로. 다 되면 초록 카드 한 장. */
    private fun readyCard(ui: SettingsUi, actions: Actions) {
        val items = listOfNotNull(
            Triple(R.string.main_chip_exact, ui.needsExactAlarm, actions::onExactAlarm),
            Triple(R.string.main_chip_battery, ui.needsBattery, actions::onBattery),
            Triple(R.string.main_chip_hibernation, ui.needsHibernation, actions::onHibernation).takeIf { Build.VERSION.SDK_INT >= 30 },
        )
        val left = items.count { it.second }
        val done = items.size - left
        val allDone = left == 0
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (allDone) intArrayOf(0xFF2E4A3C.toInt(), color(R.color.hero)) else intArrayOf(color(R.color.hero), color(R.color.hero))).apply { cornerRadius = dp(20).toFloat() }
            addView(ring(done, items.size, allDone), LayoutParams(dp(62), dp(62)))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(text(context.getString(if (allDone) R.string.main_ready_done else R.string.main_ready_todo), 15f, color(R.color.hero_ink), bold = true))
                addView(text(if (allDone) listOfNotNull(context.getString(R.string.main_ready_done_sub), ui.nextRun?.let { context.getString(R.string.main_ready_next, it) }).joinToString(" · ")
                    else context.resources.getQuantityString(R.plurals.main_ready_todo_sub, left, left), 12f, color(R.color.hero_muted)).apply { setPadding(0, dp(3), 0, 0) })
            }, LayoutParams(0, WRAP_CONTENT, 1f))
        }, lp(top = 8))
        if (allDone) return
        // 할 일 칩: 남은 것은 금색 테두리로 누를 수 있게, 끝난 것은 흐리게 ✓
        addView(FlowRow(context).apply {
            for ((label, needed, action) in items) addView(TextView(context).apply {
                text = if (needed) context.getString(label) + " ›" else "✓ " + context.getString(label)
                textSize = 12.5f
                setTextColor(color(if (needed) R.color.ink else R.color.ink_muted))
                if (needed) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(99).toFloat(); setColor(color(R.color.surface))
                    setStroke(dp(if (needed) 2 else 1), color(if (needed) R.color.gold else R.color.line))
                }
                if (needed) setOnClickListener { action() }
            })
        }, lp(top = 10))
    }

    /** 진행 링: 된 만큼 금색(다 되면 초록 ✓), 가운데 "1/3". */
    private fun ring(done: Int, total: Int, allDone: Boolean) = object : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND }
        private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.hero_ink); textAlign = android.graphics.Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val stroke = dp(6).toFloat()
            val r = android.graphics.RectF(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
            paint.strokeWidth = stroke
            paint.color = 0x26FFFFFF
            canvas.drawOval(r, paint)
            paint.color = if (allDone) 0xFF8FD19E.toInt() else context.getColor(R.color.gold)
            val sweep = if (total == 0) 360f else 360f * done / total
            if (sweep > 0) canvas.drawArc(r, -90f, sweep, false, paint)
            label.textSize = dp(if (allDone) 20 else 15).toFloat()
            val y = height / 2f - (label.descent() + label.ascent()) / 2
            canvas.drawText(if (allDone) "✓" else "$done/$total", width / 2f, y, label)
        }
    }.apply { contentDescription = "$done/$total" }

    /** 계정 카드: 이름 첫 글자 동그라미, 이름·서버, UID와 로그인 유지 기한. 누르면 계정 메뉴. */
    private fun accountCard(ui: SettingsUi, actions: Actions) = addView(card {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { actions.onAccount() }
        val name = ui.nickname ?: context.getString(R.string.main_traveler)
        addView(TextView(context).apply {
            text = name.take(1); textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF5DB7D3.toInt(), 0xFF2A6F86.toInt())).apply { shape = GradientDrawable.OVAL }
        }, LayoutParams(dp(46), dp(46)))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(text(name, 15f, color(R.color.ink), bold = true))
            addView(text(listOfNotNull(ui.server, ui.uid?.let { "UID $it" }).joinToString(" · ") +
                (ui.loginExpiry?.let { "\n" + context.getString(R.string.main_login_until, it) } ?: ""), 11.5f, color(R.color.ink_muted)).apply { setPadding(0, dp(2), 0, 0) })
        }, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(text(context.getString(R.string.main_account_more), 12.5f, color(R.color.accent), bold = true))
    }, lp(top = 14))

    /** 타일 두 칸씩: 알림 · 홈 꾸미기 · 메인 화면 · 언어. */
    private fun tiles(ui: SettingsUi, actions: Actions) {
        data class Tile(val icon: Int, val tint: Int, val title: String, val sub: String, val onClick: () -> Unit)
        val list = listOfNotNull(
            Tile(R.drawable.ic_tile_bell, color(R.color.item_mora), context.getString(R.string.main_notifications),
                context.resources.getQuantityString(R.plurals.main_tile_notify, ui.notifyOn, ui.notifyOn) +
                    if (ui.quietNight) " · " + context.getString(R.string.main_tile_quiet) else "", actions::onNotifications),
            Tile(R.drawable.ic_decorate, color(R.color.element_cryo), context.getString(R.string.main_decorate), ui.homeStyle, actions::onDecorate),
            Tile(R.drawable.ic_lobby_cards, color(R.color.item_book), context.getString(R.string.main_tile_main),
                context.resources.getQuantityString(R.plurals.main_tile_cards, ui.cardCount, ui.cardCount), actions::onMainDisplay),
            ui.language?.let { Tile(R.drawable.ic_tile_globe, color(R.color.rerun_recent), context.getString(R.string.main_language), it, actions::onLanguage) },
        )
        list.chunked(2).forEachIndexed { i, pair ->
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                pair.forEachIndexed { j, t ->
                    addView(card {
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                        contentDescription = t.title + ", " + t.sub
                        setOnClickListener { t.onClick() }
                        addView(FrameLayout(context).apply {
                            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(LobbyTheme.blend(t.tint, Color.WHITE, 0.82f)) }
                            addView(ImageView(context).apply { setImageResource(t.icon); setColorFilter(t.tint); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
                                FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                        }, LayoutParams(dp(36), dp(36)))
                        addView(text(t.title, 14f, color(R.color.ink), bold = true).apply { setPadding(0, dp(10), 0, 0) })
                        addView(text(t.sub, 11.5f, color(R.color.ink_muted)).apply { maxLines = 2; setPadding(0, dp(2), 0, 0) })
                    }, LayoutParams(0, MATCH_PARENT, 1f).apply { if (j > 0) leftMargin = dp(10) })
                }
                if (pair.size == 1) addView(View(context), LayoutParams(0, 1, 1f).apply { leftMargin = dp(10) })
            }, lp(top = if (i == 0) 12 else 10))
        }
    }

    /** 맨 아래 작은 링크: 원신 켜기 앱(둘 이상 깔렸을 때), 어떻게 동작하나요, 개인정보처리방침, 소스 코드, 버전. */
    private fun footer(ui: SettingsUi, actions: Actions) = addView(FlowRow(context, center = true).apply {
        fun link(label: String, onClick: (() -> Unit)?) = addView(text(label, 12f, color(R.color.ink_muted)).apply {
            setPadding(dp(8), dp(12), dp(8), dp(12))
            if (onClick != null) { paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG; setOnClickListener { onClick() } }
        })
        if (ui.gameApp != null && ui.gameAppCount > 1) link(context.getString(R.string.main_game_app_short, ui.gameApp), actions::onGameApp)
        link(context.getString(R.string.main_how_it_works), actions::onAbout)
        link(context.getString(R.string.main_privacy), actions::onPrivacy)
        link(context.getString(R.string.main_source), actions::onSource)
        link("v${ui.version}", null)
    }, lp(top = 14))

    /** 알림 쪽: 묶음별 카드 안에 아이콘·제목·스위치. 바꾸면 바로 저장한다([onChange]). */
    fun showNotifications(groups: List<Pair<String, List<NotifyToggle>>>, footer: String, onChange: (String, Boolean) -> Unit) {
        removeAllViews()
        for ((title, items) in groups) {
            addView(text(title, 13f, color(R.color.ink_muted), bold = true).apply { setPadding(dp(4), dp(16), 0, dp(6)) })
            addView(card {
                setPadding(0, dp(4), 0, dp(4))
                items.forEachIndexed { i, item ->
                    if (i > 0) addView(View(context).apply { setBackgroundColor(color(R.color.line)) }, LayoutParams(MATCH_PARENT, 1).apply { setMargins(dp(58), 0, dp(14), 0) })
                    addView(LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(10), dp(10), dp(10))
                        val switch = android.widget.Switch(context).apply { isChecked = item.on; isClickable = false; isFocusable = false }
                        setOnClickListener { switch.isChecked = !switch.isChecked; onChange(item.key, switch.isChecked) }
                        contentDescription = item.title
                        addView(FrameLayout(context).apply {
                            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.item_tile)) }
                            addView(ImageView(context).apply { setImageResource(item.icon); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
                                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                        }, LayoutParams(dp(34), dp(34)))
                        addView(text(item.title, 14f, color(R.color.ink)).apply { setPadding(dp(12), 0, dp(8), 0) }, LayoutParams(0, WRAP_CONTENT, 1f))
                        addView(switch)
                    })
                }
            }, lp(top = 0))
        }
        addView(text(footer, 12f, color(R.color.ink_muted)).apply { setPadding(dp(4), dp(14), dp(4), 0); setLineSpacing(dp(2).toFloat(), 1f) })
    }

    private fun card(build: LinearLayout.() -> Unit) = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(color(R.color.surface)); cornerRadius = dp(18).toFloat() }
        build()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun lp(top: Int) = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    /** 칩·링크를 한 줄에 놓다가 넘치면 다음 줄로. */
    private class FlowRow(context: Context, private val center: Boolean = false) : android.view.ViewGroup(context) {
        private val gap = (6 * context.resources.displayMetrics.density).toInt()
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxW = MeasureSpec.getSize(widthMeasureSpec)
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
                x += c.measuredWidth + gap
                rowH = maxOf(rowH, c.measuredHeight)
            }
            setMeasuredDimension(maxW, y + rowH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l
            val rows = mutableListOf<MutableList<View>>(mutableListOf())
            var x = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > maxW) { rows += mutableListOf<View>(); x = 0 }
                rows.last() += c
                x += c.measuredWidth + gap
            }
            var y = 0
            for (row in rows) {
                val rowW = row.sumOf { it.measuredWidth } + gap * (row.size - 1)
                var cx = if (center) (maxW - rowW) / 2 else 0
                val rowH = row.maxOfOrNull { it.measuredHeight } ?: 0
                for (c in row) { c.layout(cx, y, cx + c.measuredWidth, y + c.measuredHeight); cx += c.measuredWidth + gap }
                y += rowH + gap
            }
        }
    }
}
