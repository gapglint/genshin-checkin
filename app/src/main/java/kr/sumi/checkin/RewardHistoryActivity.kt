package kr.sumi.checkin

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
 * 고른 계정의 보상 내역. 알림함처럼 화면을 열면 확인한 것으로 치고, 이번에 처음 보는 보상만 배경과 점으로 표시한다.
 * 위에는 이번 달 출석 보상·리딤 보상 합계를, 아래에는 출석·보충 보상(달별, 20개씩 더 보기)과 리딤 코드를 보여 준다.
 * 리딤 코드는 호요랩에 조회 기능이 없어 이 앱이 받은 기록이고, 보상은 위키에 적힌 값이다.
 */
class RewardHistoryActivity : Activity() {
    private lateinit var store: Store
    private lateinit var list: LinearLayout
    private lateinit var more: Button
    private lateinit var status: TextView
    private lateinit var newText: TextView
    // 화면을 열 때의 확인 표시. 열자마자 확인한 것으로 저장하지만, 이번 화면에서는 이 기준으로 새 보상을 칠한다.
    private var seenAwardKey: String? = null
    private var seenRedeemAt = 0L
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
        newText = TextView(this).apply {
            setTextColor(context.getColor(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(4), dp(16), 0)
            visibility = View.GONE
        }
        // 리딤 코드는 여는 순간 확인한 것으로 친다. 출석 보상은 첫 페이지를 받은 뒤에(기준이 있어야 해서).
        seenRedeemAt = store.historySeenRedeemAt
        store.historySeenRedeemAt = ServerClock.now()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(newText)
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
        Thread { if (Redeem.fillMissingRewards(store)) runOnUiThread { render() } }.start()
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
                    if (page == 1) {
                        RewardHistory.ensureBaseline(store, pageEntries)
                        seenAwardKey = store.historySeenAwardKey
                        pageEntries.firstOrNull()?.let { store.historySeenAwardKey = it.key }
                        store.historyUnseen = 0
                    }
                    entries += pageEntries
                    total = json.optJSONObject("data")?.optInt("total") ?: entries.size
                    status.text = "출석·보충 보상 총 ${"%,d".format(total)}번 받았어요"
                    // 이번 달 합계가 맞도록, 받아 온 목록이 아직 이번 달에서 끝나면 한 페이지 더 받는다.
                    val month = entries.firstOrNull()?.date?.take(7)
                    if (month != null && entries.size < total && entries.last().date.startsWith(month) && page < 3) {
                        list.post { loadPage() }
                    }
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
        val unseenAwards = if (page > 0) RewardHistory.unseenAwards(entries, seenAwardKey) else 0
        val unseenCodes = RewardHistory.unseenCodes(codes, seenRedeemAt)
        val unseen = unseenAwards + unseenCodes
        newText.visibility = if (unseen > 0) View.VISIBLE else View.GONE
        newText.text = "새로 받은 보상 ${unseen}개"

        // 합계: 이번 달 출석·보충 보상, 이 앱으로 받은 리딤 보상
        val thisMonth = entries.firstOrNull()?.date?.take(7)
        val monthEntries = entries.filter { thisMonth != null && it.date.startsWith(thisMonth) }
        val redeemItems = codes.mapNotNull { it.reward }.flatMap { Redeem.rewardItems(it) }
        list.addView(sectionLabel("합계"))
        list.addView(group(listOf(
            row(
                if (thisMonth != null) "${RewardHistory.monthLabel(thisMonth).substringAfter(" ")} 출석·보충" else "이번 달 출석·보충",
                if (monthEntries.isNotEmpty()) "${monthEntries.size}번" else null,
                sub = RewardHistory.formatTotals(RewardHistory.totals(monthEntries.map { it.name to it.count }))
                    ?: if (page == 0) "불러오는 중..." else "아직 받은 보상이 없어요",
            ),
            row(
                "리딤 코드",
                if (codes.isNotEmpty()) "${codes.size}개" else null,
                sub = RewardHistory.formatTotals(RewardHistory.totals(redeemItems))
                    ?: if (codes.isEmpty()) "아직 이 앱으로 받은 코드가 없어요" else "보상은 게임 우편함에서 확인해요",
            ),
        )))

        list.addView(sectionLabel("리딤 코드"))
        list.addView(group(
            if (codes.isEmpty()) listOf(row("아직 이 앱으로 받은 코드가 없어요", null, muted = true))
            else codes.map {
                val reward = it.reward?.let { raw -> RewardHistory.formatTotals(Redeem.rewardItems(raw), max = 4) }
                row(
                    it.code, DateFormat.format("M월 d일 HH:mm", Date(it.at)).toString(),
                    sub = reward ?: "보상은 게임 우편함에서 확인해요", unseen = it.at > seenRedeemAt,
                )
            }
        ))

        // 출석·보충 보상은 달별로 묶는다. 목록 앞쪽 unseenAwards개가 이번에 처음 보는 보상이다.
        val newKeys = entries.take(unseenAwards).map { it.key }.toSet()
        for ((month, items) in entries.groupBy { it.date.take(7) }) {
            list.addView(sectionLabel(RewardHistory.monthLabel(month)))
            list.addView(group(items.map {
                row("${it.name} ×${"%,d".format(it.count)}", RewardHistory.dayLabel(it.date), unseen = it.key in newKeys)
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

    private fun row(title: String, trailing: String?, muted: Boolean = false, unseen: Boolean = false, sub: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(20), dp(12), dp(16), dp(12))
        if (unseen) setBackgroundResource(R.color.unseen_bg)
        // 알림함처럼 새 보상은 배경을 칠하고 앞에 작은 금색 점을 찍는다.
        if (unseen) {
            contentDescription = "새 보상, $title"
            addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(context.getColor(R.color.accent))
                }
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (muted) 14f else 16f)
                setTextColor(context.getColor(if (muted) R.color.ink_muted else R.color.ink))
            })
            if (sub != null) addView(TextView(context).apply {
                text = sub
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(context.getColor(R.color.ink_muted))
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
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
