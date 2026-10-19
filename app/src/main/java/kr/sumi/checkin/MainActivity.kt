package kr.sumi.checkin

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.widget.Toast
import android.app.Activity
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Date

class MainActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var screen: MainScreen
    /** 지금 화면에 보이는 계정. 없으면 로그인 전 화면. */
    private val store: Store? get() = settings.current()
    private var busy: String? = null
    private var loadingNickname = false
    private var countingRewards = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawBehindSystemBars(window)
        setContentView(R.layout.activity_main)
        settings = AppSettings(this)
        screen = MainScreen(findViewById(R.id.root))
        applySystemBarInsets(findViewById(R.id.content))
        applySystemBarInsets(screen.settingsPanel)
        screen.settingsButton.setOnClickListener { showSettings(true) }
        screen.settingsBack.setOnClickListener { showSettings(false) }
        screen.setupBanner.setOnClickListener { showSettings(true) }
        screen.tileHistory.setOnClickListener {
            store?.let { startActivity(RewardHistoryActivity.intent(this, it.accountId)) }
        }
        screen.tileRedeem.setOnClickListener {
            when {
                !settings.redeemEnabled -> showSettings(true)
                store?.redeemRole == null -> chooseRedeemRole()
                else -> redeemNow()
            }
        }

        screen.onAccountChip = { id ->
            if (id == null) {
                startActivity(LoginActivity.loginIntent(this))
            } else {
                settings.currentId = id
                refresh()
            }
        }
        screen.primaryButton.setOnClickListener {
            if (store == null) startActivity(LoginActivity.loginIntent(this)) else checkInNow()
        }
        screen.makeUpButton.setOnClickListener { makeUpNow() }
        screen.makeUpCard.setOnClickListener { explainMakeUp() }
        screen.rowNotifications.setOnClickListener { chooseNotifications() }
        screen.rowOpenPage.setOnClickListener {
            store?.let { startActivity(LoginActivity.checkInPageIntent(this, it.accountId)) }
        }
        screen.rowAccount.setOnClickListener { checkLogin() }
        screen.rowLogout.setOnClickListener { confirmLogout() }
        for (link in listOf(screen.privacyLink, screen.sourceLink)) link.paintFlags = link.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        screen.privacyLink.setOnClickListener { openWeb(PRIVACY_URL) }
        screen.sourceLink.setOnClickListener { openWeb(SOURCE_URL) }
        screen.rowExactAlarm.setOnClickListener { openExactAlarmSettings() }
        screen.rowRedeem.setOnClickListener { screen.redeemSwitch.toggle() }
        screen.redeemSwitch.setOnCheckedChangeListener { _, checked -> settings.redeemEnabled = checked }
        screen.rowRedeemRole.setOnClickListener { chooseRedeemRole() }
        screen.rowRedeemNow.setOnClickListener { redeemNow() }
        screen.rowBattery.setOnClickListener { requestBatteryExemption() }
        screen.rowHibernation.setOnClickListener { openHibernationSettings() }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // 앱 업데이트 등으로 예약이 사라졌어도 다시 건다. 이미 있으면 그대로 둔다.
        if (settings.hasAccounts) CheckIn.schedule(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 정확한 알람을 허용하고 돌아왔을 수 있으니 알람을 다시 건다.
        if (settings.hasAccounts) DailyAlarm.schedule(this)
        refresh()
        loadNicknamesIfMissing()
        loadCalendarIfStale()
    }

    private var closeSettings: Any? = null

    /** 설정은 같은 화면 위에 연다. 열려 있으면 뒤로 가기로 닫는다. */
    private fun showSettings(show: Boolean) {
        screen.settingsPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) screen.settingsPanel.findViewById<View>(R.id.settingsScroll).scrollTo(0, 0)
        if (Build.VERSION.SDK_INT >= 33) setCloseSettingsOnBack(show)
    }

    @TargetApi(33)
    private fun setCloseSettingsOnBack(on: Boolean) {
        val current = closeSettings as android.window.OnBackInvokedCallback?
        if (on && current == null) {
            val callback = android.window.OnBackInvokedCallback { showSettings(false) }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            closeSettings = callback
        } else if (!on && current != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(current)
            closeSettings = null
        }
    }

    /** Android 12 이하용. 13 이상은 OnBackInvokedCallback이 처리한다(매니페스트에서 켜 둠). */
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Android 13 미만용")
    override fun onBackPressed() {
        if (screen.settingsPanel.visibility == View.VISIBLE) showSettings(false) else super.onBackPressed()
    }

    private var loadingCalendar = false
    /** 출석·보충을 했으면 달력(받은 칸, 놓친 날)을 다시 받아 온다. */
    @Volatile private var calendarDirty = false

    /** 지금 계정의 출석 보상 달력을 하루 한 번 조용히 받아 온다(화면을 막지 않는다). */
    private fun loadCalendarIfStale(force: Boolean = false) {
        val store = store ?: return
        if (loadingCalendar || (!force && CheckInCalendar.isFresh(store))) return
        loadingCalendar = true
        Thread {
            try {
                CheckInCalendar.refresh(store)
            } catch (e: Exception) {
            }
            runOnUiThread {
                loadingCalendar = false
                refresh()
            }
        }.start()
    }

    /** 닉네임을 아직 모르는 계정이 있으면 조용히 불러온다(화면을 막지 않는다). */
    private fun loadNicknamesIfMissing() {
        val missing = settings.accounts().filter { it.accountRole == null }
        if (missing.isEmpty() || loadingNickname) return
        loadingNickname = true
        Thread {
            for (account in missing) {
                try {
                    Redeem.refreshAccount(account)
                } catch (e: Exception) {
                }
            }
            runOnUiThread {
                loadingNickname = false
                refresh()
            }
        }.start()
    }

    /** 알림을 눌러 들어왔으면 그 계정을 띄우고, 보충 출석 알림이면 바로 보충한다. */
    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ACCOUNT)?.let {
            settings.currentId = it
            intent.removeExtra(EXTRA_ACCOUNT)
        }
        if (intent?.getBooleanExtra(EXTRA_MAKE_UP, false) == true) {
            intent.removeExtra(EXTRA_MAKE_UP)
            makeUpNow()
        }
    }

    private fun refresh() {
        val store = store
        val today = Store.checkInDay()
        val signedToday = store?.signedToday == true
        // 지난달 기록이면 이번 달 누적은 0부터 다시 센다.
        val sameMonth = store?.signedDay?.startsWith(today.toString().substring(0, 7)) == true
        screen.render(
            MainUiState(
                loggedIn = store != null,
                accounts = settings.accounts().map { AccountChip(it.accountId, it.displayName, it.signedToday) },
                currentId = store?.accountId,
                uid = store?.accountId,
                nickname = store?.accountRole?.nickname,
                server = store?.accountRole?.serverName,
                signedToday = signedToday,
                totalDays = if (sameMonth) store?.signedTotal ?: 0 else 0,
                month = today.monthValue,
                reward = if (signedToday) store?.lastReward else null,
                lastChecked = store?.lastRunAt?.takeIf { it > 0 }
                    ?.let { DateFormat.format("M월 d일 HH:mm", Date(it)).toString() },
                loginExpiry = store?.loginExpiresAt?.let { DateFormat.format("yyyy년 M월 d일", Date(it)).toString() },
                nextRun = settings.nextRunAt.takeIf { store != null && it > 0 }?.let { describeNextRun(it) },
                needsExactAlarm = !DailyAlarm.canRunExactly(this),
                needsBatteryExemption = !getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(packageName),
                needsHibernationExemption = Build.VERSION.SDK_INT >= 30 && !packageManager.isAutoRevokeWhitelisted,
                note = store?.lastProblem?.takeIf { !signedToday && store.lastProblemDay == today.toString() },
                makeUpMissed = store?.makeUpMissed?.takeIf { store.makeUpDay == today.toString() },
                notificationsSummary = notificationsSummary(),
                redeemEnabled = settings.redeemEnabled,
                redeemRole = store?.redeemRole?.label(),
                redeemSummary = store?.lastRedeemSummary?.let { summary ->
                    val at = DateFormat.format("M월 d일 HH:mm", Date(store.lastRedeemAt))
                    "$at · $summary"
                },
                redeemShort = store?.lastRedeemSummary?.substringBefore(":")?.trim(),
                redeemWhen = store?.lastRedeemSummary?.let { DateFormat.format("M월 d일 HH:mm", Date(store.lastRedeemAt)).toString() },
                busy = busy,
                historyUnseen = store?.historyUnseen ?: 0,
                calendar = store?.takeIf { it.calendarDay?.take(7) == today.toString().take(7) }?.calendarAwards.orEmpty(),
                calendarMissed = store?.calendarMissed ?: 0,
                // 남은 날(오늘 아직 출석 전이면 오늘 포함)만큼 더 받을 수 있다.
                calendarReachable = (store?.signedTotal ?: 0) + today.lengthOfMonth() - today.dayOfMonth + (if (signedToday) 0 else 1),
            )
        )
        store?.let { countUnseenRewards(it) }
    }

    /** 새 보상 수를 조용히 센다. 첫 페이지 한 번만 받고, 같은 계정은 10분에 한 번까지. */
    private fun countUnseenRewards(store: Store) {
        if (ServerClock.now() - store.historyCountedAt < 10 * 60_000 || countingRewards) return
        countingRewards = true
        Thread {
            try {
                RewardHistory.countUnseen(store)
            } catch (e: Exception) {
            }
            runOnUiThread {
                countingRewards = false
                if (settings.currentId == store.accountId) refresh()
            }
        }.start()
    }

    /** 고른 계정의 로그인이 아직 유효한지 호요랩에 물어보고 알려 준다. 풀렸으면 다시 로그인하게 한다. */
    private fun checkLogin() {
        val store = store ?: return
        runInBackground("로그인 확인 중...") {
            val valid = CheckInClient(store.cookie ?: return@runInBackground null).isLoggedIn()
            runOnUiThread {
                val who = store.accountRole?.let { "${it.nickname} (${it.serverName})\n" } ?: ""
                AppDialog(this)
                    .setTitle(if (valid) "로그인돼 있어요" else "로그인이 풀렸어요")
                    .setMessage(
                        if (valid) "${who}HoYoLAB UID ${store.accountId} 계정으로 자동 출석 중이에요."
                        else "${who}HoYoLAB UID ${store.accountId} 계정을 다시 로그인해 주세요."
                    )
                    .setPositiveButton(if (valid) "확인" else "다시 로그인") {
                        if (!valid) startActivity(LoginActivity.loginIntent(this))
                    }
                    .apply { if (valid) setNeutralButton("다시 로그인") { startActivity(LoginActivity.loginIntent(this@MainActivity)) } }
                    .show()
            }
            null
        }
    }

    /**
     * 다음 자동 출석 시각을 사람이 읽는 문장으로. 서버 기준 시각을 폰 시간대로 보여 준다.
     * 폰 시계가 틀려도 실제 시각으로 보이도록 폰 시계 대신 서버 시각과의 차이로 '오늘/내일'을 정한다.
     */
    private fun describeNextRun(serverMs: Long): String {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(serverMs).atZone(zone)
        val today = Instant.ofEpochMilli(ServerClock.now()).atZone(zone).toLocalDate()
        val day = when (at.toLocalDate()) {
            today -> "오늘"
            today.plusDays(1) -> "내일"
            else -> "${at.monthValue}월 ${at.dayOfMonth}일"
        }
        return "%s %02d:%02d".format(day, at.hour, at.minute)
    }

    private fun openWeb(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    /** '사용하지 않는 앱 일시중지'를 끄는 화면(앱 정보). 이 앱은 사용자가 거의 열지 않으므로 꺼 두어야 계속 돈다. */
    private fun openHibernationSettings() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            startActivity(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.fromParts("package", packageName, null)))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) return
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    }

    @SuppressLint("BatteryLife") // 정해진 시각에 출석하는 것이 이 앱의 핵심 기능이라 예외를 요청한다.
    private fun requestBatteryExemption() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try {
            startActivity(request)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** 이 계정의 서버별 캐릭터를 불러와 리딤 보상을 받을 캐릭터를 고르게 한다. */
    private fun chooseRedeemRole() {
        val store = store ?: return
        runInBackground("캐릭터 불러오는 중...") {
            val roles = Redeem.roles(CheckInClient(store.cookie ?: return@runInBackground null))
            runOnUiThread { showRoleChoice(store, roles) }
            null
        }
    }

    private fun showRoleChoice(store: Store, roles: List<GameRole>) {
        if (roles.isEmpty()) {
            AppDialog(this)
                .setTitle("캐릭터가 없어요")
                .setMessage("이 계정에 연결된 원신 캐릭터를 찾지 못했어요. 로그인이 풀렸다면 다시 로그인해 주세요.")
                .setPositiveButton("확인")
                .show()
            return
        }
        val current = roles.indexOfFirst { it.uid == store.redeemRole?.uid }
        AppDialog(this)
            .setTitle("보상 받을 캐릭터")
            .setSingleChoiceItems(roles.map { it.label() }.toTypedArray(), current) { dialog, which ->
                store.redeemRoles = listOf(roles[which])
                store.accountRole = roles[which]
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("취소")
            .show()
    }

    private fun redeemNow() {
        val store = store ?: return
        runInBackground("리딤 코드 확인 중...") { redeemFor(store) }
    }

    private fun redeemFor(store: Store): String? {
        val report = Redeem.run(store)
        runOnUiThread {
            AppDialog(this)
                .setTitle(if (report.redeemed.isNotEmpty()) "리딤 코드 ${report.redeemed.size}개를 받았어요" else "리딤 코드")
                .setMessage(Redeem.details(report))
                .setPositiveButton("확인")
                .show()
        }
        return null
    }

    private fun checkInNow() {
        val store = store ?: return
        runInBackground("출석하는 중...") {
            calendarDirty = true
            CheckIn.run(this, store)?.let { CheckIn.describe(it) }
        }
    }

    /** 알림 종류를 고른다. 로그인·캡차 같은 문제는 항상 알린다. */
    private fun chooseNotifications() {
        val checked = booleanArrayOf(settings.notifyCheckIn, settings.makeUpAlerts, settings.notifyRedeem, settings.quietNight)
        AppDialog(this)
            .setTitle("알림")
            .setMultiChoiceItems(arrayOf(
                "매일 출석했을 때",
                "보충 출석을 할 수 있을 때",
                "리딤 코드를 받았을 때",
                "밤(오후 10시~오전 8시)에는 소리·진동 없이",
            ), checked)
            .setFooter("로그인이 풀리거나 캡차가 필요할 때는 항상 알려요.")
            .setNegativeButton("취소")
            .setPositiveButton("저장") {
                settings.notifyCheckIn = checked[0]
                settings.makeUpAlerts = checked[1]
                settings.notifyRedeem = checked[2]
                settings.quietNight = checked[3]
                refresh()
            }
            .show()
    }

    private fun notificationsSummary(): String {
        val kinds = listOfNotNull(
            "출석 완료".takeIf { settings.notifyCheckIn },
            "보충 출석".takeIf { settings.makeUpAlerts },
            "리딤 코드".takeIf { settings.notifyRedeem },
        )
        val what = if (kinds.isEmpty()) "문제가 있을 때만" else kinds.joinToString(" · ")
        return if (settings.quietNight) "$what · 밤에는 소리 없이" else what
    }

    /** 보충권이 무엇이고 어떻게 받는지. 보충 카드를 누르면 보여 준다. */
    private fun explainMakeUp() {
        AppDialog(this)
            .setTitle("보충 출석")
            .setMessage(
                "보충권 하나로 놓친 출석 하루를 채우고, 그 칸의 보상(원석 등)도 받아요.\n\n" +
                    "보충권은 호요랩 출석 이벤트의 간단한 임무(링크 방문 등)로 받아요. " +
                    "'보충하기'를 누르면 앱이 받을 수 있는 임무를 대신 끝내고, 보충권을 받아 바로 채워요.\n\n" +
                    "하루·한 달에 보충할 수 있는 횟수는 호요랩이 정해요. 다 쓰면 다음에 다시 할 수 있어요."
            )
            .setNegativeButton("닫기")
            .setPositiveButton("보충하기") { makeUpNow() }
            .show()
    }

    private fun makeUpNow() {
        val store = store ?: return
        runInBackground("보충 출석 준비 중...") {
            val result = MakeUp.perform(store) { step -> runOnUiThread { busy = step; refresh() } }
            calendarDirty = true
            runOnUiThread { showMakeUpResult(result) }
            null
        }
    }

    /** 네트워크 작업을 백그라운드에서 돌리는 동안 버튼을 막고 진행 안내를 보여 준다. 반환한 문장은 토스트로. */
    private fun runInBackground(label: String, work: () -> String?) {
        busy?.let {
            Toast.makeText(this, "$it 잠시만 기다려 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        busy = label
        refresh()
        Thread {
            val message = try {
                work()
            } catch (e: IOException) {
                "네트워크 오류: ${e.message}"
            }
            runOnUiThread {
                busy = null
                refresh()
                if (calendarDirty) {
                    calendarDirty = false
                    loadCalendarIfStale(force = true)
                }
                if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showMakeUpResult(result: MakeUpResult) {
        val title = if (result is MakeUpResult.Done) "보충 출석 완료" else "보충 출석"
        AppDialog(this)
            .setTitle(title)
            .setMessage(MakeUp.describe(result))
            .setPositiveButton("확인")
            .apply {
                if (result == MakeUpResult.LoginExpired) {
                    setNeutralButton("로그인") { startActivity(LoginActivity.loginIntent(this@MainActivity)) }
                }
            }
            .show()
    }

    private fun confirmLogout() {
        val store = store ?: return
        AppDialog(this)
            .setTitle("${store.displayName} 계정을 로그아웃할까요?")
            .setMessage("이 계정의 로그인과 기록을 지우고 이 계정은 자동 출석을 멈춰요. 다른 계정은 그대로예요.")
            .setPositiveButton("로그아웃") {
                settings.remove(store.accountId)
                if (!settings.hasAccounts) CheckIn.cancel(this)
                refresh()
            }
            .setNegativeButton("취소")
            .show()
    }

    companion object {
        private const val EXTRA_MAKE_UP = "make_up"
        private const val EXTRA_ACCOUNT = "account"
        private const val PRIVACY_URL = "https://apps.sumi.kr/checkin/privacy/"
        private const val SOURCE_URL = "https://github.com/lesumein/genshin-checkin"

        /** 그 계정을 띄워 앱을 연다(알림용). */
        fun accountIntent(context: Context, accountId: String) = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ACCOUNT, accountId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun makeUpIntent(context: Context, accountId: String) = accountIntent(context, accountId)
            .putExtra(EXTRA_MAKE_UP, true)

        /** 내용을 상태 바·내비게이션 바 아래까지 그린다. */
        @Suppress("DEPRECATION")
        fun drawBehindSystemBars(window: Window) {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
            } else {
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        }

        /** 화면 구멍(카메라 등)을 피해야 하는 여백. Android 9(API 28)부터 있다. */
        @TargetApi(28)
        private fun cutoutInsets(insets: WindowInsets): Rect {
            val cutout = insets.displayCutout ?: return Rect()
            return Rect(cutout.safeInsetLeft, cutout.safeInsetTop, cutout.safeInsetRight, cutout.safeInsetBottom)
        }

        /** 화면을 시스템 바 아래까지 그리고, 내용은 상태 바·내비게이션 바(와 화면 구멍)를 피하게 한다. */
        @Suppress("DEPRECATION")
        fun applySystemBarInsets(view: View) {
            val start = Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            view.setOnApplyWindowInsetsListener { v, insets ->
                val bars = if (Build.VERSION.SDK_INT >= 30) {
                    val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    Rect(i.left, i.top, i.right, i.bottom)
                } else {
                    val cutout = if (Build.VERSION.SDK_INT >= 28) cutoutInsets(insets) else Rect()
                    Rect(
                        maxOf(insets.systemWindowInsetLeft, cutout.left),
                        maxOf(insets.systemWindowInsetTop, cutout.top),
                        maxOf(insets.systemWindowInsetRight, cutout.right),
                        maxOf(insets.systemWindowInsetBottom, cutout.bottom),
                    )
                }
                v.setPadding(start.left + bars.left, start.top + bars.top, start.right + bars.right, start.bottom + bars.bottom)
                insets
            }
        }
    }
}
