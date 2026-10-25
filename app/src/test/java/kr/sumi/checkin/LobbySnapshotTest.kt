package kr.sumi.checkin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/** 로비형 홈과 메뉴 화면. 그림은 게임 그림 대신 만든 그라데이션(실제로는 공식 그림·내 사진이 들어간다). */
class LobbySnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, theme = "Theme.HoyoCheckin")

    private val actions = object : LobbyView.Actions {
        override fun onResin() {}
        override fun onCheckIn() {}
        override fun onDockButton() {}
        override fun onMenu(key: String) {}
        override fun onSettings() {}
    }

    /** 사진 대신: 하늘색 → 초록 세로 그라데이션(1080×1920). */
    private fun fakeArt(): Bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawRect(0f, 0f, 1080f, 1920f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 1920f, intArrayOf(0xFF9FD3E8.toInt(), 0xFFDDEBC9.toInt(), 0xFF5E9446.toInt()), null, Shader.TileMode.CLAMP)
        })
    }

    private val base = LobbyUi(
        name = "나히다", element = "dendro", meta = "풀 · Lv.90 · 호감도 10 · 운명의 자리 2",
        greeting = "좋은 아침이에요, 여행자 님.\n오늘 출석이랑 새 코드 1개 받아 뒀어요.",
        resin = 142, totalDays = 17, dockTitle = "오늘 출석 완료", dockSub = "원석 ×20 · 다음 자동 출석 내일 01:03",
        dockBig = "3:20", dockBigLabel = "레진 가득", dockButton = "원신 켜기", redeemDot = true, credit = true,
    )

    private fun lobby(ui: LobbyUi, art: Bitmap?) = paparazzi.snapshot(LobbyView(paparazzi.context).apply { bind(ui, art, actions) })

    @Test fun withArt() = lobby(base, fakeArt())

    @Test fun patternNoArt() = lobby(base.copy(name = "치치", element = "cryo", meta = "얼음 · Lv.90 · 호감도 10", credit = false), null)

    @Test fun problem() = lobby(base.copy(
        greeting = "출석을 못 했어요.\n아래를 확인해 주세요.",
        dockTone = DockTone.WARN, dockTitle = "로그인이 풀렸어요", dockSub = "다시 로그인하면 바로 출석해요",
        dockBig = null, dockBigLabel = null, dockButton = "다시 로그인",
    ), fakeArt())

    @Test fun resinMenu() {
        val t = LobbyTheme(paparazzi.context.getColor(R.color.element_dendro))
        val menu = LobbyMenu(paparazzi.context, "레진", t, null, "나히다") {}
        menu.card { with(menu) { head("지금 레진"); meter(142, 200, "오후 3:20 가득") } }
        menu.card { with(menu) { head("오늘 할 일"); stats("4/5" to "탐사 파견 완료", "1,800" to "주전자 동전", "4/4" to "일일 의뢰") } }
        menu.card { with(menu) { head("10월 출석 보상 · 17 / 31"); calendar((1..31).map { if (it % 7 == 0) "원석" else "모라" }, 17, 18, 29) } }
        paparazzi.snapshot(FrameLayout(paparazzi.context).apply { addView(menu) })
    }
}
