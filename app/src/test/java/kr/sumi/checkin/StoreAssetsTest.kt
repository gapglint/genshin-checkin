package kr.sumi.checkin

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
 * 아이콘 512×512, 대표 이미지 1024×500, 스크린샷 1080×1920(플레이는 9:16만 받는다).
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
                text = "리딤코드 자동 입력 · 매일 자동 출석 · 보충 출석"
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

    /** 방송 직후처럼 새 코드 여러 개를 넣은 상태. 리딤 장면 스크린샷용. */
    private val redeemed = signed.copy(
        redeemSummary = "10월 18일 21:12 · 새 코드 3개 입력: 5T8B9KQW2RJM, GA7TQLBCNS9V, VAPTVN8B2R5H",
    )

    /**
     * 스토어 스크린샷 1080×1920: 위에 큰 문구, 아래에 실제 앱 화면(activity_main)을 줄여 얹는다.
     * scrollTo를 주면 그 뷰가 화면 위쪽에 오도록 내린다.
     * tablet이면 1440×2560 틀에 폭 720dp(320dpi) 태블릿 화면으로 그린다(7인치·10인치 태블릿 칸에 같이 쓴다).
     */
    private fun screenshot(
        state: MainUiState, night: Boolean, title: String, sub: String,
        scrollTo: Int? = null, aboveDp: Int = 44, tablet: Boolean = false,
    ) {
        // 휴대폰: 앱 화면은 1080×2160(420dpi)으로 그리고, 스크린샷 틀은 1080×1920이다.
        val w = if (tablet) 1440 else 1080
        val h = if (tablet) 2560 else 1920
        val appH = w * 2
        val k = w / 1080f
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(
            screenWidth = w, screenHeight = h, nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT,
            density = if (tablet) Density.XHIGH else DeviceConfig.PIXEL_6.density,
            xdpi = if (tablet) 320 else DeviceConfig.PIXEL_6.xdpi, ydpi = if (tablet) 320 else DeviceConfig.PIXEL_6.ydpi,
        ))
        // 문구 크기를 틀 폭에 맞춘다(휴대폰 420dpi 기준).
        val textScale = k * 2.625f / (if (tablet) 2f else 2.625f)
        val context = paparazzi.context
        val app = paparazzi.inflate<android.widget.ScrollView>(R.layout.activity_main)
        app.isVerticalScrollBarEnabled = false
        MainScreen(app).render(state)
        if (scrollTo != null) {
            app.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(appH, View.MeasureSpec.EXACTLY))
            app.layout(0, 0, w, appH)
            val target = app.findViewById<View>(scrollTo)
            app.scrollTo(0, target.top - (aboveDp * context.resources.displayMetrics.density).toInt())
        }

        // 앱 화면은 실제 크기(틀 폭)로 배치하고 0.84배로 줄여 둥근 모서리 안에 넣는다.
        val scale = 0.84f
        val screen = FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 64f * k
                setColor(context.getColor(R.color.bg))
                setStroke((4 * k).toInt(), context.getColor(R.color.hero_line))
            }
            clipToOutline = true
            addView(app, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            pivotX = w / 2f
            pivotY = 0f
            scaleX = scale
            scaleY = scale
            translationY = 540f * k
        }
        val caption = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((72 * k).toInt(), (150 * k).toInt(), (72 * k).toInt(), 0)
            addView(TextView(context).apply {
                text = title
                textSize = 30f * textScale
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getColor(R.color.hero_ink))
            })
            addView(TextView(context).apply {
                text = sub
                textSize = 16f * textScale
                gravity = Gravity.CENTER
                setPadding(0, (36 * k).toInt(), 0, 0)
                setTextColor(context.getColor(R.color.gold))
            })
        }
        paparazzi.snapshot(FrameLayout(context).apply {
            setBackgroundResource(R.drawable.ic_launcher_background)
            addView(screen, FrameLayout.LayoutParams(w, appH))
            addView(caption, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    @Test fun screenshot1Redeem() = screenshot(
        redeemed, night = false, scrollTo = R.id.redeemGroup,
        title = "방송 리딤코드,\n알아서 입력돼요", sub = "새 코드가 나오면 고른 캐릭터로 자동 입력",
    )

    @Test fun screenshot2CheckIn() = screenshot(
        signed, night = false,
        title = "새벽 1시, 서버 시각에\n자동 출석", sub = "폰 시계가 아니라 HoYoLAB 서버 시각 기준",
    )

    @Test fun screenshot3MakeUp() = screenshot(
        signed, night = false, scrollTo = R.id.makeUpCard, aboveDp = 150,
        title = "놓친 날은\n보충 출석까지", sub = "알림을 누르면 바로 보충해요",
    )

    @Test fun screenshot4Dark() = screenshot(
        signed, night = true,
        title = "여러 계정도\n차례로 자동 출석", sub = "광고 없이, 로그인 정보는 폰 안에만",
    )

    // 태블릿은 화면 하나에 내용이 거의 다 들어가서 맨 위부터 보여 준다.
    @Test fun tablet1Redeem() = screenshot(
        redeemed, night = false, tablet = true,
        title = "리딤코드도 출석도\n알아서 챙겨요", sub = "새벽 1시 서버 시각 기준 · 보충 출석까지",
    )

    @Test fun tablet2Dark() = screenshot(
        signed, night = true, tablet = true,
        title = "여러 계정도\n차례로 자동 출석", sub = "광고 없이, 로그인 정보는 폰 안에만",
    )
}
