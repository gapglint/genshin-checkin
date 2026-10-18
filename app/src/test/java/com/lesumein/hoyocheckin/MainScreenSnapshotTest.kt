package com.lesumein.hoyocheckin

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

    private val signed = MainUiState(
        loggedIn = true, accounts = listOf(AccountChip("181234567", "여행자", true), AccountChip("192345678", "부계정", false)),
        currentId = "181234567", uid = "181234567", nickname = "여행자", server = "아시아 서버", signedToday = true, totalDays = 17, month = 10,
        reward = "원석 x20", lastChecked = "10월 18일 21:04", nextRun = "내일 01:03", makeUpMissed = 2,
        redeemRole = "아시아 서버 · 여행자 (Lv.60) · UID 812345678", redeemSummary = "10월 18일 01:04 · 새 코드 1개 입력: OKTCV81F9H8D", historyUnseen = 3,
    )
    private val waiting = MainUiState(
        loggedIn = true, uid = "812345678", totalDays = 16, month = 10, lastChecked = "10월 18일 03:00",
        nextRun = "오늘 09:00", note = "요청이 너무 잦다고 해요. 잠시 뒤 자동으로 다시 시도해요.",
        needsExactAlarm = true, needsBatteryExemption = true,
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
