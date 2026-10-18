package com.lesumein.hoyocheckin

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.CompoundButton
import android.widget.TextView

private fun Context.dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
private fun Context.color(id: Int) = this.getColor(id)

/**
 * Material 3 스위치와 같은 모양의 스위치(라이브러리 없이 직접 그림).
 * 트랙 52×32dp. 켜짐: 트랙 action 색, 지름 24dp 손잡이 on_action 색.
 * 꺼짐: 트랙 line 색에 2dp ink_muted 테두리, 지름 16dp 손잡이 ink_muted 색. 켜고 끌 때 손잡이가 미끄러진다.
 */
class StarSwitch @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    CompoundButton(context, attrs) {

    private val trackWidth = context.dp(52f)
    private val trackHeight = context.dp(32f)
    private val outline = context.dp(2f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    /** 0 = 꺼짐, 1 = 켜짐. 그 사이는 움직이는 중. */
    private var position = 0f
    private var animator: ValueAnimator? = null

    init {
        buttonDrawable = null
        background = null
        isClickable = true
        isFocusable = true
        minimumWidth = trackWidth.toInt()
        minimumHeight = context.dp(48f).toInt() // 누르기 쉬운 크기
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(trackWidth.toInt() + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(context.dp(48f).toInt(), heightMeasureSpec),
        )
    }

    override fun setChecked(checked: Boolean) {
        val changed = checked != isChecked
        super.setChecked(checked)
        val target = if (checked) 1f else 0f
        animator?.cancel()
        if (!changed || !isLaidOut) {
            position = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = 150
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val top = (height - trackHeight) / 2
        rect.set(left, top, left + trackWidth, top + trackHeight)
        val radius = trackHeight / 2

        // 트랙
        paint.style = Paint.Style.FILL
        paint.color = blend(context.color(R.color.line), context.color(R.color.action), position)
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (position < 1f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outline
            paint.color = context.color(R.color.ink_muted)
            paint.alpha = ((1 - position) * 255).toInt()
            rect.inset(outline / 2, outline / 2)
            canvas.drawRoundRect(rect, radius - outline / 2, radius - outline / 2, paint)
            paint.alpha = 255
        }

        // 손잡이: 꺼짐 지름 16dp(왼쪽 중심 16dp) → 켜짐 지름 24dp(오른쪽 중심 16dp)
        val thumbRadius = context.dp(8f) + context.dp(4f) * position
        val startX = left + context.dp(16f)
        val endX = left + trackWidth - context.dp(16f)
        paint.style = Paint.Style.FILL
        paint.color = blend(context.color(R.color.ink_muted), context.color(R.color.on_action), position)
        canvas.drawCircle(startX + (endX - startX) * position, height / 2f, thumbRadius, paint)
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Switch::class.java.name

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int) = (((from shr shift) and 0xFF) * (1 - t) + ((to shr shift) and 0xFF) * t).toInt() shl shift
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}

/** Material 3 필터 칩과 같은 모양의 칩. 보이는 높이 32dp(누르는 영역 48dp), 모서리 8dp. 고르면 line 색으로 채우고, 아니면 흰 바탕에 테두리. */
fun makeChip(context: Context, label: String, checked: Boolean, checkable: Boolean): TextView = TextView(context).apply {
    text = label
    gravity = Gravity.CENTER
    minHeight = context.dp(48f).toInt()
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    setTextColor(context.color(R.color.ink))
    isClickable = true
    isFocusable = true
    if (checkable) isSelected = checked
    val radius = context.dp(8f)
    val shape = GradientDrawable().apply {
        cornerRadius = radius
        if (checkable && checked) {
            setColor(context.color(R.color.line))
        } else {
            setColor(context.color(R.color.surface))
            setStroke(context.dp(1f).toInt(), context.color(R.color.ink_muted))
        }
    }
    val mask = GradientDrawable().apply { cornerRadius = radius; setColor(0xFFFFFFFF.toInt()) }
    val inset = context.dp(8f).toInt()
    background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(context.color(R.color.ink) and 0x1FFFFFFF), shape, mask), 0, inset, 0, inset)
    // 배경을 넣으면 안쪽 여백이 배경의 여백으로 바뀌므로 배경 뒤에 준다.
    val horizontal = context.dp(16f).toInt()
    setPadding(horizontal, 0, horizontal, 0)
}

/**
 * 둥근 버튼 배경: 누르면 물결, 꺼지면 흐리게. Material 3 버튼 모양을 직접 만든다.
 * Material처럼 누르는 영역은 보이는 모양보다 위아래 insetDp만큼 크다.
 */
fun buttonBackground(context: Context, fill: Int, radiusDp: Float, stroke: Int? = null, insetDp: Float = 4f): Drawable {
    val radius = context.dp(radiusDp)
    fun shape(color: Int, strokeColor: Int?) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        strokeColor?.let { setStroke(context.dp(1f).toInt(), it) }
    }
    val states = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), shape(if (fill == 0) 0 else context.color(R.color.ink) and 0x1FFFFFFF, stroke?.let { context.color(R.color.ink) and 0x1FFFFFFF }))
        addState(intArrayOf(), shape(fill, stroke))
    }
    val mask = shape(0xFFFFFFFF.toInt(), null)
    val rippleColor = if (fill == 0) context.color(R.color.accent) and 0x1FFFFFFF else 0x33FFFFFF
    val inset = context.dp(insetDp).toInt()
    return InsetDrawable(RippleDrawable(ColorStateList.valueOf(rippleColor), states, mask), 0, inset, 0, inset)
}
