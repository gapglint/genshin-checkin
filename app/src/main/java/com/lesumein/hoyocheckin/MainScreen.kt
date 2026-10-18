package com.lesumein.hoyocheckin

import android.view.View
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout

/** 계정 칩 하나. */
data class AccountChip(val id: String, val label: String, val signedToday: Boolean)

/** 메인 화면에 그릴 내용. 저장소에서 만들고, 화면은 이것만 보고 그린다. 계정에 관한 값은 지금 고른 계정 것이다. */
data class MainUiState(
    val loggedIn: Boolean,
    val accounts: List<AccountChip> = emptyList(),
    val currentId: String? = null,
    val uid: String? = null,
    /** 게임 닉네임과 서버. 아직 못 불러왔으면 null. */
    val nickname: String? = null,
    val server: String? = null,
    val signedToday: Boolean = false,
    val totalDays: Int = 0,
    val month: Int,
    val reward: String? = null,
    val lastChecked: String? = null,
    /** 다음 자동 출석 예정 시각(이미 폰 시간대로 바꾼 문장). */
    val nextRun: String? = null,
    val needsExactAlarm: Boolean = false,
    val needsBatteryExemption: Boolean = false,
    /** 출석이 안 됐을 때 마지막 결과(실패 이유 등). */
    val note: String? = null,
    /** 보충할 수 있는 놓친 날 수. 보충할 수 없으면 null. */
    val makeUpMissed: Int? = null,
    val makeUpAlerts: Boolean = true,
    val redeemEnabled: Boolean = true,
    /** 리딤 보상을 받을 캐릭터 설명. 아직 안 골랐으면 null. */
    val redeemRole: String? = null,
    val redeemSummary: String? = null,
    /** 출첵·보충이 진행 중이면 그 안내. */
    val busy: String? = null,
    /** 아직 확인하지 않은 보상 수. */
    val historyUnseen: Int = 0,
)

/** activity_main 레이아웃을 MainUiState대로 채운다. 클릭 처리는 액티비티가 붙인다. */
class MainScreen(val root: View) {
    private val context = root.context
    private val statusChip: TextView = root.findViewById(R.id.statusChip)
    private val accountBar: View = root.findViewById(R.id.accountBar)
    private val accountChips: LinearLayout = root.findViewById(R.id.accountChips)
    /** 계정 칩을 누르면 그 계정 ID, '계정 추가'를 누르면 null. 액티비티가 정한다. */
    var onAccountChip: (String?) -> Unit = {}
    private val heroEyebrow: TextView = root.findViewById(R.id.heroEyebrow)
    private val heroDay: TextView = root.findViewById(R.id.heroDay)
    private val heroDayRow: View = root.findViewById(R.id.heroDayRow)
    private val heroStats: View = root.findViewById(R.id.heroStats)
    private val heroMonth: TextView = root.findViewById(R.id.heroMonth)
    private val heroReward: TextView = root.findViewById(R.id.heroReward)
    private val heroChecked: TextView = root.findViewById(R.id.heroChecked)
    private val heroNote: TextView = root.findViewById(R.id.heroNote)
    private val heroNextRow: View = root.findViewById(R.id.heroNextRow)
    private val heroNext: TextView = root.findViewById(R.id.heroNext)
    private val timingSection: View = root.findViewById(R.id.timingSection)
    val rowExactAlarm: View = root.findViewById(R.id.rowExactAlarm)
    val rowBattery: View = root.findViewById(R.id.rowBattery)
    val primaryButton: Button = root.findViewById(R.id.primaryButton)
    val makeUpCard: View = root.findViewById(R.id.makeUpCard)
    private val makeUpText: TextView = root.findViewById(R.id.makeUpText)
    val makeUpButton: Button = root.findViewById(R.id.makeUpButton)
    val makeUpSwitch: StarSwitch = root.findViewById(R.id.makeUpSwitch)
    val rowMakeUpAlerts: View = root.findViewById(R.id.rowMakeUpAlerts)
    val rowOpenPage: View = root.findViewById(R.id.rowOpenPage)
    val rowHistory: View = root.findViewById(R.id.rowHistory)
    private val historySub: TextView = root.findViewById(R.id.historySub)
    val rowAccount: View = root.findViewById(R.id.rowAccount)
    private val accountTitle: TextView = root.findViewById(R.id.accountTitle)
    private val accountSub: TextView = root.findViewById(R.id.accountSub)
    val rowLogout: View = root.findViewById(R.id.rowLogout)
    private val redeemSection: View = root.findViewById(R.id.redeemGroup)
    val rowRedeem: View = root.findViewById(R.id.rowRedeem)
    val redeemSwitch: StarSwitch = root.findViewById(R.id.redeemSwitch)
    val rowRedeemRole: View = root.findViewById(R.id.rowRedeemRole)
    private val redeemRoleSub: TextView = root.findViewById(R.id.redeemRoleSub)
    val rowRedeemNow: View = root.findViewById(R.id.rowRedeemNow)
    private val redeemSummary: TextView = root.findViewById(R.id.redeemSummary)

    init {
        // 둥근 모서리 밖으로 장식·눌림 효과가 삐져나오지 않게 자른다.
        root.findViewById<View>(R.id.heroCard).clipToOutline = true
        root.findViewById<View>(R.id.settingsGroup).clipToOutline = true
        root.findViewById<View>(R.id.timingGroup).clipToOutline = true
        redeemSection.clipToOutline = true
        // Material 3 버튼 모양: 큰 버튼은 모서리 16dp로 채우고, 보충하기는 글자 버튼.
        primaryButton.background = buttonBackground(context, context.getColor(R.color.action), 16f)
        makeUpButton.background = buttonBackground(context, fill = 0, radiusDp = 20f)
        // 배경을 넣으면 안쪽 여백이 바뀌므로 배경 뒤에 다시 준다.
        val buttonPadding = (16 * context.resources.displayMetrics.density).toInt()
        makeUpButton.setPadding(buttonPadding, 0, buttonPadding, 0)
    }

    fun render(s: MainUiState) {
        renderAccounts(s)
        renderChip(s)

        // 로그인 전에는 숫자 대신 할 일을 크게 보여 준다.
        heroDayRow.visibility = if (s.loggedIn) View.VISIBLE else View.GONE
        heroStats.visibility = if (s.loggedIn) View.VISIBLE else View.GONE
        heroDay.text = s.totalDays.toString()
        heroMonth.text = if (s.loggedIn) "${s.month}월 누적 출석" else "로그인하면 매일\n대신 출석해요"
        heroMonth.textSize = if (s.loggedIn) 14f else 24f
        heroMonth.setTextColor(context.getColor(if (s.loggedIn) R.color.hero_muted else R.color.hero_ink))
        heroReward.text = when {
            s.reward != null -> s.reward
            s.signedToday -> "출석 완료"
            else -> "출석하면 표시돼요"
        }
        heroChecked.text = s.lastChecked ?: "아직 없음"
        heroNextRow.visibility = if (s.nextRun != null) View.VISIBLE else View.GONE
        heroNext.text = s.nextRun

        rowExactAlarm.visibility = if (s.loggedIn && s.needsExactAlarm) View.VISIBLE else View.GONE
        rowBattery.visibility = if (s.loggedIn && s.needsBatteryExemption) View.VISIBLE else View.GONE
        timingSection.visibility =
            if (s.loggedIn && (s.needsExactAlarm || s.needsBatteryExemption)) View.VISIBLE else View.GONE
        heroNote.text = s.note
        heroNote.visibility = if (s.note != null && s.busy == null) View.VISIBLE else View.GONE

        // 오늘 출석을 마쳤으면 누를 일이 없으니 버튼을 숨긴다(카드가 이미 알려 준다).
        primaryButton.visibility = if (s.loggedIn && s.signedToday && s.busy == null) View.GONE else View.VISIBLE
        primaryButton.isEnabled = s.busy == null
        primaryButton.text = when {
            s.busy != null -> s.busy
            !s.loggedIn -> "호요랩 로그인"
            else -> "지금 출석하기"
        }

        makeUpCard.visibility = if (s.loggedIn && s.makeUpMissed != null) View.VISIBLE else View.GONE
        makeUpText.text = "이번 달에 놓친 날 ${s.makeUpMissed ?: 0}일을 보충권으로 채워요"
        makeUpButton.isEnabled = s.busy == null

        makeUpSwitch.isChecked = s.makeUpAlerts
        redeemSwitch.isChecked = s.redeemEnabled
        redeemRoleSub.text = s.redeemRole ?: if (s.loggedIn) "눌러서 골라 주세요" else "로그인하면 고를 수 있어요"
        redeemRoleSub.setTextColor(context.getColor(if (s.redeemRole == null && s.loggedIn) R.color.accent else R.color.ink_muted))
        redeemSummary.text = s.redeemSummary ?: "아직 확인하지 않았어요"
        rowRedeemRole.isEnabled = s.loggedIn && s.busy == null
        rowRedeemNow.isEnabled = s.loggedIn && s.busy == null

        heroEyebrow.text = if (s.loggedIn && s.nickname != null) "${s.nickname} 님의 매일 출석" else "HoYoLAB 매일 출석체크"
        // 닉네임이 있으면 크게, HoYoLAB UID는 작게. 닉네임을 아직 못 불러왔으면 UID로.
        accountTitle.text = if (s.nickname != null) listOfNotNull(s.nickname, s.server).joinToString(" · ") else "로그인 상태 확인"
        accountSub.text = "HoYoLAB UID ${s.uid ?: "확인 불가"} · 자동 출석 켜짐"
        // 로그인 전에는 위의 큰 버튼이 로그인을 맡는다.
        rowAccount.visibility = if (s.loggedIn) View.VISIBLE else View.GONE
        rowLogout.visibility = if (s.loggedIn) View.VISIBLE else View.GONE
        rowHistory.visibility = if (s.loggedIn) View.VISIBLE else View.GONE
        historySub.text = if (s.historyUnseen > 0) {
            "새 보상 ${if (s.historyUnseen >= RewardHistory.PAGE_SIZE) "${RewardHistory.PAGE_SIZE}개 이상" else "${s.historyUnseen}개"} · 눌러서 확인"
        } else {
            "출석·보충·리딤 코드로 받은 보상을 날짜별로 봐요"
        }
        historySub.setTextColor(context.getColor(if (s.historyUnseen > 0) R.color.accent else R.color.ink_muted))
    }

    private fun renderAccounts(s: MainUiState) {
        accountBar.visibility = if (s.accounts.isEmpty()) View.GONE else View.VISIBLE
        accountChips.removeAllViews()
        for (account in s.accounts) {
            // 오늘 출석을 마친 계정은 별 표시로 한눈에 보이게 한다.
            val label = if (account.signedToday) "★ ${account.label}" else account.label
            accountChips.addView(makeChip(context, label, checked = account.id == s.currentId, checkable = true).apply {
                setOnClickListener { onAccountChip(account.id) }
            })
        }
        accountChips.addView(makeChip(context, "+ 계정 추가", checked = false, checkable = false).apply {
            setOnClickListener { onAccountChip(null) }
        })
    }

    private fun renderChip(s: MainUiState) {
        val (text, background, textColor) = when {
            s.busy != null -> Triple("확인 중", R.drawable.bg_chip_wait, R.color.hero_ink)
            !s.loggedIn -> Triple("로그인 필요", R.drawable.bg_chip_warn, R.color.on_gold)
            s.signedToday -> Triple("오늘 출석 완료", R.drawable.bg_chip_done, R.color.on_gold)
            else -> Triple("출석 전", R.drawable.bg_chip_wait, R.color.hero_ink)
        }
        statusChip.text = text
        statusChip.setBackgroundResource(background)
        // 배경을 바꾸면 안쪽 여백이 사라지므로 레이아웃의 값(가로 12dp, 세로 5dp)을 다시 준다.
        val density = context.resources.displayMetrics.density
        statusChip.setPadding((12 * density).toInt(), (5 * density).toInt(), (12 * density).toInt(), (5 * density).toInt())
        statusChip.setTextColor(context.getColor(textColor))
    }
}
