package kr.sumi.checkin

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView

/**
 * Material 3 대화상자와 같은 모양의 대화상자(라이브러리 없이 직접 그림).
 * 모서리 28dp 카드, 제목 24sp, 본문 14sp, 오른쪽 아래 글자 버튼. 쓰는 법은 AlertDialog.Builder와 비슷하다.
 */
class AppDialog(private val activity: Activity) {
    private var title: String? = null
    private var message: String? = null
    private var items: Array<String>? = null
    private var checkedItem = -1
    private var onItem: ((DialogInterface, Int) -> Unit)? = null
    // 버튼은 왼쪽부터 중립, 취소, 확인 순서로 놓인다.
    private var neutral: Pair<String, (() -> Unit)?>? = null
    private var negative: Pair<String, (() -> Unit)?>? = null
    private var positive: Pair<String, (() -> Unit)?>? = null
    private var onCancel: (() -> Unit)? = null

    fun setTitle(text: String) = apply { title = text }
    fun setMessage(text: String) = apply { message = text }
    fun setPositiveButton(text: String, action: (() -> Unit)? = null) = apply { positive = text to action }
    fun setNegativeButton(text: String, action: (() -> Unit)? = null) = apply { negative = text to action }
    fun setNeutralButton(text: String, action: (() -> Unit)? = null) = apply { neutral = text to action }
    fun setOnCancelListener(action: () -> Unit) = apply { onCancel = action }

    /** 하나를 고르는 목록. 고르면 바로 action이 불린다(닫는 건 action이 정한다). */
    fun setSingleChoiceItems(list: Array<String>, checked: Int, action: (DialogInterface, Int) -> Unit) = apply {
        items = list
        checkedItem = checked
        onItem = action
    }

    fun show(): Dialog {
        val context = activity
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()
        fun color(id: Int) = context.getColor(id)
        val dialog = Dialog(context)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(24f), dp(24f), dp(8f))
            background = GradientDrawable().apply { cornerRadius = dp(28f).toFloat(); setColor(color(R.color.surface)) }
        }
        title?.let {
            body.addView(TextView(context).apply {
                text = it
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(color(R.color.ink))
                setPadding(0, 0, 0, dp(16f))
            })
        }
        message?.let {
            body.addView(ScrollView(context).apply {
                addView(TextView(context).apply {
                    text = it
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setLineSpacing(dp(2f).toFloat(), 1f)
                    setTextColor(color(R.color.ink_muted))
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
        }
        items?.let { list ->
            val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            list.forEachIndexed { index, label ->
                group.addView(RadioButton(context).apply {
                    text = label
                    isChecked = index == checkedItem
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(color(R.color.ink))
                    buttonTintList = android.content.res.ColorStateList.valueOf(color(R.color.action))
                    minHeight = dp(48f)
                    setOnClickListener { onItem?.invoke(dialog, index) }
                })
            }
            body.addView(ScrollView(context).apply { addView(group) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(16f), 0, dp(8f))
        }
        listOfNotNull(neutral, negative, positive).forEach { (label, action) ->
            row.addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(color(R.color.action))
                gravity = Gravity.CENTER
                minHeight = dp(48f)
                background = buttonBackground(context, fill = 0, radiusDp = 20f)
                setPadding(dp(12f), 0, dp(12f), 0) // 배경 뒤에 줘야 유지된다
                setOnClickListener {
                    dialog.dismiss()
                    action?.invoke()
                }
            })
        }
        body.addView(row)

        dialog.setContentView(body)
        dialog.setOnCancelListener { onCancel?.invoke() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 화면 양옆 24dp를 남기고, 넓은 화면에서는 560dp까지만.
            val width = minOf(context.resources.displayMetrics.widthPixels - dp(48f), dp(560f))
            setLayout(width, WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }
}
