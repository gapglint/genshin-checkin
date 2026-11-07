package kr.sumi.checkin

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date

/** 보상 내역 화면에 그릴 내용. month는 서버 기준 이번 달('yyyy-MM'). */
data class HistoryUiState(
    val title: String,
    val status: String,
    val month: String,
    val entries: List<RewardEntry> = emptyList(),
    val total: Int = 0,
    val loaded: Boolean = false,
    val codes: List<RedeemedCode> = emptyList(),
    /** 이번에 처음 보는 출석·보충 보상의 키와, 이 시각 뒤에 받은 리딤 코드는 '새 보상'으로 칠한다. */
    val newAwardKeys: Set<String> = emptySet(),
    val seenRedeemAt: Long = Long.MAX_VALUE,
)

/**
 * 보상 내역 화면. 맨 위에 이번 달 받은 원석·모라를 크게 보여 주는 카드, 그 아래 리딤 코드와 달별 출석·보충 보상.
 * 줄마다 아이템 아이콘을 붙여 글자만 늘어서지 않게 한다. 화면만 그리고, 데이터와 확인 처리는 RewardHistoryActivity가 한다.
 */
class RewardHistoryScreen(private val context: Context) {
    private val header: View = LayoutInflater.from(context).inflate(R.layout.header_bar, null)
    val back: View = header.findViewById(R.id.headerBack)
    private val title: TextView = header.findViewById(R.id.headerTitle)
    private val status: TextView = header.findViewById(R.id.headerStatus)
    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), 0, dp(16), dp(8))
    }
    val more: Button = pillButton().apply { text = context.getString(R.string.home_history_more); visibility = View.GONE }
    /** 리딤 코드 줄을 눌렀을 때(상세 보기). */
    var onCodeClick: ((RedeemedCode) -> Unit)? = null
    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.color.bg)
        addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // 넓은 화면에서는 가운데 600dp 폭으로 모은다.
        val content = MaxWidthLinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(list)
            addView(more, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(16), dp(8), dp(16), dp(24)) })
        }
        addView(ScrollView(context).apply {
            scroll = this
            isVerticalScrollBarEnabled = false
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    fun render(s: HistoryUiState) {
        title.text = s.title
        status.text = s.status
        list.removeAllViews()
        // 아이템 이름 → 호요랩 그림 주소(출석 기록에 같이 온다). 리딤 보상·합계 아이콘도 이것으로.
        art = s.entries.filter { it.icon.isNotEmpty() }.associate { it.name to it.icon } + art
        val months = s.entries.map { it.date.take(7) }.distinct()
        if (months.size > 1) list.addView(monthChips(months))
        list.addView(monthCard(s), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        // 리딤 코드: 합계는 제목 옆 한 줄로, 코드마다 받은 보상.
        val codeTotals = RewardHistory.formatTotals(RewardHistory.totals(s.codes.mapNotNull { it.reward }.flatMap { Redeem.rewardItems(it) }), max = 2)
        list.addView(sectionLabel(context.getString(R.string.home_history_codes), codeTotals?.takeIf { s.codes.isNotEmpty() && it.isNotEmpty() }))
        // 여러 캐릭터로 받았을 때만 줄마다 어느 캐릭터인지 적는다.
        val manyRoles = s.codes.mapNotNull { it.role }.distinct().size > 1
        list.addView(group(
            if (s.codes.isEmpty()) listOf(row(R.drawable.ic_item_gift, context.getString(R.string.home_history_no_codes), null, muted = true))
            else s.codes.map {
                val reward = it.reward?.let { raw -> RewardHistory.formatTotals(Redeem.rewardItems(raw), max = 4) }
                    ?: context.getString(R.string.home_history_check_mail)
                val code = it
                row(
                    R.drawable.ic_item_gift, it.code, DateFormat.format(context.getString(R.string.core_date_month_day_pattern), Date(it.at)).toString(),
                    sub = if (manyRoles && it.role != null) "${it.role}\n$reward" else reward, unseen = it.at > s.seenRedeemAt,
                    onClick = { onCodeClick?.invoke(code) },
                )
            }
        ))

        monthViews.clear()
        for ((month, items) in s.entries.groupBy { it.date.take(7) }) {
            val sum = RewardHistory.formatTotals(RewardHistory.totals(items.map { it.name to it.count }), max = 2)
            list.addView(sectionLabel(RewardHistory.monthLabel(month), sum).also { monthViews[month] = it })
            list.addView(group(items.map {
                row(iconFor(it.name), "${it.name} ×${"%,d".format(it.count)}", RewardHistory.dayLabel(it.date), unseen = it.key in s.newAwardKeys, artUrl = it.icon)
            }))
        }
        more.visibility = if (s.entries.size < s.total) View.VISIBLE else View.GONE
        // 게임 아이템 그림을 쓰면 출처를 적는다.
        if (art.isNotEmpty() && Lobby.officialArtAllowed(context)) list.addView(TextView(context).apply {
            text = context.getString(R.string.home_history_art_credit)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(context.getColor(R.color.ink_muted))
            setPadding(dp(4), dp(14), dp(4), 0)
        })
    }

    /** 받은 아이템 그림 주소(이름 → 주소). 더 받아 온 쪽에서도 쓰도록 모아 둔다. */
    private var art: Map<String, String> = emptyMap()
    private val monthViews = mutableMapOf<String, View>()
    private var scroll: ScrollView? = null

    /** 달 버튼: 누르면 그 달 목록으로 내려간다. 맨 앞(이번 달)은 맨 위로. */
    private fun monthChips(months: List<String>) = android.widget.HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            months.forEachIndexed { i, month ->
                addView(TextView(context).apply {
                    val m = month.substringAfter("-").toIntOrNull() ?: 0
                    text = context.getString(R.string.home_history_month_chip, m, RewardHistory.fullMonth(m))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(context.getColor(if (i == 0) R.color.on_action else R.color.ink_muted))
                    if (i == 0) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(99).toFloat()
                        if (i == 0) setColor(context.getColor(R.color.action)) else { setColor(context.getColor(R.color.surface)); setStroke(dp(1), context.getColor(R.color.line)) }
                    }
                    setOnClickListener { if (i == 0) scroll?.smoothScrollTo(0, 0) else monthViews[month]?.let { v -> scroll?.smoothScrollTo(0, v.top + list.top) } }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(6) })
            }
        })
    }

    /** 이번 달 받은 보상(출석·보충 + 이 앱으로 받은 리딤)을 크게. 메인 화면의 출석 카드와 같은 남색 카드. */
    private fun monthCard(s: HistoryUiState): View {
        val attendance = s.entries.filter { it.date.startsWith(s.month) }
        val codes = s.codes.filter { ServerClock.dayOf(it.at).toString().startsWith(s.month) }
        val items = attendance.map { it.name to it.count } + codes.mapNotNull { it.reward }.flatMap { Redeem.rewardItems(it) }
        val totals = RewardHistory.totals(items)
        val primo = totals.filter { ItemIcons.isPrimogem(it.first) }.sumOf { it.second }
        val mora = totals.filter { ItemIcons.isMora(it.first) }.sumOf { it.second }
        val others = totals.count { !ItemIcons.isPrimogem(it.first) && !ItemIcons.isMora(it.first) }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        card.addView(TextView(context).apply {
            val month = s.month.substringAfter("-").toIntOrNull()?.takeIf { it in 1..12 } ?: ServerClock.today().monthValue
            text = context.getString(R.string.home_history_month_rewards, month, RewardHistory.fullMonth(month))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(context.getColor(R.color.hero_muted))
        })
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stat(R.drawable.ic_item_primogem, context.getString(R.string.home_history_primogems), if (s.loaded) "%,d".format(primo) else "–", gold = true,
                artUrl = art.entries.firstOrNull { ItemIcons.isPrimogem(it.key) }?.value), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(stat(R.drawable.ic_item_mora, context.getString(R.string.home_history_mora), if (s.loaded) "%,d".format(mora) else "–", gold = false,
                artUrl = art.entries.firstOrNull { ItemIcons.isMora(it.key) }?.value), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        card.addView(View(context).apply { setBackgroundResource(R.color.hero_line) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(12) })
        val facts = listOfNotNull(
            context.resources.getQuantityString(R.plurals.home_history_fact_attendance, attendance.size, attendance.size),
            context.resources.getQuantityString(R.plurals.home_history_fact_codes, codes.size, codes.size),
            if (others > 0) context.resources.getQuantityString(R.plurals.home_history_fact_others, others, others) else null,
        )
        card.addView(TextView(context).apply {
            text = facts.joinToString("  ·  ")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(context.getColor(R.color.hero_ink))
        })

        return FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_hero)
            clipToOutline = true
            addView(ImageView(context).apply {
                setImageResource(R.drawable.hero_motif)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.END or Gravity.TOP).apply { topMargin = -dp(30); marginEnd = -dp(30) })
            addView(card)
        }
    }

    private fun stat(icon: Int, label: String, value: String, gold: Boolean, artUrl: String? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val big = artUrl != null && Lobby.officialArtAllowed(context)
            addView(ImageView(context).apply { setItemImage(this, icon, artUrl) }, LinearLayout.LayoutParams(dp(if (big) 26 else 18), dp(if (big) 26 else 18)).apply { marginEnd = dp(6) })
            addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(context.getColor(R.color.hero_muted))
            })
        })
        addView(TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = context.resources.getFont(R.font.hoyo_numerals)
            setTextColor(context.getColor(if (gold) R.color.gold else R.color.hero_ink))
            includeFontPadding = false
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun iconFor(name: String) = ItemIcons.of(name)

    /**
     * 공식 그림을 쓸 수 있고 주소가 있으면 호요랩 아이템 그림(받아 둔 것, 없으면 앱 아이콘을 두고 받아서 바꾼다), 아니면 앱 아이콘.
     * 받아 둔 그림을 바로 넣었으면 true.
     */
    private fun setItemImage(view: ImageView, icon: Int, url: String?, onLate: (ImageView.() -> Unit)? = null): Boolean {
        val cached = url?.let { Lobby.cachedItemIcon(context, it) }
        if (cached != null) { view.setImageBitmap(cached); return true }
        view.setImageResource(icon)
        if (url != null) Lobby.itemIconAsync(context, url) { view.setImageBitmap(it); onLate?.invoke(view) }
        return false
    }

    private fun sectionLabel(text: String, trailing: String? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        setPadding(dp(4), dp(22), dp(4), dp(8))
        addView(TextView(context).apply {
            this.text = text
            setTextColor(context.getColor(R.color.ink_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (trailing != null) addView(TextView(context).apply {
            this.text = trailing
            setTextColor(context.getColor(R.color.ink_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            fontFeatureSettings = "tnum"
        })
    }

    private fun group(rows: List<View>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_group)
        dividerDrawable = context.getDrawable(R.drawable.divider_inset)
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        clipToOutline = true
        rows.forEach { addView(it) }
    }

    /**
     * 아이콘 · 제목(+설명) · 오른쪽 날짜. 새 보상은 배경을 칠하고 아이콘 위에 금색 점을 찍는다.
     * 새 보상 줄을 누르면 알림함처럼 표시가 지워지고, onClick이 있으면 그것도 부른다.
     */
    private fun row(
        icon: Int, title: String, trailing: String?, muted: Boolean = false, unseen: Boolean = false,
        sub: String? = null, onClick: (() -> Unit)? = null, artUrl: String? = null,
    ) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            var dot: View? = null
            if (unseen) {
                setBackgroundResource(R.color.unseen_bg)
                contentDescription = context.getString(R.string.home_history_new_reward, title)
            }
            if (unseen || onClick != null) {
                foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
                    getDrawable(0).also { recycle() }
                }
                setOnClickListener {
                    background = null
                    dot?.visibility = View.GONE
                    contentDescription = null
                    onClick?.invoke()
                }
            }
            addView(FrameLayout(context).apply {
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    // 게임 아이템 그림이면 동그라미를 거의 채우게, 앱 아이콘이면 지금처럼 작게.
                    val official = setItemImage(this, icon, artUrl) { setPadding(dp(4), dp(4), dp(4), dp(4)) }
                    if (official) setPadding(dp(4), dp(4), dp(4), dp(4)) else setPadding(dp(9), dp(9), dp(9), dp(9))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(context.getColor(R.color.item_tile))
                    }
                    alpha = if (muted) 0.6f else 1f
                }, FrameLayout.LayoutParams(dp(40), dp(40)))
                if (unseen) addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(context.getColor(R.color.accent))
                        setStroke(dp(2), context.getColor(R.color.unseen_bg))
                    }
                    dot = this
                }, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.END or Gravity.TOP))
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(context.getColor(R.color.ink_muted))
                fontFeatureSettings = "tnum"
            })
        }

    /** Material 3 테두리 버튼 모양(보이는 높이 40dp, 누르는 영역 48dp, 둥근 끝). */
    private fun pillButton() = Button(context).apply {
        isAllCaps = false
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(context.getColor(R.color.action))
        background = buttonBackground(context, fill = 0, radiusDp = 20f, stroke = context.getColor(R.color.ink_muted))
        setPadding(dp(24), 0, dp(24), 0) // 배경 뒤에 줘야 유지된다
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
