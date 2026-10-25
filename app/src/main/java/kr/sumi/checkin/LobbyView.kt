package kr.sumi.checkin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** 최애 원소 색 묶음: 짙은 색(바탕), 원소 색, 중간 색(글자 강조), 파스텔(밝은 강조), 아주 옅은 색(메뉴 본문 바탕). */
class LobbyTheme(val el: Int) {
    val dark = blend(el, Color.BLACK, 0.72f)
    val deep = blend(el, Color.BLACK, 0.38f)
    val light = blend(el, Color.WHITE, 0.78f)
    val tint = blend(el, Color.WHITE, 0.92f)

    companion object {
        fun blend(a: Int, b: Int, t: Float) = Color.rgb(
            (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
        )

        fun alpha(c: Int, a: Int) = (c and 0xFFFFFF) or (a shl 24)
    }
}

/** 아래 패널의 상태. */
enum class DockTone { DONE, WAIT, WARN, BUSY }

/** 로비에 그릴 것. 모두 액티비티가 지금 계정 상태로 만든다. */
data class LobbyUi(
    val name: String,
    val element: String,
    /** 이름 아래 한 줄, 예: "풀 · Lv.90 · 호감도 10 · 운명의 자리 2". */
    val meta: String,
    val greeting: String,
    /** 레진 표시. 실시간 메모를 못 받았으면 null(표시를 숨긴다). */
    val resin: Int? = null,
    val maxResin: Int = 200,
    val totalDays: Int = 0,
    val dockTone: DockTone = DockTone.DONE,
    val dockTitle: String,
    val dockSub: String? = null,
    /** 오른쪽 큰 글씨(레진 가득 시각, 예: "3:20")와 그 아래 설명. */
    val dockBig: String? = null,
    val dockBigLabel: String? = null,
    val dockButton: String? = null,
    val busyProgress: Float? = null,
    val redeemDot: Boolean = false,
    val rewardsDot: Boolean = false,
    val eventsDot: Boolean = false,
    /** 공식 그림을 배경으로 쓰는 중이면 출처를 작게 적는다. */
    val credit: Boolean = false,
    /** 계정이 여럿이면 지금 계정 이름(누르면 바꾼다). 하나뿐이면 null. */
    val account: String? = null,
)

/**
 * 로비형 홈. 스크롤 없이 한 장: 배경(공식 그림·내 사진·원소 무늬), 위 레진·출석 표시, 말풍선, 오른쪽 메뉴,
 * 이름표, 아래 패널. 자세한 것은 누르면 들어가는 메뉴 화면에서 본다(게임 로비처럼).
 */
class LobbyView(context: Context) : FrameLayout(context) {
    interface Actions {
        fun onResin()
        fun onCheckIn()
        fun onDockButton()
        fun onMenu(key: String)
        fun onSettings()
        fun onAccount()
    }

    companion object {
        const val MENU_REDEEM = "redeem"
        const val MENU_REWARDS = "rewards"
        const val MENU_PICKUP = "pickup"
        const val MENU_EVENTS = "events"
        const val MENU_CARDS = "cards"
        const val MENU_DECORATE = "decorate"
    }

    private val numerals: Typeface = context.resources.getFont(R.font.hoyo_numerals)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        isClickable = true
    }

    private var shownArt: Bitmap? = null
    private var bound = false
    private var breathing: android.animation.ValueAnimator? = null

    override fun onDetachedFromWindow() {
        breathing?.cancel()
        super.onDetachedFromWindow()
    }

    fun bind(ui: LobbyUi, art: Bitmap?, actions: Actions) {
        breathing?.cancel()
        removeAllViews()
        val t = LobbyTheme(context.getColor(Lobby.elementColorRes(ui.element)))
        setBackgroundColor(t.dark)
        val w = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        // 오른쪽 메뉴는 남는 높이에 맞춰 크기·간격·이름표를 고른다(이름표·아래 패널과 겹치지 않게).
        val hDp = h / resources.displayMetrics.density
        val menuTop = if (hDp < 720) 112 else 136
        val room = hDp - menuTop - 290 // 아래 이름표·패널·출처 몫
        // (아이콘 크기, 간격, 이름 보이기) 중 6개가 들어가는 가장 큰 것
        val (iconSize, gap, labels) = listOf(Triple(46, 10, true), Triple(40, 6, true), Triple(38, 4, false), Triple(32, 3, false))
            .firstOrNull { (s, g, l) -> 6 * (s + 2 + g + if (l) 15 else 0) <= room } ?: Triple(30, 2, false)

        // 배경: 그림은 화면 폭에 맞춰 위에, 없으면 원소 무늬
        val artBottom: Int
        val hadArt = shownArt
        shownArt = art
        if (art != null) {
            val imgH = w * art.height / art.width
            val image = ImageView(context).apply { setImageBitmap(art); scaleType = ImageView.ScaleType.FIT_XY; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
            addView(image, LayoutParams(MATCH_PARENT, imgH))
            artBottom = minOf(imgH, (h * 0.76f).toInt())
            // 새 그림이면 부드럽게 나타나고, 그림은 숨 쉬듯 아주 천천히 커졌다 작아진다.
            // 무늬로 그려 두었다가 그림이 도착했을 때만 서서히(처음부터 있던 그림은 바로).
            if (bound && hadArt == null) { image.alpha = 0f; image.animate().alpha(1f).setDuration(400).start() }
            image.pivotX = w / 2f
            image.pivotY = 0f
            breathing = android.animation.ValueAnimator.ofFloat(1f, 1.03f).apply {
                duration = 7000
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { v -> (v.animatedValue as Float).let { image.scaleX = it; image.scaleY = it } }
                if (android.animation.ValueAnimator.areAnimatorsEnabled()) start()
            }
        } else {
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.deep, LobbyTheme.blend(t.el, Color.BLACK, 0.55f), t.dark))
            }, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(ImageView(context).apply {
                setImageResource(Lobby.elementIcon(ui.element))
                setColorFilter(Color.WHITE)
                alpha = 0.10f
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams((w * 0.9f).toInt(), (w * 0.9f).toInt(), Gravity.CENTER_HORIZONTAL).apply { topMargin = (h * 0.14f).toInt() })
            artBottom = (h * 0.7f).toInt()
        }
        // 위: 상단 표시가 읽히게 원소 색으로 감싼다. 아래: 그림 끝을 원소 색으로 잇는다.
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(LobbyTheme.alpha(t.dark, 235), LobbyTheme.alpha(t.dark, 190), LobbyTheme.alpha(t.dark, 0)))
        }, LayoutParams(MATCH_PARENT, dp(200)))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(LobbyTheme.alpha(t.dark, 0), t.dark))
        }, LayoutParams(MATCH_PARENT, dp(240)).apply { topMargin = artBottom - dp(240) })
        addView(View(context).apply { setBackgroundColor(t.dark) }, LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = artBottom })

        // 위: 레진·출석 표시, 설정
        val hud = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (ui.resin != null) hud.addView(pill(R.drawable.ic_lobby_moon, "${ui.resin}", "/${ui.maxResin}", t, "레진 ${ui.resin}개, 자세히",
            full = ui.resin >= ui.maxResin) { actions.onResin() })
        hud.addView(pill(R.drawable.ic_lobby_check, "${ui.totalDays}", "일째", t, "이번 달 ${ui.totalDays}일 출석, 달력 보기") { actions.onCheckIn() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (ui.resin != null) leftMargin = dp(8) })
        hud.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        ui.account?.let { name ->
            hud.addView(text("$name ▾", 12.5f, Color.WHITE, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(110)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                contentDescription = "지금 계정 $name, 바꾸기"
                background = GradientDrawable().apply { setColor(0x30FFFFFF); setStroke(dp(1), 0x40FFFFFF); cornerRadius = dp(99).toFloat() }
                setOnClickListener { actions.onAccount() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
        hud.addView(roundIcon(R.drawable.ic_settings, 40, "설정") { actions.onSettings() })
        addView(hud, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(16), dp(12), dp(16), 0) })

        // 말풍선
        addView(text(ui.greeting, 13.5f, 0xFF1B2030.toInt()).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setLineSpacing(dp(2).toFloat(), 1f)
            val r = dp(14).toFloat()
            background = GradientDrawable().apply { setColor(0xEEFFFFFF.toInt()); cornerRadii = floatArrayOf(r, r, r, r, r, r, dp(4).toFloat(), dp(4).toFloat()) }
        }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(16), dp(68), dp(86), 0) })

        // 오른쪽 메뉴
        val side = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for ((icon, label, key, dot) in listOf(
            Menu(R.drawable.ic_item_gift, "리딤", MENU_REDEEM, ui.redeemDot),
            Menu(R.drawable.ic_item_primogem, "보상", MENU_REWARDS, ui.rewardsDot),
            Menu(R.drawable.ic_rerun, "픽업", MENU_PICKUP, false),
            Menu(R.drawable.ic_events, "이벤트", MENU_EVENTS, ui.eventsDot),
            Menu(R.drawable.ic_lobby_cards, "카드", MENU_CARDS, false),
            Menu(R.drawable.ic_edit, "꾸미기", MENU_DECORATE, false),
        )) {
            val size = iconSize
            side.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), 0, dp(2), dp(gap))
                contentDescription = label
                setOnClickListener { actions.onMenu(key) }
                addView(FrameLayout(context).apply {
                    addView(roundIcon(icon, size, null, null), LayoutParams(dp(size), dp(size)))
                    if (dot) addView(View(context).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF6B4A.toInt()); setStroke(dp(2), Color.WHITE) }
                    }, LayoutParams(dp(12), dp(12), Gravity.END))
                }, LinearLayout.LayoutParams(dp(size + 2), dp(size + 2)))
                if (labels) addView(text(label, 10.5f, Color.WHITE, bold = true, shadow = true))
            })
        }
        addView(side, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END).apply { setMargins(0, dp(menuTop), dp(10), 0) })

        // 이름표 + 아래 패널 (+ 출처)
        val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(16))
            addView(text("★ 최애", 11f, t.dark, bold = true).apply {
                setPadding(dp(9), dp(3), dp(9), dp(3))
                background = GradientDrawable().apply { setColor(t.light); cornerRadius = dp(99).toFloat() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(text(ui.name, when { ui.name.length > 6 -> 26f; ui.name.length > 4 -> 30f; else -> 34f }, Color.WHITE, bold = true, shadow = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageResource(Lobby.elementIcon(ui.element)); setColorFilter(t.light) }, LinearLayout.LayoutParams(dp(14), dp(14)))
                addView(text(ui.meta, 12.5f, Color.WHITE, shadow = true).apply { setPadding(dp(6), 0, 0, 0) })
            })
        })
        bottom.addView(dock(ui, t, actions), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(14), 0, dp(14), 0) })
        bottom.addView(text(if (ui.credit) "그림 © HoYoverse · 호요랩 위키 · 비공식 팬 도구" else "비공식 팬 도구", 10f, 0x88FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(bottom, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        bound = true
    }

    private data class Menu(val icon: Int, val label: String, val key: String, val dot: Boolean)

    /** 아래 패널: 상태(완료·출석 전·문제·진행 중) + 오른쪽 큰 글씨 + 버튼. */
    private fun dock(ui: LobbyUi, t: LobbyTheme, actions: Actions) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        val warn = ui.dockTone == DockTone.WARN
        background = GradientDrawable().apply {
            setColor(if (warn) 0x55B04A2A else 0x2EFFFFFF)
            setStroke(dp(1), if (warn) 0x99FFB08A.toInt() else 0x40FFFFFF)
            cornerRadius = dp(24).toFloat()
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { actions.onCheckIn() }
            val badge = when (ui.dockTone) {
                DockTone.DONE -> R.drawable.ic_lobby_check
                DockTone.WAIT -> R.drawable.ic_lobby_wait
                DockTone.WARN -> R.drawable.ic_lobby_alert
                DockTone.BUSY -> R.drawable.ic_lobby_wait
            }
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (warn) 0xFFFFD2BC.toInt() else t.light) }
                addView(ImageView(context).apply { setImageResource(badge); setColorFilter(if (warn) 0xFF7A2A10.toInt() else t.dark) },
                    LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(text(ui.dockTitle, 15.5f, Color.WHITE, bold = true))
                ui.dockSub?.let { addView(text(it, 11.5f, 0xDDFFFFFF.toInt()).apply {
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }) }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (ui.dockBig != null) addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(text(ui.dockBig, 30f, t.light, numeral = true))
                ui.dockBigLabel?.let { addView(text(it, 10.5f, 0xDDFFFFFF.toInt())) }
            })
        })
        if (ui.dockTone == DockTone.BUSY) {
            addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = ui.busyProgress == null
                max = 100
                progress = ((ui.busyProgress ?: 0f) * 100).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(t.light)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        ui.dockButton?.let { label ->
            addView(text(label, 15f, if (warn) Color.WHITE else t.dark, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(13), 0, dp(13))
                background = GradientDrawable().apply { setColor(if (warn) 0xFFB04A2A.toInt() else t.light); cornerRadius = dp(15).toFloat() }
                setOnClickListener { actions.onDockButton() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }
    }

    private fun pill(icon: Int, value: String, unit: String, t: LobbyTheme, description: String, full: Boolean = false, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(5), dp(5), dp(12), dp(5))
        contentDescription = description
        // 레진이 가득 찼으면 금색 테두리로 눈에 띄게
        background = GradientDrawable().apply { setColor(if (full) 0x55E1BB66 else 0x30FFFFFF); setStroke(dp(if (full) 2 else 1), if (full) 0xFFE1BB66.toInt() else 0x40FFFFFF); cornerRadius = dp(99).toFloat() }
        setOnClickListener { onClick() }
        addView(FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            addView(ImageView(context).apply { setImageResource(icon); setColorFilter(t.deep) }, LayoutParams(dp(15), dp(15), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(text(" $value", 17f, Color.WHITE, numeral = true))
        addView(text(unit, 12f, 0xDDFFFFFF.toInt()))
    }

    private fun roundIcon(icon: Int, size: Int, description: String?, onClick: (() -> Unit)? = null) = FrameLayout(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x7A0C121C); setStroke(dp(1), 0x40FFFFFF) }
        contentDescription = description
        onClick?.let { action -> setOnClickListener { action() } }
        addView(ImageView(context).apply { setImageResource(icon); setColorFilter(Color.WHITE) }, LayoutParams(dp(size * 46 / 100), dp(size * 46 / 100), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, shadow: Boolean = false, numeral: Boolean = false) = TextView(context).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (numeral) typeface = numerals
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (shadow) setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
    }
}

/**
 * 로비에서 들어가는 메뉴 화면: 최애 원소 색 머리(제목, 최애 얼굴 그림) + 옅은 원소 색 본문.
 * 게임에서 로비 메뉴에 들어간 것처럼 오른쪽에서 들어오고, 뒤로 가기로 로비에 돌아간다.
 */
class LobbyMenu(
    context: Context, title: String, private val t: LobbyTheme, bust: Bitmap?, who: String, element: String, onBack: () -> Unit,
) : FrameLayout(context) {
    private val numerals: Typeface = context.resources.getFont(R.font.hoyo_numerals)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    // 다크 모드면 본문 바탕·카드·옅은 칸을 어둡게, 강조 글자는 밝게.
    private val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val pageBg = if (night) LobbyTheme.blend(t.el, Color.BLACK, 0.84f) else t.tint
    private val cardBg = if (night) LobbyTheme.blend(t.el, Color.BLACK, 0.74f) else Color.WHITE
    private val soft = if (night) LobbyTheme.blend(t.el, Color.BLACK, 0.6f) else t.tint
    private val accent = if (night) t.light else t.deep
    val body: LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(40)) }

    init {
        isClickable = true
        setBackgroundColor(pageBg)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(FrameLayout(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.deep, t.dark))
            // 머리 뒤에 원소 문양을 옅게
            addView(ImageView(context).apply {
                setImageResource(Lobby.elementIcon(element))
                setColorFilter(Color.WHITE)
                alpha = 0.10f
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(170), dp(170), Gravity.END or Gravity.TOP).apply { setMargins(0, -dp(30), -dp(30), 0) })
            if (bust != null) addView(ImageView(context).apply { setImageBitmap(bust); scaleType = ImageView.ScaleType.FIT_END; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
                LayoutParams(dp(150), dp(150), Gravity.END or Gravity.BOTTOM))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(20))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    contentDescription = "로비로"
                    setPadding(0, dp(6), dp(20), dp(10))
                    setOnClickListener { onBack() }
                    addView(ImageView(context).apply { setImageResource(R.drawable.ic_back); setColorFilter(Color.WHITE) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                    addView(text("로비", 14f, 0xDDFFFFFF.toInt(), bold = true).apply { setPadding(dp(4), 0, 0, 0) })
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                addView(text(title, 28f, Color.WHITE, bold = true))
                addView(text("$who 와 함께", 12.5f, t.light).apply { setPadding(0, dp(4), 0, 0) })
            }, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(176)))
        col.addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addView(col, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** 흰 카드 하나를 본문에 더한다. */
    fun card(build: LinearLayout.() -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply { setColor(cardBg); cornerRadius = dp(20).toFloat() }
        build()
    }.also { body.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }) }

    fun LinearLayout.head(s: String) = addView(text(s, 13f, accent, bold = true))

    fun LinearLayout.line(s: String, onClick: (() -> Unit)? = null) = addView(text(s, 14f, if (onClick != null) accent else context.getColor(R.color.ink), bold = onClick != null).apply {
        setPadding(0, dp(9), 0, 0)
        onClick?.let { action -> setOnClickListener { action() } }
    })

    /** 큰 숫자 여러 개를 나란히, 예: (4/5, 탐사 파견). */
    fun LinearLayout.stats(vararg items: Pair<String, String>) = addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, 0)
        for ((v, u) in items) addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(v, 24f, context.getColor(R.color.ink), numeral = true))
            addView(text(u, 11f, context.getColor(R.color.ink_muted)))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    })

    /** 큰 숫자 + 막대(레진 등). */
    fun LinearLayout.meter(value: Int, max: Int, right: String) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(6), 0, 0)
            addView(text("$value", 44f, context.getColor(R.color.ink), numeral = true))
            addView(text(" / $max", 18f, context.getColor(R.color.ink_muted), numeral = true).apply { setPadding(0, 0, 0, dp(8)) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(text(right, 14f, accent, bold = true).apply { setPadding(0, 0, 0, dp(10)) })
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(soft); cornerRadius = dp(4).toFloat() }
            val filled = value.coerceIn(0, max)
            if (filled > 0) addView(View(context).apply { background = GradientDrawable().apply { setColor(t.el); cornerRadius = dp(4).toFloat() } },
                LinearLayout.LayoutParams(0, MATCH_PARENT, filled.toFloat()))
            if (max - filled > 0) addView(View(context), LinearLayout.LayoutParams(0, MATCH_PARENT, (max - filled).toFloat()))
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(8)).apply { topMargin = dp(6) })
    }

    /** 출석 칸: 받은 칸은 원소 색, 오늘 받을 칸은 테두리, 못 받는 칸은 흐리게. labels는 칸마다 짧은 보상 글자. */
    fun LinearLayout.calendar(labels: List<String>, signed: Int, today: Int, reachable: Int) {
        var row: LinearLayout? = null
        val count = maxOf(labels.size, 1)
        val cells = (count + 6) / 7 * 7
        for (i in 0 until cells) {
            if (i % 7 == 0) row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, 0) }.also { addView(it) }
            val n = i + 1
            val done = n <= signed
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                if (n <= count) {
                    background = GradientDrawable().apply {
                        setColor(if (done) t.el else soft)
                        if (n == today) setStroke(dp(2), t.el)
                        cornerRadius = dp(10).toFloat()
                    }
                    alpha = if (!done && n > reachable) 0.4f else 1f
                    addView(text("$n", 12f, if (done) Color.WHITE else context.getColor(R.color.ink_muted), numeral = true).apply { gravity = Gravity.CENTER })
                    labels.getOrNull(i)?.let { addView(text(it, 9f, if (done) 0xDDFFFFFF.toInt() else context.getColor(R.color.ink_muted)).apply { gravity = Gravity.CENTER }) }
                }
            }
            row!!.addView(cell, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, numeral: Boolean = false) = TextView(context).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (numeral) typeface = numerals
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
