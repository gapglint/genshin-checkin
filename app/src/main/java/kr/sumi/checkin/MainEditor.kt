package kr.sumi.checkin

import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import java.util.concurrent.ThreadLocalRandom

/**
 * 메인 화면을 그 자리에서 편집한다(홈 화면 아이콘 정리처럼).
 * - 편집 중 카드는 그림자가 생기고 살짝 흔들린다(움직일 수 있다는 표시). 오른쪽 위 '⚙ 옵션'이나 카드를 톡 누르면 옵션이 뜬다.
 * - 카드를 꾹 누르면 들려서 손가락을 따라오고, 다른 카드는 비켜난다. 화면 아래 서랍 위에서 놓으면 서랍에 들어간다.
 * - 서랍의 카드는 톡 누르면 맨 아래로, 꾹 눌러 들고 나가면 놓은 자리로 꺼내진다.
 * 출석 카드는 맨 위 고정이라 옵션만 있다. 무엇을 저장할지는 콜백을 받은 액티비티가 정한다.
 */
class MainEditor(
    private val screen: MainScreen,
    private val onOptions: (card: String, anchor: View) -> Unit,
    private val onHide: (String) -> Unit,
    /** index: 보이는 카드 중 몇 번째 자리에 놓을지. null이면 맨 아래. */
    private val onShow: (card: String, index: Int?) -> Unit,
    private val onOrder: (List<String>) -> Unit,
) {
    private val context = screen.root.context
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val root = screen.root as FrameLayout
    private val scroll: ScrollView = root.findViewById(R.id.scroll)
    private val content = screen.cards.parent as ViewGroup
    private val drawer: LinearLayout = root.findViewById(R.id.editDrawer)
    private val drawerItems: LinearLayout = root.findViewById(R.id.editDrawerItems)
    private val drawerHint: TextView = root.findViewById(R.id.editDrawerEmpty)
    private val overlays = (CARDS + CARD_CHECK_IN).associateWith { card -> overlay(card).also { screen.slot(card).addView(it) } }
    private val wiggles = mutableMapOf<String, ObjectAnimator>()
    private val longPress = ViewConfiguration.getLongPressTimeout().coerceAtMost(350).toLong()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var editing = false
    private var hidden: List<String> = emptyList()

    init {
        // 모서리에 걸친 −와 '⚙ 옵션'이 카드 밖으로 살짝 나와도 잘리지 않게.
        (CARDS + CARD_CHECK_IN).forEach { screen.slot(it).clipChildren = false }
        screen.cards.clipChildren = false
        content.clipChildren = false
        // 리딤·보상 타일 두 장의 그림자도 잘리지 않게.
        (screen.slot(CARD_TILES).getChildAt(0) as ViewGroup).clipChildren = false
    }

    /** 편집 상태를 맞춘다. 켜고 끌 때만 움직임을 넣고, 서랍은 매번 다시 채운다. */
    fun show(on: Boolean, hidden: List<String>) {
        this.hidden = hidden
        if (on != editing) {
            editing = on
            val scale = if (on) EDIT_SCALE else 1f
            for ((card, overlay) in overlays) {
                val slot = screen.slot(card)
                overlay.visibility = if (on) View.VISIBLE else View.GONE
                screen.surfaces(card).forEach { it.translationZ = if (on) dp(6).toFloat() else 0f }
                if (slot.isLaidOut) {
                    if (on) overlay.alpha = 0f
                    overlay.animate().alpha(1f).setDuration(180).start()
                    slot.animate().scaleX(scale).scaleY(scale).setDuration(180).start()
                } else {
                    overlay.alpha = 1f
                    slot.scaleX = scale
                    slot.scaleY = scale
                }
                if (on && card != CARD_CHECK_IN && slot.isLaidOut) wiggle(card) else stopWiggle(card)
            }
            // 떠 있는 서랍에 마지막 카드가 가리지 않게 아래 여백을 늘린다.
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, if (on) dp(220) else dp(24))
        }
        drawer.visibility = if (on) View.VISIBLE else View.GONE
        paintDrawer(hover = false)
        drawerItems.removeAllViews()
        hidden.forEach { drawerItems.addView(drawerTile(it)) }
    }

    /** 아주 살짝 좌우로 흔든다. 카드마다 시작을 달리해 함께 흔들리지 않게. */
    private fun wiggle(card: String) {
        stopWiggle(card)
        wiggles[card] = ObjectAnimator.ofFloat(screen.slot(card), View.ROTATION, -0.45f, 0.45f).apply {
            duration = 260L + ThreadLocalRandom.current().nextLong(80)
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            startDelay = ThreadLocalRandom.current().nextLong(200)
            start()
        }
    }

    private fun stopWiggle(card: String) {
        wiggles.remove(card)?.cancel()
        screen.slot(card).rotation = 0f
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

    private fun paintDrawer(hover: Boolean) {
        drawer.background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(context.getColor(R.color.item_tile))
            if (hover) setStroke(dp(2), context.getColor(R.color.accent))
        }
        val scale = if (hover) 1.03f else 1f
        if (drawer.isLaidOut) drawer.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        drawerHint.text = when {
            hover -> "놓으면 서랍에 넣어요"
            hidden.isEmpty() -> "카드를 꾹 눌러 여기로 가져오면 숨겨요"
            else -> "꾹 눌러 들고 나가거나, 톡 누르면 꺼내요"
        }
        drawerHint.setTextColor(context.getColor(if (hover) R.color.accent else R.color.ink_muted))
    }

    /**
     * 카드 위에 겹치는 편집 층: 점선 테두리(톡 누르면 옵션, 꾹 누르면 들기), 왼쪽 위 −(서랍), 오른쪽 위 '⚙ 옵션'.
     * −와 '⚙ 옵션'은 내용을 가리지 않게 테두리에 걸쳐 놓는다.
     */
    @SuppressLint("ClickableViewAccessibility") // 톡 누르면 performClick으로 옵션을 연다.
    private fun overlay(card: String): View {
        val cardView = screen.slot(card).getChildAt(0)
        val top = (cardView.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val radius = if (card == CARD_CHECK_IN) 24 else 20
        return FrameLayout(context).apply {
            visibility = View.GONE
            clipChildren = false
            // 편집 중 카드에 그림자(높이)를 주므로, 편집 층은 그보다 위에 그려지게 더 높인다.
            elevation = dp(10).toFloat()
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(radius).toFloat()
                    setStroke(dp(2), context.getColor(R.color.accent), dp(7).toFloat(), dp(5).toFloat())
                }
                contentDescription = if (card == CARD_CHECK_IN) "${title(card)} 옵션" else "${title(card)}: 눌러서 옵션, 꾹 눌러서 옮기기"
                setOnClickListener { onOptions(card, screen.slot(card)) }
                if (card != CARD_CHECK_IN) setOnTouchListener(CardDrag(card))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = top })
            if (card != CARD_CALENDAR) addView(TextView(context).apply {
                text = "⚙ 옵션"
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(context.getColor(R.color.on_action))
                background = GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat()
                    setColor(context.getColor(R.color.action))
                }
                setPadding(dp(12), 0, dp(12), 0)
                elevation = dp(8).toFloat()
                setOnClickListener { onOptions(card, screen.slot(card)) }
            }, FrameLayout.LayoutParams(WRAP_CONTENT, dp(30), Gravity.TOP or Gravity.END).apply {
                setMargins(0, top - dp(14), dp(14), 0)
            })
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
                elevation = dp(8).toFloat()
                setOnClickListener { onHide(card) }
            }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.START).apply {
                setMargins(-dp(6), top - dp(10), 0, 0)
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
        elevation = dp(2).toFloat()
        setPadding(dp(8), dp(12), dp(8), dp(10))
        contentDescription = "${title(card)}: 눌러서 꺼내기"
        setOnClickListener { onShow(card, null) }
        setOnTouchListener(TileDrag(card))
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
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
    }

    private fun overDrawer(rawY: Float): Boolean {
        val at = IntArray(2)
        drawer.getLocationOnScreen(at)
        return rawY >= at[1]
    }

    /** 보이는 카드 중 손가락 높이보다 위에 중심이 있는 카드 수 = 놓을 자리. */
    private fun indexAt(rawY: Float): Int {
        val at = IntArray(2)
        return (0 until screen.cards.childCount).map { screen.cards.getChildAt(it) }.filter { it.visibility == View.VISIBLE }.count {
            it.getLocationOnScreen(at)
            at[1] + it.height / 2 < rawY
        }
    }

    /** 손가락이 화면 위·아래(서랍 바로 위) 끝에 가까우면 그쪽으로 스크롤한다. 실제로 움직인 양을 돌려준다. */
    private fun autoScroll(rawY: Float): Int {
        val top = IntArray(2).also { scroll.getLocationOnScreen(it) }[1]
        val drawerTop = IntArray(2).also { drawer.getLocationOnScreen(it) }[1]
        val step = when {
            rawY < top + dp(80) -> -dp(12)
            rawY > drawerTop - dp(40) && rawY < drawerTop -> dp(12)
            else -> 0
        }
        val before = scroll.scrollY
        if (step != 0) scroll.scrollBy(0, step)
        return scroll.scrollY - before
    }

    /**
     * 카드: 톡 누르면 옵션, 꾹 누르면 들기. 들고 끄는 동안 보이는 이웃 카드의 절반을 넘으면 그 이웃을 반대편으로 옮기고,
     * 서랍 위에서 놓으면 서랍에 넣는다. 끄는 카드 자체를 빼고 다시 넣으면 터치가 끊기므로 이웃을 옮긴다.
     */
    private inner class CardDrag(private val card: String) : View.OnTouchListener {
        private val slot get() = screen.slot(card)
        private val cards get() = screen.cards
        private var downX = 0f
        private var downY = 0f
        private var lastY = 0f
        private var lifted = false
        private var hover = false
        private val lift = Runnable { pickUp() }

        private fun pickUp() {
            lifted = true
            slot.parent.requestDisallowInterceptTouchEvent(true)
            slot.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            stopWiggle(card)
            cards.layoutTransition = null
            slot.animate().scaleX(1.04f).scaleY(1.04f).translationZ(dp(24).toFloat()).rotation(1.2f).setDuration(140).start()
        }

        private fun neighbor(step: Int): View? {
            var i = cards.indexOfChild(slot) + step
            while (i in 0 until cards.childCount) {
                val v = cards.getChildAt(i)
                if (v.visibility == View.VISIBLE) return v
                i += step
            }
            return null
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; lastY = e.rawY
                    lifted = false
                    v.postDelayed(lift, longPress)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!lifted) {
                        // 꾹 누르기 전에 움직이면 스크롤로 넘긴다.
                        if (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) v.removeCallbacks(lift)
                        return false
                    }
                    slot.translationY += e.rawY - lastY + autoScroll(e.rawY)
                    lastY = e.rawY
                    val nowHover = overDrawer(e.rawY)
                    if (nowHover != hover) {
                        hover = nowHover
                        paintDrawer(hover)
                        slot.animate().alpha(if (hover) 0.6f else 1f).setDuration(100).start()
                    }
                    if (hover) return true
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
                    v.removeCallbacks(lift)
                    if (!lifted) {
                        if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                        return true
                    }
                    lifted = false
                    if (hover && e.actionMasked == MotionEvent.ACTION_UP) {
                        hover = false
                        // 서랍으로 쏙: 작아지며 아래로 사라진 뒤 숨긴다.
                        slot.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).translationYBy(dp(120).toFloat()).setDuration(180)
                            .withEndAction {
                                settle()
                                onHide(card)
                            }.start()
                        return true
                    }
                    hover = false
                    paintDrawer(false)
                    slot.animate().translationY(0f).translationZ(0f).rotation(0f).alpha(1f)
                        .scaleX(EDIT_SCALE).scaleY(EDIT_SCALE).setDuration(160).withEndAction { if (editing) wiggle(card) }.start()
                    cards.layoutTransition = LayoutTransition()
                    onOrder((0 until cards.childCount).map { cards.getChildAt(it) }.filter { it.visibility == View.VISIBLE }.mapNotNull { it.tag as? String })
                }
            }
            return true
        }

        private fun settle() {
            slot.translationY = 0f
            slot.translationZ = 0f
            slot.rotation = 0f
            slot.alpha = 1f
            slot.scaleX = EDIT_SCALE
            slot.scaleY = EDIT_SCALE
            cards.layoutTransition = LayoutTransition()
        }
    }

    /** 서랍 카드: 톡 누르면 맨 아래로 꺼내고, 꾹 누르면 들어 올려 놓은 자리로 꺼낸다. 드는 동안은 같은 모습의 그림이 따라온다. */
    private inner class TileDrag(private val card: String) : View.OnTouchListener {
        private var ghost: ImageView? = null
        /** 그림의 처음 자리(서랍 카드 위). 움직일 때는 여기에 손가락이 움직인 만큼을 더한다. */
        private var ghostX = 0f
        private var ghostY = 0f
        private var downX = 0f
        private var downY = 0f
        private var tile: View? = null
        private val lift = Runnable { pickUp() }

        private fun pickUp() {
            val t = tile ?: return
            t.parent.requestDisallowInterceptTouchEvent(true)
            t.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val bitmap = Bitmap.createBitmap(t.width, t.height, Bitmap.Config.ARGB_8888).also { t.draw(Canvas(it)) }
            val tileAt = IntArray(2).also { t.getLocationOnScreen(it) }
            val rootAt = IntArray(2).also { root.getLocationOnScreen(it) }
            ghostX = (tileAt[0] - rootAt[0]).toFloat()
            ghostY = (tileAt[1] - rootAt[1]).toFloat()
            ghost = ImageView(context).apply {
                setImageBitmap(bitmap)
                elevation = dp(24).toFloat()
                translationX = ghostX
                translationY = ghostY
                root.addView(this, FrameLayout.LayoutParams(t.width, t.height))
                animate().scaleX(1.1f).scaleY(1.1f).rotation(-2f).setDuration(140).start()
            }
            t.alpha = 0.3f
        }

        @SuppressLint("ClickableViewAccessibility") // 톡 누르면 performClick으로 꺼낸다.
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tile = v; downX = e.rawX; downY = e.rawY
                    v.postDelayed(lift, longPress)
                }
                MotionEvent.ACTION_MOVE -> {
                    val g = ghost
                    if (g == null) {
                        if (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) v.removeCallbacks(lift)
                        return true
                    }
                    g.translationX = ghostX + e.rawX - downX
                    g.translationY = ghostY + e.rawY - downY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(lift)
                    val g = ghost
                    if (g == null) {
                        if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                        return true
                    }
                    ghost = null
                    root.removeView(g)
                    v.alpha = 1f
                    if (e.actionMasked == MotionEvent.ACTION_UP && !overDrawer(e.rawY)) onShow(card, indexAt(e.rawY))
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
