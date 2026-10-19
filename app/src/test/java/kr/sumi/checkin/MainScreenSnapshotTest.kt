package kr.sumi.checkin

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

/** 메인 화면을 상태별·테마별로 그려 본다. ./gradlew recordPaparazziDebug 로 PNG를 만든다. */
class MainScreenSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "Theme.HoyoCheckin",
    )

    private val sampleCalendar = List(31) { i ->
        when {
            i % 7 == 6 -> CalendarSlot("원석", 20)
            i % 7 == 2 -> CalendarSlot("모라", 5000)
            i % 7 == 4 -> CalendarSlot("영웅의 경험", 3)
            i % 7 == 0 -> CalendarSlot("정제용 양질 광석", 3)
            else -> CalendarSlot("모라", 1000 * (i % 3 + 1))
        }
    }
    private val signed = MainUiState(
        loggedIn = true, accounts = listOf(AccountChip("100000000", "여행자", true), AccountChip("100000001", "부계정", false)),
        currentId = "100000000", uid = "100000000", nickname = "여행자", server = "아시아 서버", signedToday = true, totalDays = 17, month = 10,
        reward = "원석 x20", lastChecked = "10월 18일 21:04", nextRun = "내일 01:03", makeUpMissed = 2,
        redeemRole = "아시아 서버 · 여행자 (Lv.60) · UID 800000000", redeemSummary = "10월 18일 01:04 · 새 코드 1개 입력: VesnaOnPatrol", redeemShort = "새 코드 1개 입력", redeemWhen = "10월 18일 01:04", historyUnseen = 3,
        calendar = sampleCalendar, calendarReachable = 30, calendarMissed = 1,
    )
    private val waiting = MainUiState(
        loggedIn = true, uid = "800000000", totalDays = 16, month = 10, lastChecked = "10월 18일 03:00",
        nextRun = "오늘 09:00", note = "요청이 너무 잦다고 해요. 잠시 뒤 자동으로 다시 시도해요.",
        needsExactAlarm = true, needsBatteryExemption = true, needsHibernationExemption = true,
    )
    private val loggedOut = MainUiState(loggedIn = false, month = 10)

    private fun snap(state: MainUiState, night: Boolean) {
        paparazzi.unsafeUpdateConfig(
            DeviceConfig.PIXEL_6.copy(nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT, screenHeight = 4200)
        )
        val view = paparazzi.inflate<android.view.View>(R.layout.activity_main)
        MainScreen(view).render(state)
        paparazzi.snapshot(view)
    }

    @Test fun signedLight() = snap(signed, night = false)
    @Test fun signedDark() = snap(signed, night = true)
    @Test fun waitingLight() = snap(waiting, night = false)
    @Test fun loggedOutDark() = snap(loggedOut, night = true)

    @Test fun settingsLight() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(screenHeight = 4200))
        val view = paparazzi.inflate<android.view.View>(R.layout.activity_main)
        MainScreen(view).apply { render(waiting.copy(redeemRole = signed.redeemRole, redeemSummary = signed.redeemSummary)) }
            .settingsPanel.visibility = android.view.View.VISIBLE
        paparazzi.snapshot(view)
    }

    // 여러 기기·조건: 화면이 잘리거나 겹치지 않는지 본다(실제 화면 높이 그대로).
    private fun device(config: DeviceConfig, state: MainUiState = signed) {
        paparazzi.unsafeUpdateConfig(config)
        val view = paparazzi.inflate<android.view.View>(R.layout.activity_main)
        MainScreen(view).render(state)
        paparazzi.snapshot(view)
    }

    @Test fun deviceSmallPhone() = device(DeviceConfig.NEXUS_5)
    @Test fun deviceBigPhone() = device(DeviceConfig.PIXEL_6_PRO)
    @Test fun deviceFontLarge() = device(DeviceConfig.PIXEL_6.copy(fontScale = 1.3f))
    @Test fun deviceFontHuge() = device(DeviceConfig.PIXEL_6.copy(fontScale = 2.0f))
    @Test fun deviceSplitScreen() = device(DeviceConfig.PIXEL_6.copy(screenHeight = 1100))
    @Test fun deviceFoldableOpen() = device(DeviceConfig.PIXEL_6.copy(screenWidth = 2208, screenHeight = 1840,
        orientation = com.android.resources.ScreenOrientation.LANDSCAPE))
    @Test fun deviceTabletLandscape() = device(DeviceConfig.PIXEL_C)
    @Test fun deviceSetupNeeded() = device(DeviceConfig.PIXEL_6, waiting)

    /** 런처 아이콘(적응형 아이콘 원본 108dp)을 크게 그려 본다. */
    @Test fun launcherIcon() {
        val size = (192 * paparazzi.context.resources.displayMetrics.density).toInt()
        // 적응형 아이콘은 렌더러가 못 그려서 배경·전경 레이어를 직접 겹치고 둥글게 자른다.
        val icon = android.widget.ImageView(paparazzi.context).apply {
            setBackgroundResource(R.drawable.ic_launcher_background)
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = android.view.ViewGroup.LayoutParams(size, size)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.3f)
                }
            }
            clipToOutline = true
        }
        val frame = android.widget.FrameLayout(paparazzi.context).apply {
            setBackgroundColor(0xFFEDEFF5.toInt())
            setPadding(size / 4, size / 4, size / 4, size / 4)
            addView(icon)
        }
        paparazzi.snapshot(frame)
    }
}
