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
    val more: Button = pillButton().apply { text = "더 보기"; visibility = View.GONE }
    /** 리딤 코드 줄을 눌렀을 때(상세 보기). */
    var onCodeClick: ((RedeemedCode) -> Unit)? = null
    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.color.bg)
        addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(list)
            addView(more, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(16), dp(8), dp(16), dp(24)) })
        }
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(content) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    fun render(s: HistoryUiState) {
        title.text = s.title
        status.text = s.status
        list.removeAllViews()
        list.addView(monthCard(s), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        list.addView(sectionLabel("리딤 코드"))
        list.addView(group(
            if (s.codes.isEmpty()) listOf(row(R.drawable.ic_item_gift, "아직 이 앱으로 받은 코드가 없어요", null, muted = true))
            else listOf(redeemTotals(s.codes)) + s.codes.map {
                val reward = it.reward?.let { raw -> RewardHistory.formatTotals(Redeem.rewardItems(raw), max = 4) }
                val code = it
                row(
                    R.drawable.ic_item_gift, it.code, DateFormat.format("M월 d일", Date(it.at)).toString(),
                    sub = reward ?: "보상은 게임 우편함에서 확인해요", unseen = it.at > s.seenRedeemAt,
                    onClick = { onCodeClick?.invoke(code) },
                )
            }
        ))

        for ((month, items) in s.entries.groupBy { it.date.take(7) }) {
            val sum = RewardHistory.formatTotals(RewardHistory.totals(items.map { it.name to it.count }), max = 2)
            list.addView(sectionLabel(RewardHistory.monthLabel(month), sum))
            list.addView(group(items.map {
                row(iconFor(it.name), "${it.name} ×${"%,d".format(it.count)}", RewardHistory.dayLabel(it.date), unseen = it.key in s.newAwardKeys)
            }))
        }
        more.visibility = if (s.entries.size < s.total) View.VISIBLE else View.GONE
    }

    /** 이번 달 받은 보상(출석·보충 + 이 앱으로 받은 리딤)을 크게. 메인 화면의 출석 카드와 같은 남색 카드. */
    private fun monthCard(s: HistoryUiState): View {
        val attendance = s.entries.filter { it.date.startsWith(s.month) }
        val codes = s.codes.filter { ServerClock.dayOf(it.at).toString().startsWith(s.month) }
        val items = attendance.map { it.name to it.count } + codes.mapNotNull { it.reward }.flatMap { Redeem.rewardItems(it) }
        val totals = RewardHistory.totals(items)
        val primo = totals.firstOrNull { it.first == "원석" }?.second ?: 0
        val mora = totals.firstOrNull { it.first == "모라" }?.second ?: 0
        val others = totals.count { it.first != "원석" && it.first != "모라" }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        card.addView(TextView(context).apply {
            text = "${RewardHistory.monthLabel(s.month).substringAfter(" ")}에 받은 보상"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(context.getColor(R.color.hero_muted))
        })
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stat(R.drawable.ic_item_primogem, "원석", if (s.loaded) "%,d".format(primo) else "–", gold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(stat(R.drawable.ic_item_mora, "모라", if (s.loaded) "%,d".format(mora) else "–", gold = false), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        card.addView(View(context).apply { setBackgroundResource(R.color.hero_line) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(12) })
        val facts = listOfNotNull(
            "출석·보충 ${attendance.size}번",
            "리딤 코드 ${codes.size}개",
            if (others > 0) "그 밖의 아이템 ${others}종" else null,
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

    /** 이 앱으로 받은 리딤 보상 합계: 원석·모라·코드 수를 아이콘과 큰 숫자로. */
    private fun redeemTotals(codes: List<RedeemedCode>): View {
        val totals = RewardHistory.totals(codes.mapNotNull { it.reward }.flatMap { Redeem.rewardItems(it) })
        fun amount(name: String) = "%,d".format(totals.firstOrNull { it.first == name }?.second ?: 0)
        val unknown = codes.count { it.reward == null }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            addView(TextView(context).apply {
                text = "이 앱으로 받은 리딤 보상"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(context.getColor(R.color.ink_muted))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(total(R.drawable.ic_item_primogem, amount("원석"), "원석"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(total(R.drawable.ic_item_mora, amount("모라"), "모라"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(total(R.drawable.ic_item_gift, "${codes.size}", "코드"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            if (unknown > 0) addView(TextView(context).apply {
                text = "보상을 모르는 코드 ${unknown}개는 합계에서 빠졌어요"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(context.getColor(R.color.ink_muted))
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun total(icon: Int, value: String, label: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getColor(R.color.ink))
                fontFeatureSettings = "tnum"
            })
            addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(context.getColor(R.color.ink_muted))
            })
        })
    }

    private fun stat(icon: Int, label: String, value: String, gold: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
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

    private fun iconFor(name: String) = when {
        name.contains("원석") -> R.drawable.ic_item_primogem
        name.contains("모라") -> R.drawable.ic_item_mora
        name.contains("경험") -> R.drawable.ic_item_book
        name.contains("광석") -> R.drawable.ic_item_ore
        else -> R.drawable.ic_item_gift
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
        sub: String? = null, onClick: (() -> Unit)? = null,
    ) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            var dot: View? = null
            if (unseen) {
                setBackgroundResource(R.color.unseen_bg)
                contentDescription = "새 보상, $title"
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
                    setImageResource(icon)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(9), dp(9), dp(9), dp(9))
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
