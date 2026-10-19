package kr.sumi.checkin

import android.annotation.SuppressLint
import android.animation.LayoutTransition
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 메인 화면 편집. 카드마다 한 줄씩: 손잡이(≡)를 끌어 순서를 바꾸고, 줄을 누르면 그 카드의 옵션, −를 누르면 아래 서랍으로.
 * 서랍의 카드를 누르면 다시 꺼내 원래 자리에 놓인다. 출석 카드는 맨 위에 고정이라 옮기거나 넣을 수 없다.
 * 바꾼 것은 바로 저장되고, onChanged로 메인 화면도 다시 그린다.
 */
class MainEditor(
    panel: View,
    private val settings: AppSettings,
    private val onChanged: () -> Unit,
    private val openOptions: (String) -> Unit,
) {
    private val context = panel.context
    private val list: LinearLayout = panel.findViewById(R.id.editList)
    private val drawerItems: LinearLayout = panel.findViewById(R.id.editDrawerItems)
    private val drawerEmpty: View = panel.findViewById(R.id.editDrawerEmpty)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val gap get() = dp(8)

    init {
        panel.findViewById<TextView>(R.id.headerTitle).text = "메인 화면 편집"
        panel.findViewById<TextView>(R.id.headerStatus).text = "≡ 를 끌어 순서를 바꾸고, 줄을 누르면 옵션이 나와요"
    }

    fun render() {
        list.removeAllViews()
        list.addView(row(CARD_CHECK_IN))
        val order = settings.cardOrder
        order.filter { settings.isShown(it) }.forEach { list.addView(row(it)) }
        drawerItems.removeAllViews()
        val hidden = order.filter { !settings.isShown(it) }
        drawerEmpty.visibility = if (hidden.isEmpty()) View.VISIBLE else View.GONE
        hidden.forEach { drawerItems.addView(drawerTile(it)) }
    }

    private fun title(card: String) = when (card) {
        CARD_CHECK_IN -> "출석"
        CARD_NOTES -> "레진"
        CARD_TILES -> "리딤 코드·보상 내역"
        else -> "출석 보상 달력"
    }

    private fun icon(card: String) = when (card) {
        CARD_CHECK_IN -> R.drawable.ic_item_primogem
        CARD_NOTES -> R.drawable.ic_item_resin
        CARD_TILES -> R.drawable.ic_item_gift
        else -> R.drawable.ic_calendar
    }

    /** 줄 아래 짧은 설명: 지금 켜진 알림 위주로. */
    private fun summary(card: String): String = when (card) {
        CARD_CHECK_IN -> "맨 위 고정 · " + if (settings.notifyCheckIn) "출석하면 알림" else "출석 알림 꺼짐"
        CARD_NOTES -> listOfNotNull(
            when {
                !settings.resinAlert -> "레진 알림 꺼짐"
                settings.resinThreshold <= 0 -> "가득 차면 알림"
                else -> "${settings.resinThreshold}개에 알림"
            },
            "파견 알림".takeIf { settings.expeditionAlert },
            "주전자 알림".takeIf { settings.coinAlert },
        ).joinToString(" · ")
        CARD_TILES -> (if (settings.redeemEnabled) "자동 입력" else "자동 입력 꺼짐") + if (settings.notifyRedeem) " · 받으면 알림" else ""
        else -> "이번 달 출석 보상"
    }

    private fun iconTile(card: String, size: Int) = ImageView(context).apply {
        setImageResource(icon(card))
        setBackgroundResource(R.drawable.bg_item_tile)
        val pad = size / 5
        setPadding(pad, pad, pad, pad)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun row(card: String): View {
        val fixed = card == CARD_CHECK_IN
        return LinearLayout(context).apply {
            tag = card
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_group)
            foreground = context.getDrawable(android.R.drawable.list_selector_background)
            minimumHeight = dp(68)
            setPadding(dp(14), dp(10), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = gap }
            setOnClickListener { openOptions(card) }
            addView(iconTile(card, dp(40)), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(TextView(context).apply {
                    text = title(card)
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(context.getColor(R.color.ink))
                })
                addView(TextView(context).apply {
                    text = summary(card)
                    textSize = 12f
                    setTextColor(context.getColor(R.color.ink_muted))
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (fixed) return@apply
            addView(TextView(context).apply {
                text = "−"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.ink_muted))
                contentDescription = "${title(card)} 서랍에 넣기"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(context.getColor(R.color.item_tile))
                }
                setOnClickListener { setShown(card, false) }
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(4) })
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_drag)
                contentDescription = "${title(card)} 끌어서 옮기기"
                scaleType = ImageView.ScaleType.CENTER
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
        }.also { row ->
            if (!fixed) row.getChildAt(row.childCount - 1).setOnTouchListener(DragHandler(row))
        }
    }

    private fun drawerTile(card: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(context.getColor(R.color.surface))
        }
        foreground = context.getDrawable(android.R.drawable.list_selector_background)
        setPadding(dp(8), dp(12), dp(8), dp(10))
        contentDescription = "${title(card)} 꺼내기"
        setOnClickListener { setShown(card, true) }
        addView(iconTile(card, dp(36)), LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(TextView(context).apply {
            text = title(card).substringBefore("·")
            textSize = 12f
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.ink))
            setPadding(0, dp(6), 0, 0)
        })
        addView(TextView(context).apply {
            text = "꺼내기"
            textSize = 11f
            setTextColor(context.getColor(R.color.accent))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = gap }
    }

    private fun setShown(card: String, shown: Boolean) {
        settings.setShown(card, shown)
        render()
        onChanged()
    }

    /** 지금 줄 순서대로 저장한다. 서랍의 카드는 원래 자리(앞뒤 관계)를 그대로 둔다. */
    private fun saveOrder() {
        val visible = (0 until list.childCount).mapNotNull { list.getChildAt(it).tag as? String }.filter { it in CARDS }
        val queue = ArrayDeque(visible)
        settings.cardOrder = settings.cardOrder.map { if (settings.isShown(it)) queue.removeFirst() else it }
        onChanged()
    }

    /**
     * 손잡이를 잡고 끄는 동안 줄을 손가락을 따라 움직이고, 이웃 줄의 절반을 넘으면 이웃을 반대편으로 옮긴다.
     * 끄는 줄 자체를 빼고 다시 넣으면 터치가 끊기므로 이웃 줄을 옮긴다. 출석 줄(0번) 위로는 못 간다.
     */
    private inner class DragHandler(private val row: View) : View.OnTouchListener {
        private var lastY = 0f
        private var transition: LayoutTransition? = null

        @SuppressLint("ClickableViewAccessibility") // 끌기 전용 손잡이. 옵션은 줄을 눌러서 연다.
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    transition = list.layoutTransition
                    list.layoutTransition = null
                    lastY = e.rawY
                    row.animate().translationZ(dp(8).toFloat()).scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    row.translationY += e.rawY - lastY
                    lastY = e.rawY
                    val index = list.indexOfChild(row)
                    val below = list.getChildAt(index + 1)
                    val above = list.getChildAt(index - 1)
                    if (below != null && row.translationY > (below.height + gap) / 2f) {
                        list.removeView(below)
                        list.addView(below, index)
                        row.translationY -= below.height + gap
                    } else if (above != null && index > 1 && row.translationY < -(above.height + gap) / 2f) {
                        list.removeView(above)
                        list.addView(above, index)
                        row.translationY += above.height + gap
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    row.animate().translationY(0f).translationZ(0f).scaleX(1f).scaleY(1f).setDuration(150).start()
                    list.layoutTransition = transition
                    saveOrder()
                }
            }
            return true
        }
    }

    companion object {
        const val CARD_CHECK_IN = "check_in"
    }
}
