package com.lesumein.hoyocheckin

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/**
 * 플레이 스토어 등록 이미지. ./gradlew recordPaparazziDebug 후 app/src/test/snapshots/images의
 * StoreAssetsTest_* 파일을 store/ 폴더로 옮겨 쓴다.
 * 아이콘 512×512, 대표 이미지 1024×500, 스크린샷 1080×2160(세로 2:1 이하).
 */
class StoreAssetsTest {
    @get:Rule
    val paparazzi = Paparazzi(theme = "Theme.HoyoCheckin", useDeviceResolution = true)

    private fun device(width: Int, height: Int, night: Boolean = false) = DeviceConfig.PIXEL_6.copy(
        screenWidth = width, screenHeight = height, density = Density.MEDIUM, xdpi = 160, ydpi = 160,
        nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT,
        orientation = if (width > height) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
    )

    @Test fun icon512() {
        paparazzi.unsafeUpdateConfig(device(512, 512))
        val icon = ImageView(paparazzi.context).apply {
            setBackgroundResource(R.drawable.ic_launcher_background)
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = FrameLayout.LayoutParams(512, 512)
        }
        paparazzi.snapshot(FrameLayout(paparazzi.context).apply { addView(icon) })
    }

    @Test fun featureGraphic() {
        paparazzi.unsafeUpdateConfig(device(1024, 500))
        val context = paparazzi.context
        val star = ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = LinearLayout.LayoutParams(420, 420)
        }
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "출석은 자동으로,\n보상은 빠짐없이"
                textSize = 46f
                setLineSpacing(0f, 1.15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getColor(R.color.hero_ink))
            })
            addView(TextView(context).apply {
                text = "HoYoLAB 매일 출석 · 보충 출석 · 리딤 코드"
                textSize = 22f
                setPadding(0, 28, 0, 0)
                setTextColor(context.getColor(R.color.gold))
            })
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 0, 60, 0)
            addView(star)
            addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        paparazzi.snapshot(FrameLayout(context).apply {
            setBackgroundResource(R.drawable.ic_launcher_background)
            addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
    }

    private val signed = MainUiState(
        loggedIn = true, accounts = listOf(AccountChip("181234567", "여행자", true), AccountChip("192345678", "부계정", false)),
        currentId = "181234567", uid = "181234567", nickname = "여행자", server = "아시아 서버", signedToday = true,
        totalDays = 17, month = 10, reward = "원석 x20", lastChecked = "10월 18일 01:03", nextRun = "내일 01:02",
        makeUpMissed = 2, redeemRole = "아시아 서버 · 여행자 (Lv.60) · UID 812345678",
        redeemSummary = "10월 18일 01:05 · 새 코드 1개 입력: OKTCV81F9H8D", historyUnseen = 3,
    )

    private fun screenshot(state: MainUiState, night: Boolean) {
        // 1080×2160 화면을 420dpi로 그린다(일반적인 휴대폰 밀도).
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(
            screenWidth = 1080, screenHeight = 2160, nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT,
        ))
        val view = paparazzi.inflate<View>(R.layout.activity_main)
        view.isVerticalScrollBarEnabled = false
        MainScreen(view).render(state)
        paparazzi.snapshot(view)
    }

    @Test fun screenshotLight() = screenshot(signed, night = false)
    @Test fun screenshotDark() = screenshot(signed, night = true)
    @Test fun screenshotLoggedOut() = screenshot(MainUiState(loggedIn = false, month = 10), night = false)
}
