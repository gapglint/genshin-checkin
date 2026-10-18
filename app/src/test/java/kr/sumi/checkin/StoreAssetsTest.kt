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
                text = "리딤코드 자동 입력 · 새벽 1시 자동 출석 · 보상 합계"
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
     * tablet이면 1440×2560 틀에 폭 576dp(400dpi) 태블릿 화면으로 그린다(7인치·10인치 태블릿 칸에 같이 쓴다).
     * history를 주면 메인 화면 대신 보상 내역 화면을 그린다.
     */
    private fun screenshot(
        state: MainUiState, night: Boolean, title: String, sub: String,
        scrollTo: Int? = null, aboveDp: Int = 44, tablet: Boolean = false, history: HistoryUiState? = null,
        custom: ((android.content.Context) -> View)? = null,
    ) {
        // 휴대폰: 앱 화면은 1080×2160(420dpi)으로 그리고, 스크린샷 틀은 1080×1920이다.
        val w = if (tablet) 1440 else 1080
        val h = if (tablet) 2560 else 1920
        // 태블릿은 보이는 높이만큼만 잡아야 내용이 넘쳐서 장면마다 다른 곳으로 스크롤할 수 있다.
        val appH = if (tablet) ((h - 540f * (w / 1080f)) / 0.84f).toInt() else w * 2
        val k = w / 1080f
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(
            screenWidth = w, screenHeight = h, nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT,
            density = if (tablet) Density.create(400) else DeviceConfig.PIXEL_6.density,
            xdpi = if (tablet) 400 else DeviceConfig.PIXEL_6.xdpi, ydpi = if (tablet) 400 else DeviceConfig.PIXEL_6.ydpi,
        ))
        // 문구 크기를 틀 폭에 맞춘다(휴대폰 420dpi 기준).
        val textScale = k * 2.625f / (if (tablet) 2.5f else 2.625f)
        val context = paparazzi.context
        val app: View = if (custom != null) {
            custom(context)
        } else if (history != null) {
            RewardHistoryScreen(context).apply { render(history) }.root
        } else {
            paparazzi.inflate<android.widget.ScrollView>(R.layout.activity_main).also {
                it.isVerticalScrollBarEnabled = false
                MainScreen(it).render(state)
            }
        }
        if (scrollTo != null && app is android.widget.ScrollView) {
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

    /**
     * 알림 장면: 안드로이드 알림창 모양으로, 밤에 온 알림은 '무음'에 조용히 쌓이고 아침 알림은 그대로 오는 모습.
     * 알림 문구는 Notifier가 실제로 보내는 문구다.
     */
    private fun notificationShade(context: android.content.Context): View {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val shadeBg = 0xFF15171C.toInt(); val card = 0xFF262931.toInt(); val ink = 0xFFE9EAEE.toInt(); val muted = 0xFFA4A8B3.toInt()
        fun section(text: String) = TextView(context).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(muted)
            setPadding(dp(8), dp(18), 0, dp(8))
        }
        fun notification(time: String, title: String, body: String) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(card) }
            addView(android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_notify)
                setColorFilter(0xFF2A1F08.toInt())
                setPadding(dp(7), dp(7), dp(7), dp(7))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(context.getColor(R.color.gold))
                }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "원신 출첵 · $time"; textSize = 12f; setTextColor(muted) })
                addView(TextView(context).apply { text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink); setPadding(0, dp(2), 0, 0) })
                addView(TextView(context).apply { text = body; textSize = 14f; setTextColor(muted); setPadding(0, dp(2), 0, 0); setLineSpacing(0f, 1.1f) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(shadeBg)
            setPadding(dp(16), dp(28), dp(16), 0)
            addView(TextView(context).apply { text = "오전 9:00"; textSize = 34f; setTextColor(ink); setPadding(dp(8), 0, 0, 0) })
            addView(TextView(context).apply { text = "10월 18일 토요일"; textSize = 14f; setTextColor(muted); setPadding(dp(8), 0, 0, dp(4)) })
            addView(section("알림"))
            addView(notification("오전 9:00", "보충 출석을 할 수 있어요", "이번 달에 놓친 날이 2일 있어요. 누르면 바로 보충해요."))
            addView(section("무음 · 밤에 온 알림"))
            addView(notification("오전 1:05", "리딤 코드 3개를 받았어요", "GA7TQLBCNS9V, VAPTVN8B2R5H, 5T8B9KQW2RJM · 게임 우편함에서 받으세요."))
            addView(notification("오전 1:03", "원신 출첵 완료", "출첵 완료! (17일째)\n보상: 원석 x20"),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    /** 보상 내역 장면용 예시: 이번 달 출석 보상과 이 앱으로 받은 리딤 코드. */
    private val history: HistoryUiState get() {
        fun at(day: Int) = java.time.LocalDateTime.of(2026, 10, day, 21, 12).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val names = listOf("원석" to 20, "모라" to 5000, "영웅의 경험" to 3, "원석" to 20, "정제용 양질 광석" to 3, "모라" to 5000, "원석" to 20)
        val entries = (18 downTo 1).map { day ->
            val (name, count) = names[day % names.size]
            RewardEntry("$day", name, count, "2026-10-%02d".format(day))
        }
        return HistoryUiState(
            title = "보상 내역 · 여행자", status = "출석·보충 보상 총 412번 받았어요", month = "2026-10",
            entries = entries, total = 412, loaded = true,
            codes = listOf(
                RedeemedCode("GA7TQLBCNS9V", at(18), "Primogem*60;Mora*10000;Hero's Wit*2"),
                RedeemedCode("VAPTVN8B2R5H", at(18), "Primogem*60;Mora*5000;Mystic Enhancement Ore*5"),
                RedeemedCode("OPERACOLLAB", at(2), "Primogem*30;Mora*20000;Hero's Wit*2"),
            ),
            newAwardKeys = setOf("18"), seenRedeemAt = at(10),
        )
    }

    @Test fun screenshot1Redeem() = screenshot(
        redeemed, night = false, scrollTo = R.id.redeemGroup,
        title = "방송 리딤코드,\n알아서 입력돼요", sub = "새 코드가 나오면 고른 캐릭터로 자동 입력",
    )

    @Test fun screenshot2CheckIn() = screenshot(
        signed, night = false,
        title = "새벽 1시, 서버 시각에\n자동 출석", sub = "폰 시계가 아니라 HoYoLAB 서버 시각 기준",
    )

    @Test fun screenshot3History() = screenshot(
        signed, night = false, history = history,
        title = "이번 달 받은 원석,\n한눈에", sub = "리딤으로 받은 보상까지 합계로",
    )

    @Test fun screenshot4MakeUp() = screenshot(
        signed, night = false, scrollTo = R.id.makeUpCard, aboveDp = 150,
        title = "놓친 날은\n보충 출석까지", sub = "알림을 누르면 바로 보충해요",
    )

    @Test fun screenshot5Alerts() = screenshot(
        signed, night = false, custom = { notificationShade(it) },
        title = "밤에는 조용히,\n아침에 확인", sub = "알림은 골라 받고, 새벽 알림은 소리 없이",
    )

    @Test fun screenshot6Dark() = screenshot(
        signed, night = true,
        title = "여러 계정도\n차례로 자동 출석", sub = "광고 없이, 로그인 정보는 폰 안에만",
    )

    @Test fun tablet1Redeem() = screenshot(
        redeemed, night = false, scrollTo = R.id.redeemGroup, tablet = true,
        title = "방송 리딤코드,\n알아서 입력돼요", sub = "새 코드가 나오면 고른 캐릭터로 자동 입력",
    )

    @Test fun tablet2CheckIn() = screenshot(
        signed, night = false, tablet = true,
        title = "새벽 1시, 서버 시각에\n자동 출석", sub = "폰 시계가 아니라 HoYoLAB 서버 시각 기준",
    )

    @Test fun tablet3History() = screenshot(
        signed, night = false, history = history, tablet = true,
        title = "이번 달 받은 원석,\n한눈에", sub = "리딤으로 받은 보상까지 합계로",
    )

    @Test fun tablet4MakeUp() = screenshot(
        signed, night = false, scrollTo = R.id.makeUpCard, aboveDp = 150, tablet = true,
        title = "놓친 날은\n보충 출석까지", sub = "알림을 누르면 바로 보충해요",
    )

    @Test fun tablet5Alerts() = screenshot(
        signed, night = false, custom = { notificationShade(it) }, tablet = true,
        title = "밤에는 조용히,\n아침에 확인", sub = "알림은 골라 받고, 새벽 알림은 소리 없이",
    )

    @Test fun tablet6Dark() = screenshot(
        signed, night = true, tablet = true,
        title = "여러 계정도\n차례로 자동 출석", sub = "광고 없이, 로그인 정보는 폰 안에만",
    )
}
