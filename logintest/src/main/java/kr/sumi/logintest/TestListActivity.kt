package kr.sumi.logintest

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 방식 목록. 하나씩 눌러 시험하고, 다 하면 결과를 복사해 보낸다. */
class TestListActivity : Activity() {
    private lateinit var list: LinearLayout
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0xF4, 0xF5, 0xF7))
            addView(list)
        }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val results = Results(this)
        list.addView(text("로그인 테스트", 22f, bold = true))
        list.addView(text(
            "로그인 방법을 위에서부터 하나씩 눌러 시험해 주세요.\n" +
                "• 로그인되면 자동으로 닫히고 ✅가 붙어요.\n" +
                "• 안 되면 '끝내기'를 누르고 어땠는지 골라 주세요.\n" +
                "• 계정은 저장하지 않고, 시험이 끝날 때마다 로그인 기록을 지워요.\n" +
                "• 다 하면 맨 아래 '결과 복사'를 눌러 보내 주세요.",
            14f,
        ).apply { setPadding(0, dp(8), 0, dp(16)) })

        for (s in Strategy.ALL) {
            val summary = results.summary(s.id)
            val mark = when {
                summary == null -> ""
                summary.startsWith("성공") -> "✅ "
                else -> "❌ "
            }
            list.addView(card {
                addView(text("$mark${s.title}", 16f, bold = true))
                addView(text(s.description, 13f, muted = true))
                summary?.let { addView(text(it, 13f).apply { setPadding(0, dp(4), 0, 0) }) }
                setOnClickListener { startActivity(TestActivity.intent(this@TestListActivity, s)) }
            })
        }

        list.addView(button("결과 복사해서 보내기", primary = true) {
            val report = results.report()
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("로그인 테스트 결과", report))
            Toast.makeText(this, "복사했어요. 카톡이나 메일에 붙여 넣어 보내 주세요.", Toast.LENGTH_LONG).show()
        })
        list.addView(button("결과 보기", primary = false) {
            AlertDialog.Builder(this).setMessage(results.report()).setPositiveButton("닫기", null).show()
        })
        list.addView(button("결과 지우기", primary = false) {
            results.clear()
            render()
        })
    }

    private fun text(value: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(if (muted) Color.rgb(0x66, 0x6A, 0x73) else Color.rgb(0x1B, 0x1D, 0x22))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card(build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.WHITE) }
        isClickable = true
        foreground = getDrawable(android.R.drawable.list_selector_background)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) }
        build()
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        minHeight = dp(52)
        setTextColor(if (primary) Color.WHITE else Color.rgb(0x1B, 0x1D, 0x22))
        background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(if (primary) Color.rgb(0x2F, 0x5B, 0xD8) else Color.WHITE)
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        setOnClickListener { action() }
    }
}
