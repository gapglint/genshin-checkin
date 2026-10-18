package com.lesumein.hoyocheckin

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/** 메인 화면에 그릴 내용. 저장소에서 만들고, 화면은 이것만 보고 그린다. */
data class MainUiState(
    val loggedIn: Boolean,
    val uid: String? = null,
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
    /** 출첵·보충이 진행 중이면 그 안내. */
    val busy: String? = null,
)

/** activity_main 레이아웃을 MainUiState대로 채운다. 클릭 처리는 액티비티가 붙인다. */
class MainScreen(val root: View) {
    private val context = root.context
    private val statusChip: TextView = root.findViewById(R.id.statusChip)
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
    val primaryButton: MaterialButton = root.findViewById(R.id.primaryButton)
    val makeUpCard: View = root.findViewById(R.id.makeUpCard)
    private val makeUpText: TextView = root.findViewById(R.id.makeUpText)
    val makeUpButton: MaterialButton = root.findViewById(R.id.makeUpButton)
    val makeUpSwitch: MaterialSwitch = root.findViewById(R.id.makeUpSwitch)
    val rowMakeUpAlerts: View = root.findViewById(R.id.rowMakeUpAlerts)
    val rowOpenPage: View = root.findViewById(R.id.rowOpenPage)
    val rowAccount: View = root.findViewById(R.id.rowAccount)
    private val accountTitle: TextView = root.findViewById(R.id.accountTitle)
    private val accountSub: TextView = root.findViewById(R.id.accountSub)
    val rowLogout: View = root.findViewById(R.id.rowLogout)

    init {
        // 둥근 모서리 밖으로 장식·눌림 효과가 삐져나오지 않게 자른다.
        root.findViewById<View>(R.id.heroCard).clipToOutline = true
        root.findViewById<View>(R.id.settingsGroup).clipToOutline = true
        root.findViewById<View>(R.id.timingGroup).clipToOutline = true
    }

    fun render(s: MainUiState) {
        renderChip(s)

        // 로그인 전에는 숫자 대신 할 일을 크게 보여 준다.
        heroDayRow.visibility = if (s.loggedIn) View.VISIBLE else View.GONE
        heroStats.visibility = if (s.loggedIn) View.VISIBLE else View.GONE
        heroDay.text = s.totalDays.toString()
        heroMonth.text = if (s.loggedIn) "${s.month}월 누적 출석" else "로그인하면 매일\n대신 출석해요"
        heroMonth.textSize = if (s.loggedIn) 14f else 24f
        heroMonth.setTextColor(ContextCompat.getColor(context, if (s.loggedIn) R.color.hero_muted else R.color.hero_ink))
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

        accountTitle.text = "로그인 상태 확인"
        accountSub.text = "UID ${s.uid ?: "확인 불가"} · 자동 출석 켜짐"
        // 로그인 전에는 위의 큰 버튼이 로그인을 맡는다.
        rowAccount.visibility = if (s.loggedIn) View.VISIBLE else View.GONE
        rowLogout.visibility = if (s.loggedIn) View.VISIBLE else View.GONE
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
        statusChip.setTextColor(ContextCompat.getColor(context, textColor))
    }
}
