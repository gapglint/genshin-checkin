package kr.sumi.checkin

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 카드 바로 위(공간이 없으면 아래)에 뜨는 말풍선. 꼬리가 그 카드를 가리켜 '이 카드의 옵션'임이 보인다.
 * 바깥을 누르거나 뒤로 가기로 닫힌다.
 */
object Popover {
    fun show(anchor: View, title: String, content: View): PopupWindow {
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val screenWidth = context.resources.displayMetrics.widthPixels
        val width = minOf(anchor.width, dp(400), screenWidth - dp(24))
        val color = context.getColor(R.color.surface)

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(color)
                setStroke(dp(1), context.getColor(R.color.line))
            }
            elevation = dp(12).toFloat()
            setPadding(dp(18), dp(14), dp(18), dp(8))
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(context.getColor(R.color.ink))
                setPadding(0, 0, 0, dp(8))
            })
            addView(android.widget.ScrollView(context).apply { addView(content) })
        }
        val tail = View(context).apply {
            background = GradientDrawable().apply { setColor(color) }
            rotation = 45f
            elevation = dp(12).toFloat()
        }
        val frame = FrameLayout(context).apply {
            clipChildren = false
            setPadding(dp(4), dp(14), dp(4), dp(14))
            addView(tail, FrameLayout.LayoutParams(dp(16), dp(16)))
            addView(box, FrameLayout.LayoutParams(width, WRAP_CONTENT))
        }
        frame.measure(View.MeasureSpec.makeMeasureSpec(width + dp(8), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        var height = frame.measuredHeight

        val at = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val screenHeight = context.resources.displayMetrics.heightPixels
        val roomAbove = at[1] - dp(48)
        val roomBelow = screenHeight - (at[1] + anchor.height) - dp(48)
        val above = height <= roomAbove || (height > roomBelow && roomAbove > roomBelow)
        // 옵션이 많아 들어갈 자리가 모자라면 상자 안을 스크롤하게 줄인다.
        val room = if (above) roomAbove else roomBelow
        if (height > room) {
            val scroll = box.getChildAt(1)
            scroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (scroll.measuredHeight - (height - room)).coerceAtLeast(dp(120)))
            frame.measure(View.MeasureSpec.makeMeasureSpec(width + dp(8), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            height = frame.measuredHeight
        }
        val x = at[0] + (anchor.width - width) / 2 - dp(4)
        val y = if (above) at[1] - height + dp(4) else at[1] + anchor.height - dp(4)
        // 꼬리: 상자 아래(또는 위) 가장자리에 중심을 두어 반쯤 튀어나오게. 안쪽 반은 상자에 가린다.
        (tail.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = (if (above) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
            if (above) bottomMargin = -dp(8) else topMargin = -dp(8)
        }

        return PopupWindow(frame, width + dp(8), height, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            animationStyle = android.R.style.Animation_Dialog
            showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }
}
