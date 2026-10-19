package kr.sumi.checkin

import android.annotation.SuppressLint
import android.animation.LayoutTransition
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 메인 화면을 그 자리에서 편집한다(홈 화면 아이콘 정리처럼). 편집 중에는 실제 카드가 살짝 작아지고 점선 테두리가 생기며,
 * 왼쪽 위 −를 누르면 아래 서랍으로, 오른쪽 ≡를 끌면 순서가 바뀌고, 카드를 누르면 그 카드 옵션이 뜬다.
 * 출석 카드는 맨 위에 고정이라 옵션만 있다. 무엇을 저장할지는 콜백을 받은 액티비티가 정한다.
 */
class MainEditor(
    private val screen: MainScreen,
    private val onOptions: (String) -> Unit,
    private val onHide: (String) -> Unit,
    private val onShow: (String) -> Unit,
    private val onOrder: (List<String>) -> Unit,
) {
    private val context = screen.root.context
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val drawer: View = screen.root.findViewById(R.id.editDrawer)
    private val drawerItems: LinearLayout = screen.root.findViewById(R.id.editDrawerItems)
    private val drawerEmpty: View = screen.root.findViewById(R.id.editDrawerEmpty)
    private val overlays = (CARDS + CARD_CHECK_IN).associateWith { card -> overlay(card).also { screen.slot(card).addView(it) } }
    private var editing = false

    init {
        // 모서리에 걸친 −와 위쪽 손잡이가 카드 밖으로 살짝 나와도 잘리지 않게.
        (CARDS + CARD_CHECK_IN).forEach { screen.slot(it).clipChildren = false }
        screen.cards.clipChildren = false
        (screen.cards.parent as ViewGroup).clipChildren = false
    }

    /** 편집 상태를 맞춘다. 켜고 끌 때만 움직임을 넣고, 서랍은 매번 다시 채운다. */
    fun show(on: Boolean, hidden: List<String>) {
        if (on != editing) {
            editing = on
            val scale = if (on) EDIT_SCALE else 1f
            for ((card, overlay) in overlays) {
                val slot = screen.slot(card)
                overlay.visibility = if (on) View.VISIBLE else View.GONE
                // 화면에 그려진 뒤라면 부드럽게, 아직이면(처음 열 때) 바로 그 모습으로.
                if (slot.isLaidOut) {
                    if (on) overlay.alpha = 0f
                    overlay.animate().alpha(1f).setDuration(180).start()
                    slot.animate().scaleX(scale).scaleY(scale).setDuration(180).start()
                } else {
                    overlay.alpha = 1f
                    slot.scaleX = scale
                    slot.scaleY = scale
                }
            }
        }
        drawer.visibility = if (on) View.VISIBLE else View.GONE
        drawerItems.removeAllViews()
        drawerEmpty.visibility = if (hidden.isEmpty()) View.VISIBLE else View.GONE
        hidden.forEach { drawerItems.addView(drawerTile(it)) }
    }

    private fun title(card: String) = when (card) {
        CARD_CHECK_IN -> "출석"
        CARD_NOTES -> "레진"
        CARD_TILES -> "리딤 코드"
        else -> "출석 달력"
    }

    private fun icon(card: String) = when (card) {
        CARD_CHECK_IN -> R.drawable.ic_item_primogem
        CARD_NOTES -> R.drawable.ic_item_resin
        CARD_TILES -> R.drawable.ic_item_gift
        else -> R.drawable.ic_calendar
    }

    /**
     * 카드 위에 겹치는 편집 층: 점선 테두리(누르면 옵션), 왼쪽 위 모서리에 걸친 −(서랍), 위쪽 가운데 테두리에 걸친 ≡ 손잡이(끌기).
     * 내용을 가리지 않게 모서리와 테두리에 걸쳐 놓는다. 틀 전체를 덮어 카드 위 여백까지 손잡이를 잡을 수 있다.
     */
    private fun overlay(card: String): View {
        val cardView = screen.slot(card).getChildAt(0)
        val top = (cardView.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val radius = if (card == CARD_CHECK_IN) 24 else 20
        return FrameLayout(context).apply {
            visibility = View.GONE
            clipChildren = false
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(radius).toFloat()
                    setStroke(dp(2), context.getColor(R.color.accent), dp(7).toFloat(), dp(5).toFloat())
                }
                contentDescription = "${title(card)} 옵션"
                setOnClickListener { onOptions(card) }
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = top })
            if (card == CARD_CHECK_IN) return@apply
            addView(TextView(context).apply {
                text = "−"
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                contentDescription = "${title(card)} 서랍에 넣기"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(context.getColor(R.color.warn))
                    setStroke(dp(2), context.getColor(R.color.bg))
                }
                elevation = dp(3).toFloat()
                setOnClickListener { onHide(card) }
            }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.START).apply {
                setMargins(-dp(6), top - dp(10), 0, 0)
            })
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_drag)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "${title(card)} 끌어서 옮기기"
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(context.getColor(R.color.surface))
                    setStroke(dp(2), context.getColor(R.color.accent))
                }
                elevation = dp(3).toFloat()
                setOnTouchListener(DragHandler(screen.slot(card)))
            }, FrameLayout.LayoutParams(dp(60), dp(28), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = top - dp(14)
            })
        }
    }

    private fun drawerTile(card: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(context.getColor(R.color.surface))
        }
        setPadding(dp(8), dp(12), dp(8), dp(10))
        contentDescription = "${title(card)} 꺼내기"
        setOnClickListener { onShow(card) }
        addView(ImageView(context).apply {
            setImageResource(icon(card))
            setBackgroundResource(R.drawable.bg_item_tile)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(TextView(context).apply {
            text = title(card)
            textSize = 12f
            maxLines = 1
            setTextColor(context.getColor(R.color.ink))
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(TextView(context).apply {
            text = "+ 꺼내기"
            textSize = 11f
            setTextColor(context.getColor(R.color.accent))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
    }

    /**
     * ≡를 잡고 끄는 동안 카드가 손가락을 따라 움직이고, 보이는 이웃 카드의 절반을 넘으면 그 이웃을 반대편으로 옮긴다.
     * 끄는 카드 자체를 빼고 다시 넣으면 터치가 끊기므로 이웃을 옮긴다. 숨긴 카드(GONE)는 건너뛴다.
     */
    private inner class DragHandler(private val slot: View) : View.OnTouchListener {
        private val cards = screen.cards
        private var lastY = 0f
        private var transition: LayoutTransition? = null

        private fun neighbor(step: Int): View? {
            var i = cards.indexOfChild(slot) + step
            while (i in 0 until cards.childCount) {
                val v = cards.getChildAt(i)
                if (v.visibility == View.VISIBLE) return v
                i += step
            }
            return null
        }

        @SuppressLint("ClickableViewAccessibility") // 끌기 전용 손잡이. 옵션은 카드를 눌러서 연다.
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    transition = cards.layoutTransition
                    cards.layoutTransition = null
                    lastY = e.rawY
                    slot.animate().translationZ(dp(10).toFloat()).scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    slot.translationY += e.rawY - lastY
                    lastY = e.rawY
                    val below = neighbor(1)
                    val above = neighbor(-1)
                    if (below != null && slot.translationY > below.height / 2f) {
                        cards.removeView(below)
                        cards.addView(below, cards.indexOfChild(slot))
                        slot.translationY -= below.height
                    } else if (above != null && slot.translationY < -above.height / 2f) {
                        cards.removeView(above)
                        cards.addView(above, cards.indexOfChild(slot) + 1)
                        slot.translationY += above.height
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    slot.animate().translationY(0f).translationZ(0f).scaleX(EDIT_SCALE).scaleY(EDIT_SCALE).setDuration(150).start()
                    cards.layoutTransition = transition
                    onOrder((0 until cards.childCount).map { cards.getChildAt(it) }.filter { it.visibility == View.VISIBLE }.mapNotNull { it.tag as? String })
                }
            }
            return true
        }
    }

    companion object {
        const val CARD_CHECK_IN = "check_in"
        /** 편집 중 카드 크기. 살짝 작아져 '움직일 수 있다'는 느낌을 준다. */
        private const val EDIT_SCALE = 0.96f
    }
}
