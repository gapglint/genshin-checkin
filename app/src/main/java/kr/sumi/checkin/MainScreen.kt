package kr.sumi.checkin

import android.view.View
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout

/** 계정 칩 하나. */
data class AccountChip(val id: String, val label: String, val signedToday: Boolean)

/** 실시간 메모 카드에 그릴 내용. 문장은 액티비티가 지금 시각으로 만든다. */
data class NotesUi(
    val resin: Int,
    val maxResin: Int,
    /** 오른쪽 위 두 줄, 예: "7시간 44분 뒤 가득" / "오후 3:20". 가득 찼으면 "" / "가득 찼어요". */
    val fullLabel: String,
    val fullTime: String,
    val expeditions: String,
    val expeditionsSub: String,
    val coin: String,
    val coinSub: String,
    val commissions: String,
    val commissionsSub: String,
    /** 펼쳤을 때 맨 아래, 예: "오후 3:02 기준". */
    val updated: String,
)

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
    /** 로그인 만료 예정일(문장). 0.9.6 전에 로그인해서 모르면 null. */
    val loginExpiry: String? = null,
    /** 다음 자동 출석 예정 시각(이미 폰 시간대로 바꾼 문장). */
    val nextRun: String? = null,
    val needsExactAlarm: Boolean = false,
    val needsBatteryExemption: Boolean = false,
    /** 오래 안 열면 앱을 멈추는 '사용하지 않는 앱 일시중지'(Android 11+)가 켜져 있는지. */
    val needsHibernationExemption: Boolean = false,
    /** 출석이 안 됐을 때 마지막 결과(실패 이유 등). */
    val note: String? = null,
    /** 보충할 수 있는 놓친 날 수. 보충할 수 없으면 null. */
    val makeUpMissed: Int? = null,
    /** 알림 설정 요약, 예: "출석 완료 · 보충 출석 · 리딤 코드 · 밤에는 소리 없이". */
    val notificationsSummary: String = "출석 완료 · 보충 출석 · 리딤 코드 · 밤에는 소리 없이",
    val redeemEnabled: Boolean = true,
    /** 리딤 보상을 받을 캐릭터 설명. 아직 안 골랐으면 null. */
    val redeemRole: String? = null,
    val redeemSummary: String? = null,
    /** 리딤 바로가기 타일용 짧은 결과('새 코드 3개 입력')와 그 시각. */
    val redeemShort: String? = null,
    val redeemWhen: String? = null,
    /** 출첵·보충이 진행 중이면 그 안내. */
    val busy: String? = null,
    /** 진행률(0~1)을 아는 작업이면 막대를 그만큼 채운다. */
    val busyProgress: Float? = null,
    /** 아직 확인하지 않은 보상 수. */
    val historyUnseen: Int = 0,
    /** 이번 달 출석 보상(출석 횟수 순서). 아직 못 받아 왔으면 비어 있다. */
    val calendar: List<CalendarSlot> = emptyList(),
    /** 이번 달 안에 받을 수 있는 마지막 칸 번호(놓친 날을 보충하지 않으면 그 뒤 칸은 못 받는다). */
    val calendarReachable: Int = Int.MAX_VALUE,
    val calendarMissed: Int = 0,
    /** 실시간 메모. 아직 못 받았으면 null. */
    val notes: NotesUi? = null,
    /** 실시간 메모를 못 받은 이유. */
    val notesProblem: String? = null,
    val notesLoading: Boolean = false,
    val resinAlert: Boolean = true,
    /** 레진 알림 기준 개수. 0이면 가득 찼을 때. */
    val resinThreshold: Int = 180,
    /** 메인 화면에 보일 것(출석 카드는 항상). */
    val showNotes: Boolean = true,
    /** 레진 카드를 펼쳐 파견·주전자·일일 의뢰까지 보이는지. */
    val notesExpanded: Boolean = false,
    /** 펼치면 보일 항목(파견·주전자·일일 의뢰 중 고른 것). 비어 있으면 펼칠 게 없다. */
    val noteStats: List<String> = NOTE_STATS,
    /** 원신이 깔려 있으면 레진 카드에 '원신 켜기'를 보인다. */
    val gameInstalled: Boolean = false,
    /** '원신 켜기'로 열 앱 이름과 깔린 원신 수(둘 이상이면 설정에서 고른다). */
    val gameApp: String? = null,
    val gameAppCount: Int = 0,
    val showTiles: Boolean = true,
    val showCalendar: Boolean = true,
    val showReruns: Boolean = true,
    /** 복각표 카드: 지금(없으면 다음) 픽업 캐릭터, 기간 문구, 가장 오래 기다린 캐릭터. 자료가 아직 없으면 null. */
    val reruns: RerunCardUi? = null,
    /** 아직 목록에서 보지 않은 진행 중 이벤트 수(아이콘의 점). */
    val eventsUnseen: Int = 0,
    /** 출석 카드 아래 카드 순서(CARDS의 이름). */
    val cardOrder: List<String> = CARDS,
    /** 메인 화면을 그 자리에서 편집하는 중인지. 보이기로 한 카드는 내용이 없어도 보인다. */
    val editing: Boolean = false,
)

/** activity_main 레이아웃을 MainUiState대로 채운다. 클릭 처리는 액티비티가 붙인다. */
/** 메인 복각표 카드에 보일 것. chars는 (이름, 원소). */
data class RerunCardUi(
    val title: String,
    val period: String,
    val chars: List<Pair<String, String>>,
    val oldest: String?,
)

class MainScreen(val root: View) {
    private val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
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
    private val heroRewardIcon: android.widget.ImageView = root.findViewById(R.id.heroRewardIcon)
    private val heroNote: TextView = root.findViewById(R.id.heroNote)
    private val heroNext: TextView = root.findViewById(R.id.heroNext)
    private val timingSection: View = root.findViewById(R.id.timingSection)
    val rowExactAlarm: View = root.findViewById(R.id.rowExactAlarm)
    val rowBattery: View = root.findViewById(R.id.rowBattery)
    val rowHibernation: View = root.findViewById(R.id.rowHibernation)
    val primaryButton: Button = root.findViewById(R.id.primaryButton)
    val busyBar: View = root.findViewById(R.id.busyBar)
    val makeUpCard: View = root.findViewById(R.id.makeUpCard)
    private val makeUpText: TextView = root.findViewById(R.id.makeUpText)
    val makeUpButton: Button = root.findViewById(R.id.makeUpButton)
    val rowNotifications: View = root.findViewById(R.id.rowNotifications)
    private val notificationsSub: TextView = root.findViewById(R.id.notificationsSub)
    val settingsButton: View = root.findViewById(R.id.settingsButton)
    val settingsPanel: View = root.findViewById(R.id.settingsPanel)
    val settingsBack: View = settingsPanel.findViewById(R.id.headerBack)
    val setupBanner: View = root.findViewById(R.id.setupBanner)
    private val setupText: TextView = root.findViewById(R.id.setupText)
    private val tiles: View = root.findViewById(R.id.tiles)
    val tileRedeem: View = root.findViewById(R.id.tileRedeem)
    private val tileRedeemValue: TextView = root.findViewById(R.id.tileRedeemValue)
    private val tileRedeemSub: TextView = root.findViewById(R.id.tileRedeemSub)
    val tileHistory: View = root.findViewById(R.id.tileHistory)
    private val tileHistoryValue: TextView = root.findViewById(R.id.tileHistoryValue)
    private val tileHistorySub: TextView = root.findViewById(R.id.tileHistorySub)
    private val tileHistoryDot: View = root.findViewById(R.id.tileHistoryDot)
    private val calendarCard: View = root.findViewById(R.id.calendarCard)
    val rerunCard: View = root.findViewById(R.id.rerunCard)
    private val rerunTitle: TextView = root.findViewById(R.id.rerunTitle)
    private val rerunPeriod: TextView = root.findViewById(R.id.rerunPeriod)
    private val rerunChars: LinearLayout = root.findViewById(R.id.rerunChars)
    private val rerunOldest: TextView = root.findViewById(R.id.rerunOldest)
    private val calendarTitle: TextView = root.findViewById(R.id.calendarTitle)
    private val calendarCount: TextView = root.findViewById(R.id.calendarCount)
    private val calendarGrid: LinearLayout = root.findViewById(R.id.calendarGrid)
    private val calendarNote: TextView = root.findViewById(R.id.calendarNote)
    val notesCard: View = root.findViewById(R.id.notesCard)
    private val notesResin: TextView = root.findViewById(R.id.notesResin)
    private val notesFullLabel: TextView = root.findViewById(R.id.notesFullLabel)
    private val notesFullTime: TextView = root.findViewById(R.id.notesFullTime)
    private val notesChevron: View = root.findViewById(R.id.notesChevron)
    private val notesBar: LinearLayout = root.findViewById(R.id.notesBar)
    private val notesMore: View = root.findViewById(R.id.notesMore)
    private val notesStats: LinearLayout = root.findViewById(R.id.notesStats)
    private val notesUpdated: TextView = root.findViewById(R.id.notesUpdated)
    private val notesFooter: View = root.findViewById(R.id.notesFooter)
    val notesPlay: View = root.findViewById(R.id.notesPlay)
    val rowMainDisplay: View = root.findViewById(R.id.rowMainDisplay)
    val editButton: View = root.findViewById(R.id.editButton)
    val eventsButton: View = root.findViewById(R.id.eventsButton)
    private val eventsDot: View = root.findViewById(R.id.eventsDot)
    val editDone: View = root.findViewById(R.id.editDone)
    val cards: LinearLayout = root.findViewById(R.id.cards)
    /** 카드마다 감싸는 틀. 편집할 때 그 위에 편집 층을 겹친다(MainEditor). 태그는 카드 이름. */
    private val slots = mutableMapOf<String, android.widget.FrameLayout>()
    fun slot(card: String): android.widget.FrameLayout = slots.getValue(card)

    /** 편집 중 그림자를 줄 카드 바탕들(리딤·보상 타일은 두 장). */
    fun surfaces(card: String): List<View> = when (card) {
        CARD_NOTES -> listOf(notesCard)
        CARD_TILES -> listOf(tileRedeem, tileHistory)
        CARD_CALENDAR -> listOf(calendarCard)
        CARD_RERUNS -> listOf(rerunCard)
        else -> listOf(root.findViewById(R.id.heroCard))
    }
    val rowGameApp: View = root.findViewById(R.id.rowGameApp)
    private val gameAppSub: TextView = root.findViewById(R.id.gameAppSub)
    val rowAccount: View = root.findViewById(R.id.rowAccount)
    private val accountTitle: TextView = root.findViewById(R.id.accountTitle)
    private val accountSub: TextView = root.findViewById(R.id.accountSub)
    val rowLogout: View = root.findViewById(R.id.rowLogout)
    val privacyLink: TextView = root.findViewById(R.id.privacyLink)
    val sourceLink: TextView = root.findViewById(R.id.sourceLink)

    init {
        wrap(MainEditor.CARD_CHECK_IN, root.findViewById(R.id.heroCard))
        wrap(CARD_NOTES, notesCard)
        wrap(CARD_TILES, tiles)
        wrap(CARD_CALENDAR, calendarCard)
        wrap(CARD_RERUNS, rerunCard)
        // 둥근 모서리 밖으로 장식·눌림 효과가 삐져나오지 않게 자른다.
        root.findViewById<View>(R.id.heroCard).clipToOutline = true
        root.findViewById<View>(R.id.settingsGroup).clipToOutline = true
        root.findViewById<View>(R.id.timingGroup).clipToOutline = true
        notesCard.clipToOutline = true
        root.findViewById<View>(R.id.notesGroup).clipToOutline = true
        tileRedeem.clipToOutline = true
        tileHistory.clipToOutline = true
        rerunCard.clipToOutline = true
        setupBanner.clipToOutline = true
        makeUpCard.clipToOutline = true
        settingsPanel.findViewById<TextView>(R.id.headerTitle).text = "설정"
        settingsPanel.findViewById<View>(R.id.headerStatus).visibility = View.GONE
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
        heroRewardIcon.setImageResource(itemIcon(s.reward))
        heroNext.visibility = if (s.nextRun != null) View.VISIBLE else View.GONE
        heroNext.text = "다음 자동 출석 · ${s.nextRun}"

        rowExactAlarm.visibility = if (s.loggedIn && s.needsExactAlarm) View.VISIBLE else View.GONE
        rowBattery.visibility = if (s.loggedIn && s.needsBatteryExemption) View.VISIBLE else View.GONE
        rowHibernation.visibility = if (s.loggedIn && s.needsHibernationExemption) View.VISIBLE else View.GONE
        val setupNeeded = if (!s.loggedIn) 0 else listOf(s.needsExactAlarm, s.needsBatteryExemption, s.needsHibernationExemption).count { it }
        timingSection.visibility = if (setupNeeded > 0) View.VISIBLE else View.GONE
        // 메인에는 한 줄 안내만 두고, 자세한 항목은 설정 안에서 보여 준다.
        setupBanner.visibility = if (setupNeeded > 0) View.VISIBLE else View.GONE
        setupText.text = "제때 출석하려면 설정 ${setupNeeded}개가 필요해요"
        heroNote.text = s.note
        heroNote.visibility = if (s.note != null && s.busy == null) View.VISIBLE else View.GONE

        // 오늘 출석을 마쳤으면 누를 일이 없으니 버튼을 숨긴다(카드가 이미 알려 준다).
        primaryButton.visibility = if (s.loggedIn && s.signedToday && s.busy == null) View.GONE else View.VISIBLE
        primaryButton.isEnabled = s.busy == null
        busyBar.visibility = if (s.busy != null) View.VISIBLE else View.GONE
        (busyBar as android.widget.ProgressBar).apply {
            isIndeterminate = s.busyProgress == null
            max = 100
            setProgress(((s.busyProgress ?: 0f) * 100).toInt(), true)
        }
        primaryButton.text = when {
            s.busy != null -> s.busy
            !s.loggedIn -> "호요랩 로그인"
            else -> "지금 출석하기"
        }

        makeUpCard.visibility = if (s.loggedIn && s.makeUpMissed != null) View.VISIBLE else View.GONE
        makeUpText.text = "놓친 날 ${s.makeUpMissed ?: 0}일 · 보충권은 앱이 대신 받아 와요 ⓘ"
        makeUpButton.isEnabled = s.busy == null

        notificationsSub.text = s.notificationsSummary

        heroEyebrow.text = if (s.loggedIn && s.nickname != null) "${s.nickname} 님의 매일 출석" else "HoYoLAB 매일 출석체크"
        // 닉네임이 있으면 크게, HoYoLAB UID는 작게. 닉네임을 아직 못 불러왔으면 UID로.
        accountTitle.text = if (s.nickname != null) listOfNotNull(s.nickname, s.server).joinToString(" · ") else "로그인 상태 확인"
        accountSub.text = "HoYoLAB UID ${s.uid ?: "확인 불가"} · 마지막 확인 ${s.lastChecked ?: "아직 없음"}" +
            (s.loginExpiry?.let { "\n로그인 만료 예정 $it" } ?: "")
        // 로그인 전에는 위의 큰 버튼이 로그인을 맡는다.
        rowAccount.visibility = if (s.loggedIn) View.VISIBLE else View.GONE
        rowLogout.visibility = if (s.loggedIn) View.VISIBLE else View.GONE
        applyOrder(s.cardOrder)
        renderTiles(s)
        renderCalendar(s)
        renderNotes(s)
        // 편집 중에는 머리줄에 '완료'만, 잠깐 뜨는 안내·버튼은 숨겨 카드에만 집중하게 한다.
        editDone.visibility = if (s.editing) View.VISIBLE else View.GONE
        editButton.visibility = if (s.editing || !s.loggedIn) View.GONE else View.VISIBLE
        settingsButton.visibility = if (s.editing) View.GONE else View.VISIBLE
        if (s.editing) {
            primaryButton.visibility = View.GONE
            makeUpCard.visibility = View.GONE
            setupBanner.visibility = View.GONE
        }
        for (card in CARDS) slot(card).visibility = slot(card).getChildAt(0).visibility
    }

    private fun wrap(card: String, view: View) {
        val parent = view.parent as android.view.ViewGroup
        val index = parent.indexOfChild(view)
        parent.removeView(view)
        // 카드의 위쪽 여백 등을 틀 안에서도 그대로 쓰도록 직접 옮겨 담는다.
        val margins = view.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val slot = android.widget.FrameLayout(context).apply {
            tag = card
            addView(view, android.widget.FrameLayout.LayoutParams(margins.width, margins.height).apply {
                setMargins(margins.leftMargin, margins.topMargin, margins.rightMargin, margins.bottomMargin)
            })
        }
        parent.addView(slot, index, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT))
        slots[card] = slot
    }

    /** 카드를 저장된 순서대로 다시 놓는다. 이미 그 순서면 건드리지 않는다. */
    private fun applyOrder(order: List<String>) {
        val views = order.map { slot(it) }
        if ((0 until cards.childCount).map { cards.getChildAt(it) } == views) return
        cards.removeAllViews()
        views.forEach { cards.addView(it) }
    }

    /**
     * 실시간 메모 카드. 평소엔 레진 숫자·막대·가득 차는 시각만 보이고, 펼치면 파견·주전자·일일 의뢰가 나온다.
     * 아직 못 받았으면 이유와 '눌러서 다시 시도'만 보인다.
     */
    private fun renderNotes(s: MainUiState) {
        val n = s.notes
        notesCard.visibility = if (s.loggedIn && s.showNotes && (n != null || s.notesProblem != null || s.notesLoading || s.editing)) View.VISIBLE else View.GONE
        // 원신이 둘 이상 깔렸을 때만 어느 걸 켤지 고르게 한다.
        rowGameApp.visibility = if (s.gameApp != null && s.gameAppCount > 1) View.VISIBLE else View.GONE
        gameAppSub.text = s.gameApp

        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        notesBar.visibility = if (n != null) View.VISIBLE else View.GONE
        val expandable = s.noteStats.isNotEmpty()
        notesChevron.visibility = if (n != null && expandable) View.VISIBLE else View.GONE
        notesMore.visibility = if (n != null && expandable && s.notesExpanded) View.VISIBLE else View.GONE
        notesChevron.rotation = if (s.notesExpanded) 270f else 90f
        if (n == null) {
            notesFooter.visibility = View.GONE
            notesResin.textSize = 16f
            notesResin.text = when {
                s.notesLoading -> "불러오는 중..."
                s.notesProblem == null -> "레진"
                else -> "불러오지 못했어요"
            }
            notesFullLabel.text = ""
            notesFullTime.text = if (s.notesLoading) "" else "다시 시도"
            notesFullTime.setTextColor(context.getColor(R.color.accent))
            notesCard.contentDescription = s.notesProblem
            return
        }
        notesCard.contentDescription = null
        notesResin.textSize = 20f
        notesResin.text = android.text.SpannableString("${n.resin} / ${n.maxResin}").apply {
            val start = "${n.resin}".length
            setSpan(android.text.style.RelativeSizeSpan(0.7f), start, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(context.getColor(R.color.ink_muted)), start, length, 0)
        }
        notesFullLabel.text = n.fullLabel
        notesFullLabel.visibility = if (n.fullLabel.isEmpty()) View.GONE else View.VISIBLE
        notesFullTime.text = n.fullTime
        notesFullTime.setTextColor(context.getColor(if (n.resin >= n.maxResin) R.color.accent else R.color.ink))

        // 막대: 바탕(연한 색) 위에 찬 만큼만 레진 색.
        notesBar.removeAllViews()
        notesBar.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(3).toFloat()
            setColor(context.getColor(R.color.resin_track))
        }
        val filled = n.resin.coerceIn(0, n.maxResin)
        if (filled > 0) notesBar.addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(context.getColor(R.color.item_resin))
            }
        }, LinearLayout.LayoutParams(0, MATCH, filled.toFloat()))
        if (n.maxResin - filled > 0) notesBar.addView(View(context), LinearLayout.LayoutParams(0, MATCH, (n.maxResin - filled).toFloat()))
        notesBar.contentDescription = "레진 ${n.resin}개, 최대 ${n.maxResin}개"
        // 원신 켜기는 접혀 있어도 보인다. 기준 시각은 펼쳤거나 문제가 있을 때만(접힌 카드는 짧게).
        val expanded = s.notesExpanded && expandable
        notesPlay.visibility = if (s.gameInstalled) View.VISIBLE else View.GONE
        notesUpdated.text = s.notesProblem?.let { "$it · 눌러서 다시 시도" } ?: n.updated.takeIf { expanded }
        notesFooter.visibility = if (s.gameInstalled || !notesUpdated.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        if (!expanded) return

        notesStats.removeAllViews()
        mapOf(
            NOTE_EXPEDITIONS to Triple(R.drawable.ic_note_expedition, "탐사 파견", n.expeditions to n.expeditionsSub),
            NOTE_COIN to Triple(R.drawable.ic_note_teapot, "주전자 동전", n.coin to n.coinSub),
            NOTE_COMMISSIONS to Triple(R.drawable.ic_note_commission, "일일 의뢰", n.commissions to n.commissionsSub),
        ).filterKeys { it in s.noteStats }.values.forEach { (icon, label, values) ->
            notesStats.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(android.widget.ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(16), dp(16)))
                    addView(TextView(context).apply {
                        text = label
                        textSize = 12f
                        setTextColor(context.getColor(R.color.ink_muted))
                        maxLines = 1
                        setPadding(dp(4), 0, 0, 0)
                    })
                })
                addView(TextView(context).apply {
                    text = values.first
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(context.getColor(R.color.ink))
                    fontFeatureSettings = "tnum"
                    maxLines = 1
                    setPadding(0, dp(4), 0, 0)
                })
                addView(TextView(context).apply {
                    text = values.second
                    textSize = 12f
                    setTextColor(context.getColor(R.color.ink_muted))
                    maxLines = 2
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /**
     * 출석 보상 달력. 7칸씩 줄을 만들고, 칸마다 그 번째 출석의 보상 아이콘과 개수를 넣는다.
     * 받은 칸은 흐리게 하고 체크, 다음에 받을 칸은 금색 테두리, 이번 달 안에 못 받는 칸은 더 흐리게.
     */
    private fun renderCalendar(s: MainUiState) {
        calendarCard.visibility = if (s.loggedIn && s.showCalendar && (s.calendar.isNotEmpty() || s.editing)) View.VISIBLE else View.GONE
        rerunCard.visibility = if (s.loggedIn && s.showReruns) View.VISIBLE else View.GONE
        renderReruns(s.reruns)
        eventsButton.visibility = if (s.editing) View.GONE else View.VISIBLE
        eventsDot.visibility = if (s.eventsUnseen > 0) View.VISIBLE else View.GONE
        eventsButton.contentDescription = if (s.eventsUnseen > 0) "이벤트, 새 이벤트 ${s.eventsUnseen}개" else "이벤트"
        if (s.calendar.isEmpty()) {
            // 편집 중에 아직 못 받았으면 제목만.
            calendarTitle.text = "${s.month}월 출석 보상"
            calendarCount.text = ""
            calendarGrid.removeAllViews()
            calendarNote.visibility = View.GONE
            return
        }
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        calendarTitle.text = "${s.month}월 출석 보상"
        calendarCount.text = "${s.totalDays} / ${s.calendar.size}"
        calendarGrid.removeAllViews()
        val next = s.totalDays + 1
        for (rowStart in s.calendar.indices step 7) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until 7) {
                val index = rowStart + col
                val cell = android.widget.FrameLayout(context)
                if (index < s.calendar.size) {
                    val slot = s.calendar[index]
                    val number = index + 1
                    val done = number <= s.totalDays
                    cell.background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(context.getColor(R.color.item_tile))
                        if (number == next) setStroke(dp(2), context.getColor(R.color.accent))
                    }
                    cell.alpha = when {
                        done -> 0.55f
                        number > s.calendarReachable -> 0.35f
                        else -> 1f
                    }
                    cell.contentDescription = "${number}번째 출석, ${slot.name} ${slot.count}개" + if (done) ", 받음" else ""
                    cell.addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER
                        addView(android.widget.ImageView(context).apply { setImageResource(itemIcon(slot.name)) },
                            LinearLayout.LayoutParams(dp(22), dp(22)))
                        addView(TextView(context).apply {
                            text = CheckInCalendar.compact(slot.count)
                            textSize = 11f
                            setTextColor(context.getColor(R.color.ink))
                            gravity = android.view.Gravity.CENTER
                            maxLines = 1
                        })
                    }, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
                    cell.addView(TextView(context).apply {
                        text = "$number"
                        textSize = 9f
                        setTextColor(context.getColor(R.color.ink_muted))
                        setPadding(dp(5), dp(2), 0, 0)
                    })
                    if (done) cell.addView(android.widget.ImageView(context).apply { setImageResource(R.drawable.ic_check_badge) },
                        android.widget.FrameLayout.LayoutParams(dp(14), dp(14), android.view.Gravity.END or android.view.Gravity.TOP).apply {
                            topMargin = dp(3); marginEnd = dp(3)
                        })
                }
                row.addView(cell, LinearLayout.LayoutParams(0, dp(58), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            calendarGrid.addView(row)
        }
        // 다음 원석이 언제인지, 놓친 날이 있으면 보충 안내.
        val nextPrimo = (next..s.calendar.size).firstOrNull { s.calendar[it - 1].name.contains("원석") && it <= s.calendarReachable }
        val primoText = nextPrimo?.let {
            val days = it - next + (if (s.signedToday) 1 else 0)
            val whenText = if (days == 0) "오늘" else "${days}일 뒤"
            "다음 원석: $whenText ×${s.calendar[it - 1].count}"
        }
        val missedText = if (s.calendarMissed > 0) "놓친 날 ${s.calendarMissed}일 · '보충하기'를 누르면 흐린 칸도 받을 수 있어요" else null
        val note = listOfNotNull(primoText, missedText).joinToString("\n")
        calendarNote.text = note
        calendarNote.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 보상 이름에 맞는 아이콘. 모르는 아이템은 선물 상자. */
    private fun itemIcon(reward: String?) = when {
        reward == null || reward.contains("원석") -> R.drawable.ic_item_primogem
        reward.contains("모라") -> R.drawable.ic_item_mora
        reward.contains("경험") -> R.drawable.ic_item_book
        reward.contains("광석") -> R.drawable.ic_item_ore
        else -> R.drawable.ic_item_gift
    }

    /** 바로가기 타일: 리딤 코드(누르면 지금 확인), 보상 내역(누르면 열기). */
    private fun renderTiles(s: MainUiState) {
        tiles.visibility = if (s.loggedIn && s.showTiles) View.VISIBLE else View.GONE
        tileRedeemValue.text = when {
            !s.redeemEnabled -> "자동 입력 꺼짐"
            s.redeemRole == null -> "캐릭터를 골라 주세요"
            else -> s.redeemShort ?: "자동 입력 켜짐"
        }
        tileRedeemValue.setTextColor(context.getColor(if (s.redeemEnabled && s.redeemRole == null) R.color.accent else R.color.ink))
        tileRedeemSub.text = when {
            !s.redeemEnabled || s.redeemRole == null -> "눌러서 설정"
            s.redeemWhen != null -> s.redeemWhen
            else -> "눌러서 지금 확인"
        }
        tileRedeem.isEnabled = s.busy == null
        val unseen = if (s.historyUnseen >= RewardHistory.PAGE_SIZE) "${RewardHistory.PAGE_SIZE}개 이상" else "${s.historyUnseen}개"
        tileHistoryValue.text = if (s.historyUnseen > 0) "새 보상 $unseen" else "받은 보상 보기"
        tileHistoryValue.setTextColor(context.getColor(if (s.historyUnseen > 0) R.color.accent else R.color.ink))
        tileHistorySub.text = "이번 달 원석·모라 합계"
        tileHistoryDot.visibility = if (s.historyUnseen > 0) View.VISIBLE else View.GONE
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

    /** 복각표 카드: 픽업 캐릭터를 원소 동그라미와 원소 색 이름으로. 자료가 없으면 안내만. */
    private fun renderReruns(r: RerunCardUi?) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        rerunChars.removeAllViews()
        rerunTitle.text = r?.title ?: "복각표"
        rerunPeriod.text = r?.period ?: ""
        rerunOldest.text = r?.oldest ?: "캐릭터마다 마지막 픽업 뒤 몇 버전 지났는지 봐요"
        rerunChars.visibility = if (r?.chars.isNullOrEmpty()) View.GONE else View.VISIBLE
        for ((name, element) in r?.chars.orEmpty().take(4)) {
            val base = context.getColor(RerunActivity.elementColor(element))
            rerunChars.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                addView(android.widget.ImageView(context).apply {
                    RerunActivity.elementIcon(element)?.let { setImageResource(it) }
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(base)
                        setStroke(dp(2), context.getColor(R.color.gold))
                    }
                }, LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(TextView(context).apply {
                    text = name.trim().split(" ").last()
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(RerunActivity.light(base))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(6), 0, 0)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        rerunCard.contentDescription = listOfNotNull(r?.title, r?.chars?.joinToString { it.first }, r?.period, r?.oldest, "눌러서 복각표").joinToString(", ")
    }
}
