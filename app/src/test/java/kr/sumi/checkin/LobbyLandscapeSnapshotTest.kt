package kr.sumi.checkin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/** 가로 화면: 세로 그림을 높이에 맞춰 가운데, 패널은 왼쪽, 말풍선은 그림 오른쪽, 메뉴 5개가 다 들어간다. */
class LobbyLandscapeSnapshotTest {
    init { TestText.install() }
    private val base = DeviceConfig.PIXEL_5
    @get:Rule val paparazzi = Paparazzi(deviceConfig = base.copy(screenWidth = base.screenHeight, screenHeight = base.screenWidth, orientation = ScreenOrientation.LANDSCAPE, locale = "ko"), theme = "Theme.HoyoCheckin")
    private val actions = object : LobbyView.Actions {
        override fun onResin() {}; override fun onCheckIn() {}; override fun onDockButton() {}
        override fun onMenu(key: String) {}; override fun onSettings() {}; override fun onAccount() {}; override fun onBubble() {}
    }
    // 세로 그림 흉내: 위가 하늘색, 얼굴(빨간 원)이 25% 높이, 몸은 아래로.
    private fun art(): Bitmap {
        val b = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.rgb(120, 170, 220))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(60, 60, 140); c.drawRect(220f, 420f, 500f, 1280f, p)
        p.color = Color.rgb(230, 80, 80); c.drawCircle(360f, 320f, 110f, p)
        return b
    }
    @Test fun landscape() = paparazzi.snapshot(LobbyView(paparazzi.context).apply {
        measure(android.view.View.MeasureSpec.makeMeasureSpec(2340, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY))
        layout(0, 0, 2340, 1080)
        bind(LobbyUi(name = "치치", element = "cryo", meta = "얼음 · Lv.90", greeting = "늦은 밤이에요, 여행자 님.",
            resin = 96, totalDays = 25, dockTitle = "오늘 출석 완료", dockSub = "원석 ×20",
            dockBig = "5:40", dockBigLabel = "오전 레진 가득", dockButton = "원신 켜기", face = android.graphics.RectF(0.35f, 0.17f, 0.65f, 0.33f)), art(), actions)
    })
}
