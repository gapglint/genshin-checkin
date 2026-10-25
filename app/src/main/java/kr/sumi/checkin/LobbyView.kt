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
    /** 최애의 대사(호요랩 위키). 없으면 greeting만 보인다. */
    val quote: String? = null,
    /** 대사에 음성이 있으면 말풍선에 스피커 표시(누르면 듣는다). */
    val voice: Boolean = false,
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
        /** 말풍선을 누르면 다른 대사(음성이 있으면 들려준다). */
        fun onBubble()
    }

    companion object {
        const val MENU_REDEEM = "redeem"
        const val MENU_REWARDS = "rewards"
        const val MENU_PICKUP = "pickup"
        const val MENU_EVENTS = "events"
        const val MENU_CARDS = "cards"
        const val MENU_DECORATE = "decorate"
    }

    /** 눌렀을 때 살짝 위로 들썩였다 돌아온다(애니메이션을 끈 기기에서는 그대로). */
    private fun hop(v: View) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return
        v.animate().cancel()
        v.animate().translationY(-dp(6).toFloat()).setDuration(110).withEndAction {
            v.animate().translationY(0f).setDuration(180).setInterpolator(android.view.animation.OvershootInterpolator()).start()
        }.start()
    }

    private val numerals: Typeface = context.resources.getFont(R.font.hoyo_numerals)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 상태바·내비게이션 바 높이. 그림은 화면 끝까지 채우고, 누르는 것과 글자만 이만큼 안쪽에 둔다. */
    private var insetTop = 0
    private var insetBottom = 0
    private var lastBind: (() -> Unit)? = null
    /** '전체 보기'로 펼친 긴 대사. */
    private var expandedQuote: String? = null

    init {
        isClickable = true
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()).let { it.top to it.bottom }
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            if (bars.first != insetTop || bars.second != insetBottom) {
                insetTop = bars.first
                insetBottom = bars.second
                lastBind?.invoke()
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    private var shownArt: Bitmap? = null
    private var bound = false
    private var breathing: android.animation.ValueAnimator? = null

    override fun onDetachedFromWindow() {
        breathing?.cancel()
        super.onDetachedFromWindow()
    }

    fun bind(ui: LobbyUi, art: Bitmap?, actions: Actions) {
        lastBind = { bind(ui, art, actions) }
        breathing?.cancel()
        removeAllViews()
        val t = LobbyTheme(context.getColor(Lobby.elementColorRes(ui.element)))
        setBackgroundColor(t.dark)
        val w = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        // 오른쪽 메뉴는 남는 높이에 맞춰 크기·간격·이름표를 고른다(이름표·아래 패널과 겹치지 않게).
        val top = insetTop / resources.displayMetrics.density
        val hDp = (h - insetTop - insetBottom) / resources.displayMetrics.density
        val menuTop = (if (hDp < 720) 112 else 136) + top.toInt()
        val room = hDp - menuTop - 290 // 아래 이름표·패널·출처 몫
        // (아이콘 크기, 간격, 이름 보이기) 중 메뉴 5개가 들어가는 가장 큰 것
        val (iconSize, gap, labels) = listOf(Triple(46, 10, true), Triple(40, 6, true), Triple(38, 4, false), Triple(32, 3, false))
            .firstOrNull { (s, g, l) -> 5 * (s + 2 + g + if (l) 15 else 0) <= room } ?: Triple(30, 2, false)

        // 배경: 그림은 화면 폭에 맞춰 위에, 없으면 원소 무늬
        val artBottom: Int
        val hadArt = shownArt
        shownArt = art
        if (art != null) {
            val imgH = w * art.height / art.width
            // 캐릭터를 누르면 말풍선을 누른 것처럼 다른 대사(음성). 살짝 들썩여 반응한다. 화면 읽기는 말풍선으로 같은 일을 한다.
            val image = ImageView(context).apply {
                setImageBitmap(art); scaleType = ImageView.ScaleType.FIT_XY; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnClickListener { hop(this); actions.onBubble() }
            }
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
                setOnClickListener { hop(this); actions.onBubble() }
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
        addView(hud, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(16), insetTop + dp(12), dp(16), 0) })

        // 말풍선: 최애의 대사(있으면) + 앱이 한 일 한 줄. 누르면 다른 대사.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val r = dp(14).toFloat()
            background = GradientDrawable().apply { setColor(0xEEFFFFFF.toInt()); cornerRadii = floatArrayOf(r, r, r, r, r, r, dp(4).toFloat(), dp(4).toFloat()) }
            setOnClickListener { actions.onBubble() }
            contentDescription = listOfNotNull(ui.quote, ui.greeting).joinToString(". ") + if (ui.quote != null) ", 눌러서 다른 대사" else ""
            if (ui.quote != null) {
                // 긴 대사([Lobby.BUBBLE_MAX]자 넘게)는 네 줄만 보여 주고, 네 줄을 넘으면 '전체 보기'로 펼친다. 펼친 것은 대사가 바뀔 때까지.
                val long = ui.quote.length > Lobby.BUBBLE_MAX
                val open = !long || expandedQuote == ui.quote
                val quoteView = text("「${ui.quote}」", if (ui.quote.length > 60) 12.5f else 14f, 0xFF1B2030.toInt()).apply {
                    LobbyFont.title?.let { typeface = it }
                    setLineSpacing(dp(2).toFloat(), 1f)
                    // 명조 글꼴은 받침이 글꼴이 알려 주는 높이보다 조금 내려가 마지막 줄 아래가 잘린다. 그만큼 여백.
                    setPadding(0, 0, 0, dp(4))
                }
                // 말풍선 글자 폭(화면 폭 - 좌우 여백 16·86 - 안쪽 14·14 - 음성 표시)으로 줄 수를 미리 센다.
                val textWidth = w - dp(16 + 86 + 28) - if (ui.voice) dp(26) else 0
                val lines = if (long && textWidth > 0) android.text.StaticLayout.Builder
                    .obtain(quoteView.text, 0, quoteView.text.length, quoteView.paint, textWidth)
                    .setLineSpacing(dp(2).toFloat(), 1f).build().lineCount else 0
                val more = text(if (open) "접기 ▴" else "전체 보기 ▾", 12f, t.deep, bold = true).apply {
                    visibility = if (lines > 4) VISIBLE else GONE
                    setPadding(0, dp(6), dp(8), dp(2))
                    contentDescription = if (open) "대사 접기" else "대사 전체 보기"
                    setOnClickListener {
                        expandedQuote = if (open) null else ui.quote
                        lastBind?.invoke()
                    }
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(quoteView.apply {
                        if (!open) {
                            maxLines = 4
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    if (ui.voice) addView(text("🔊", 13f, t.deep).apply { setPadding(dp(6), 0, 0, 0) })
                })
                if (long) addView(more)
                addView(View(context).apply { setBackgroundColor(0x221B2030) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { setMargins(0, dp(8), 0, dp(6)) })
                addView(text(ui.greeting, 12f, 0xFF5C6479.toInt()).apply { setLineSpacing(dp(1).toFloat(), 1f) })
            } else {
                addView(text(ui.greeting, 13.5f, 0xFF1B2030.toInt()).apply { setLineSpacing(dp(2).toFloat(), 1f) })
            }
        }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(16), insetTop + dp(68), dp(86), 0) })

        // 오른쪽 메뉴
        val side = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for ((icon, label, key, dot) in listOf(
            Menu(R.drawable.ic_item_gift, "보상", MENU_REWARDS, ui.rewardsDot || ui.redeemDot),
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
                // 이름표도 그림에 묻히지 않게 진한 알약 바탕에.
                if (labels) addView(text(label, 10.5f, Color.WHITE, bold = true).apply {
                    setPadding(dp(7), dp(1), dp(7), dp(2))
                    background = GradientDrawable().apply { setColor(0xCC0C121C.toInt()); cornerRadius = dp(99).toFloat() }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
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
                LobbyFont.title?.let { typeface = it }
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 0, 0, dp(4)) // 명조 받침이 잘리지 않게
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageResource(Lobby.elementIcon(ui.element)); setColorFilter(t.light) }, LinearLayout.LayoutParams(dp(14), dp(14)))
                addView(text(ui.meta, 12.5f, Color.WHITE, shadow = true).apply { setPadding(dp(6), 0, 0, 0) })
            })
        })
        bottom.addView(dock(ui, t, actions), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(14), 0, dp(14), 0) })
        bottom.addView(text(when {
            ui.credit && ui.quote != null -> "그림·대사 © HoYoverse · 호요랩 위키 · 비공식 팬 도구"
            ui.credit -> "그림 © HoYoverse · 호요랩 위키 · 비공식 팬 도구"
            ui.quote != null -> "대사 © HoYoverse · 호요랩 위키 · 비공식 팬 도구"
            else -> "비공식 팬 도구"
        }, 10f, 0x88FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bottom.setPadding(0, 0, 0, insetBottom)
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
        // 그림 위에서도 묻히지 않게 진한 바탕과 또렷한 흰 테두리.
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xD90C121C.toInt()); setStroke(dp(2), 0x8CFFFFFF.toInt()) }
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
                addView(text(title, 28f, Color.WHITE, bold = true).apply { LobbyFont.title?.let { typeface = it }; setPadding(0, 0, 0, dp(4)) })
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

    /** 아이콘 타일 + 큰 숫자 + 이름을 나란히(원래 카드의 파견·주전자·의뢰처럼). */
    fun LinearLayout.iconStats(vararg items: Triple<Int, String, String>) = addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(12), 0, 0)
        for ((icon, v, u) in items) addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(iconTile(icon, 44), LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(text(v, 20f, context.getColor(R.color.ink), numeral = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
            addView(text(u, 11f, context.getColor(R.color.ink_muted)).apply { gravity = Gravity.CENTER })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    })

    /** 아이콘 + 제목 + 오른쪽 작은 글자 한 줄(리딤 기록·오늘 보상 등). */
    fun LinearLayout.row(icon: Int, title: String, right: String? = null, onClick: (() -> Unit)? = null) = addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        onClick?.let { action -> setOnClickListener { action() } }
        addView(iconTile(icon, 36), LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(text(title, 14f, if (onClick != null) accent else context.getColor(R.color.ink), bold = onClick != null).apply {
            setPadding(dp(12), 0, dp(8), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        right?.let { addView(text(it, 12f, context.getColor(R.color.ink_muted))) }
    })

    /** 원래 앱의 아이템 아이콘을 옅은 원소 색 동그라미에. */
    private fun iconTile(icon: Int, size: Int) = FrameLayout(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(soft) }
        addView(ImageView(context).apply { setImageResource(icon) }, LayoutParams(dp(size * 58 / 100), dp(size * 58 / 100), Gravity.CENTER))
    }

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

    /** 큰 숫자 + 막대(레진 등). icon이 있으면 숫자 왼쪽에 아이콘. */
    fun LinearLayout.meter(value: Int, max: Int, right: String, icon: Int? = null) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            icon?.let { addView(iconTile(it, 48), LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) }) }
            addView(text("$value", 44f, context.getColor(R.color.ink), numeral = true))
            addView(text(" / $max", 18f, context.getColor(R.color.ink_muted), numeral = true).apply { setPadding(0, dp(12), 0, 0) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(text(right, 14f, accent, bold = true))
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(soft); cornerRadius = dp(4).toFloat() }
            val filled = value.coerceIn(0, max)
            if (filled > 0) addView(View(context).apply { background = GradientDrawable().apply { setColor(t.el); cornerRadius = dp(4).toFloat() } },
                LinearLayout.LayoutParams(0, MATCH_PARENT, filled.toFloat()))
            if (max - filled > 0) addView(View(context), LinearLayout.LayoutParams(0, MATCH_PARENT, (max - filled).toFloat()))
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(8)).apply { topMargin = dp(10) })
    }

    /**
     * 출석 보상 달력(원래 카드처럼 칸마다 아이템 아이콘과 개수): 받은 칸은 원소 색 체크, 다음에 받을 칸은 원소 색 테두리,
     * 보충하지 않으면 못 받는 칸은 흐리게.
     */
    fun LinearLayout.calendar(slots: List<CalendarSlot>, signed: Int, next: Int, reachable: Int) {
        for (rowStart in slots.indices step 7) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            for (col in 0 until 7) {
                val index = rowStart + col
                val cell = FrameLayout(context)
                if (index < slots.size) {
                    val slot = slots[index]
                    val n = index + 1
                    val done = n <= signed
                    cell.background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(soft)
                        if (n == next) setStroke(dp(2), t.el)
                    }
                    cell.alpha = when { done -> 0.6f; n > reachable -> 0.35f; else -> 1f }
                    cell.contentDescription = "${n}번째 출석, ${slot.name} ${slot.count}개" + if (done) ", 받음" else ""
                    cell.addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        addView(ImageView(context).apply { setImageResource(ItemIcons.of(slot.name)) }, LinearLayout.LayoutParams(dp(22), dp(22)))
                        addView(text(CheckInCalendar.compact(slot.count), 11f, context.getColor(R.color.ink)).apply { gravity = Gravity.CENTER; maxLines = 1 })
                    }, LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    cell.addView(text("$n", 9f, context.getColor(R.color.ink_muted)).apply { setPadding(dp(5), dp(2), 0, 0) })
                    if (done) cell.addView(FrameLayout(context).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.el) }
                        addView(ImageView(context).apply { setImageResource(R.drawable.ic_lobby_check); setColorFilter(Color.WHITE) }, LayoutParams(dp(10), dp(10), Gravity.CENTER))
                    }, LayoutParams(dp(15), dp(15), Gravity.END or Gravity.TOP).apply { topMargin = dp(3); marginEnd = dp(3) })
                }
                row.addView(cell, LinearLayout.LayoutParams(0, dp(58), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            addView(row)
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
