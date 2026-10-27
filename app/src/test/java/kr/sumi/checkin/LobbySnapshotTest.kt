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
        override fun onAccount() {}
        override fun onBubble() {}
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

    /** 최애 대사(위키) + 앱이 한 일 한 줄, 음성 표시. */
    @Test fun withQuote() = lobby(base.copy(
        quote = "좋은 아침이에요, 여행자님. 아침부터 이렇게 만나다니… 오늘은 모든 일이 잘 풀리겠어요",
        greeting = "오늘 출석이랑 새 코드도 받아 뒀어요.", voice = true,
    ), fakeArt())

    /** 긴 대사(90자 넘게)는 네 줄과 '전체 보기'. */
    @Test fun longQuote() = lobby(base.copy(
        quote = "테스트용 긴 대사예요. 말풍선에 접지 않고 넣는 길이보다 긴 대사는 네 줄만 보이고, 아래 '전체 보기'를 누르면 말풍선이 펼쳐져서 끝까지 다 읽을 수 있어요. 이 문장은 네 줄을 넘기려고 일부러 길게 썼어요. 한 줄 더, 또 한 줄 더 이어지다가 여기까지 보이면 펼친 거예요.",
        greeting = "레진이 가득 찼어요! 출석은 받아 뒀어요.", voice = true,
    ), fakeArt())

    @Test fun patternNoArt() = lobby(base.copy(name = "치치", element = "cryo", meta = "얼음 · Lv.90 · 호감도 10", credit = false), null)

    @Test fun problem() = lobby(base.copy(
        greeting = "출석을 못 했어요.\n아래를 확인해 주세요.",
        dockTone = DockTone.WARN, dockTitle = "로그인이 풀렸어요", dockSub = "다시 로그인하면 바로 출석해요",
        dockBig = null, dockBigLabel = null, dockButton = "다시 로그인",
    ), fakeArt())

    /** 계정이 여럿이면 위에 계정 이름, 레진이 가득이면 금색 테두리. 이름이 긴 캐릭터는 글자를 줄인다. */
    @Test fun accountAndFullResin() = lobby(base.copy(
        name = "카에데하라 카즈하", element = "anemo", meta = "바람 · Lv.90 · 호감도 10 · 운명의 자리 0",
        resin = 200, account = "부계정", dockBig = "가득", dockBigLabel = "레진",
    ), fakeArt())

    @Test fun resinMenu() {
        val t = LobbyTheme(paparazzi.context.getColor(R.color.element_dendro))
        val menu = LobbyMenu(paparazzi.context, "레진", t, null, "나히다", "dendro") {}
        menu.card { with(menu) { head("지금 레진"); meter(142, 200, "7시간 44분 뒤 가득\n오후 3:20", R.drawable.ic_item_resin) } }
        menu.card { with(menu) { head("오늘 할 일"); iconStats(Triple(R.drawable.ic_note_expedition, "4/5", "탐사 파견"), Triple(R.drawable.ic_note_teapot, "1,800", "주전자 동전"), Triple(R.drawable.ic_note_commission, "4/4", "일일 의뢰")) } }
        menu.card { with(menu) { head("최근 받은 코드"); row(R.drawable.ic_item_primogem, "PJ2KEBRAYSRM", "원석 ×60 · 10/18"); row(R.drawable.ic_item_mora, "OKTCV81F9H8D", "모라 ×10,000 · 10/14") } }
        paparazzi.snapshot(FrameLayout(paparazzi.context).apply { addView(menu) })
    }
}
