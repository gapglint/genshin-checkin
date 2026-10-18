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
import android.app.Activity
import android.widget.Button
import java.io.IOException
import java.util.Date

/**
 * 고른 계정의 보상 내역. 알림함처럼 아직 확인하지 않은 보상은 배경을 칠하고, '모두 확인'으로 지운다.
 * 출석·보충 보상은 호요랩의 출석 보상 기록을 그대로 보여 주고(달별로 묶음, 20개씩 더 보기),
 * 리딤 코드는 호요랩에 조회 기능이 없어 이 앱이 받은 기록을 보여 준다.
 */
class RewardHistoryActivity : Activity() {
    private lateinit var store: Store
    private lateinit var list: LinearLayout
    private lateinit var more: Button
    private lateinit var status: TextView
    private lateinit var seenBar: View
    private lateinit var seenText: TextView
    private val entries = mutableListOf<RewardEntry>()
    private var total = 0
    private var page = 0
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.drawBehindSystemBars(window)
        store = intent.getStringExtra(EXTRA_ACCOUNT)?.let { AppSettings(this).account(it) } ?: return finish()

        val header = LayoutInflater.from(this).inflate(R.layout.header_bar, null)
        header.findViewById<TextView>(R.id.headerTitle).text = "보상 내역 · ${store.displayName}"
        header.findViewById<View>(R.id.headerBack).setOnClickListener { finish() }
        status = header.findViewById(R.id.headerStatus)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        more = pillButton(filled = false).apply {
            text = "더 보기"
            visibility = View.GONE
            setOnClickListener { loadPage() }
        }
        seenText = TextView(this).apply {
            setTextColor(context.getColor(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val seenButton = pillButton(filled = true).apply {
            text = "모두 확인"
            setOnClickListener {
                RewardHistory.markAllSeen(store, entries.firstOrNull())
                render()
            }
        }
        seenBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(4), dp(16), 0)
            addView(seenText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(seenButton)
            visibility = View.GONE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seenBar)
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
                    val pageEntries = RewardHistory.parseAwards(json)
                    if (page == 1) RewardHistory.ensureBaseline(store, pageEntries)
                    entries += pageEntries
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
        val codes = store.redeemLog
        val seenAt = store.historySeenRedeemAt
        val unseenAwards = RewardHistory.unseenAwards(entries, store.historySeenAwardKey)
        val unseenCodes = RewardHistory.unseenCodes(codes, seenAt)
        val unseen = unseenAwards + unseenCodes
        if (page > 0) store.historyUnseen = unseen
        seenBar.visibility = if (unseen > 0) View.VISIBLE else View.GONE
        seenText.text = "새 보상 ${unseen}개"

        list.addView(sectionLabel("리딤 코드"))
        list.addView(group(
            if (codes.isEmpty()) listOf(row("아직 이 앱으로 받은 코드가 없어요", null, muted = true))
            else codes.map { (code, at) ->
                row(code, DateFormat.format("M월 d일 HH:mm", Date(at)).toString(), unseen = at > seenAt)
            }
        ))

        // 출석·보충 보상은 달별로 묶는다. 목록 앞쪽 unseenAwards개가 새 보상이다.
        val newKeys = entries.take(unseenAwards).map { it.key }.toSet()
        for ((month, items) in entries.groupBy { it.date.take(7) }) {
            list.addView(sectionLabel(RewardHistory.monthLabel(month)))
            list.addView(group(items.map {
                row("${it.name} ×${it.count}", RewardHistory.dayLabel(it.date), unseen = it.key in newKeys)
            }))
        }
        more.visibility = if (entries.size < total) View.VISIBLE else View.GONE
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(context.getColor(R.color.ink_muted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(4), dp(20), 0, dp(8))
    }

    private fun group(rows: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_group)
        dividerDrawable = context.getDrawable(R.drawable.divider_inset)
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        clipToOutline = true
        rows.forEach { addView(it) }
    }

    private fun row(title: String, trailing: String?, muted: Boolean = false, unseen: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(20), dp(12), dp(16), dp(12))
        if (unseen) setBackgroundResource(R.color.unseen_bg)
        if (unseen) addView(TextView(context).apply {
            text = "새로"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(context.getColor(R.color.accent))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, dp(10), 0)
        })
        addView(TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (muted) 14f else 16f)
            setTextColor(context.getColor(if (muted) R.color.ink_muted else R.color.ink))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (trailing != null) addView(TextView(context).apply {
            text = trailing
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(context.getColor(R.color.ink_muted))
            fontFeatureSettings = "tnum"
        })
    }

    /** Material 3 버튼 모양(보이는 높이 40dp, 누르는 영역 48dp, 둥근 끝). filled면 채운 버튼, 아니면 테두리 버튼. */
    private fun pillButton(filled: Boolean) = Button(this).apply {
        isAllCaps = false
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val c = context.getColor(if (filled) R.color.on_action else R.color.action)
        setTextColor(c)
        background = buttonBackground(
            context,
            fill = if (filled) context.getColor(R.color.action) else 0,
            radiusDp = 20f,
            stroke = if (filled) null else context.getColor(R.color.ink_muted),
        )
        setPadding(dp(24), 0, dp(24), 0) // 배경 뒤에 줘야 유지된다
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ACCOUNT = "account"
        private const val PAGE_SIZE = RewardHistory.PAGE_SIZE

        fun intent(context: Context, accountId: String) =
            Intent(context, RewardHistoryActivity::class.java).putExtra(EXTRA_ACCOUNT, accountId)
    }
}
