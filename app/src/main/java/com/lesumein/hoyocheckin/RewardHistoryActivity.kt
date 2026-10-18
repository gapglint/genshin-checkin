package com.lesumein.hoyocheckin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.IOException
import java.util.Date

/** 보상 한 건. date는 서버 기준 'yyyy-MM-dd'. */
data class RewardEntry(val name: String, val count: Int, val date: String)

/**
 * 고른 계정의 보상 내역.
 * 출석·보충 보상은 호요랩의 출석 보상 기록을 그대로 보여 주고(달별로 묶음, 20개씩 더 보기),
 * 리딤 코드는 호요랩에 조회 기능이 없어 이 앱이 받은 기록을 보여 준다.
 */
class RewardHistoryActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var list: LinearLayout
    private lateinit var more: MaterialButton
    private lateinit var status: TextView
    private val entries = mutableListOf<RewardEntry>()
    private var total = 0
    private var page = 0
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        store = intent.getStringExtra(EXTRA_ACCOUNT)?.let { AppSettings(this).account(it) } ?: return finish()

        val header = LayoutInflater.from(this).inflate(R.layout.header_bar, null)
        header.findViewById<TextView>(R.id.headerTitle).text = "보상 내역 · ${store.displayName}"
        header.findViewById<View>(R.id.headerBack).setOnClickListener { finish() }
        status = header.findViewById(R.id.headerStatus)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        more = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "더 보기"
            visibility = View.GONE
            setOnClickListener { loadPage() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(list)
            addView(more, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(dp(16), 0, dp(16), dp(24))
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.bg)
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(this@RewardHistoryActivity).apply { addView(content) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        MainActivity.applySystemBarInsets(root)

        render()
        loadPage()
    }

    private fun loadPage() {
        val cookie = store.cookie ?: return
        if (loading) return
        loading = true
        status.text = "불러오는 중..."
        more.isEnabled = false
        Thread {
            val result = try {
                val json = CheckInClient(cookie).awardHistory(page + 1, PAGE_SIZE)
                if (json.optInt("retcode", -1) in CheckInClient.NOT_LOGGED_IN) {
                    Result.failure(IOException("로그인이 풀렸어요. 다시 로그인해 주세요."))
                } else {
                    Result.success(json)
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
            runOnUiThread {
                loading = false
                more.isEnabled = true
                result.onSuccess { json ->
                    page++
                    entries += parseAwards(json)
                    total = json.optJSONObject("data")?.optInt("total") ?: entries.size
                    status.text = "출석·보충 보상 ${total}개"
                }.onFailure {
                    status.text = "내역을 불러오지 못했어요. ${it.message ?: ""}".trim()
                }
                render()
            }
        }.start()
    }

    private fun render() {
        list.removeAllViews()

        list.addView(sectionLabel("리딤 코드"))
        val codes = store.redeemLog
        list.addView(group(
            if (codes.isEmpty()) listOf(row("아직 이 앱으로 받은 코드가 없어요", null, muted = true))
            else codes.map { (code, at) -> row(code, DateFormat.format("M월 d일 HH:mm", Date(at)).toString()) }
        ))

        // 출석·보충 보상은 달별로 묶는다.
        for ((month, items) in entries.groupBy { it.date.take(7) }) {
            list.addView(sectionLabel(monthLabel(month)))
            list.addView(group(items.map { row("${it.name} ×${it.count}", dayLabel(it.date)) }))
        }
        more.visibility = if (entries.size < total) View.VISIBLE else View.GONE
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.ink_muted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(4), dp(20), 0, dp(8))
    }

    private fun group(rows: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_group)
        dividerDrawable = ContextCompat.getDrawable(context, R.drawable.divider_inset)
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        clipToOutline = true
        rows.forEach { addView(it) }
    }

    private fun row(title: String, trailing: String?, muted: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(20), dp(12), dp(16), dp(12))
        addView(TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (muted) 14f else 16f)
            setTextColor(ContextCompat.getColor(context, if (muted) R.color.ink_muted else R.color.ink))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (trailing != null) addView(TextView(context).apply {
            text = trailing
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.ink_muted))
            fontFeatureSettings = "tnum"
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ACCOUNT = "account"
        private const val PAGE_SIZE = 20

        fun intent(context: Context, accountId: String) =
            Intent(context, RewardHistoryActivity::class.java).putExtra(EXTRA_ACCOUNT, accountId)

        /** 출석 보상 기록 응답을 보상 목록으로. created_at은 '날짜 시각' 문자열이거나 초 단위 숫자다. */
        internal fun parseAwards(json: JSONObject): List<RewardEntry> {
            val array = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
            return (0 until array.length()).map { array.getJSONObject(it) }.map {
                RewardEntry(it.optString("name"), it.optInt("cnt"), dateOf(it.optString("created_at")))
            }
        }

        private fun dateOf(raw: String): String = raw.toLongOrNull()
            ?.let { ServerClock.dayOf(if (it < 10_000_000_000L) it * 1000 else it).toString() }
            ?: raw.take(10)

        internal fun monthLabel(yearMonth: String): String {
            val parts = yearMonth.split("-")
            return if (parts.size == 2) "${parts[0]}년 ${parts[1].toInt()}월" else yearMonth
        }

        internal fun dayLabel(date: String): String {
            val parts = date.split("-")
            return if (parts.size == 3) "${parts[1].toInt()}월 ${parts[2].toInt()}일" else date
        }
    }
}
