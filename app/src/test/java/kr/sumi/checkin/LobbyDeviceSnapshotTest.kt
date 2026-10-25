package kr.sumi.checkin

import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

/** 작은 화면(오른쪽 메뉴를 줄인다)과 다크 모드 메뉴 화면. */
class LobbyDeviceSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_5.copy(nightMode = NightMode.NIGHT), theme = "Theme.HoyoCheckin")

    private val actions = object : LobbyView.Actions {
        override fun onResin() {}
        override fun onCheckIn() {}
        override fun onDockButton() {}
        override fun onMenu(key: String) {}
        override fun onSettings() {}
        override fun onAccount() {}
    }

    @Test fun smallScreen() = paparazzi.snapshot(LobbyView(paparazzi.context).apply {
        bind(LobbyUi(name = "치치", element = "cryo", meta = "얼음 · Lv.90 · 호감도 10", greeting = "늦은 밤이에요, 여행자 님.\n오늘 출석은 받아 뒀어요.",
            resin = 96, totalDays = 25, dockTitle = "오늘 출석 완료", dockSub = "원석 ×20 · 다음 자동 출석 내일 01:03",
            dockBig = "5:40", dockBigLabel = "오전 레진 가득", dockButton = "원신 켜기"), null, actions)
    })

    @Test fun nightMenu() {
        val t = LobbyTheme(paparazzi.context.getColor(R.color.element_cryo))
        val menu = LobbyMenu(paparazzi.context, "출석", t, null, "치치", "cryo") {}
        menu.card { with(menu) { head("10월 출석 보상 · 25 / 31"); calendar((1..31).map { CalendarSlot(if (it % 7 == 0) "원석" else if (it % 3 == 0) "영웅의 경험" else "모라", if (it % 7 == 0) 20 else 2000) }, 25, 26, 31) } }
        menu.card { with(menu) { head("오늘"); row(R.drawable.ic_item_primogem, "출석 완료", "원석 ×20"); row(R.drawable.ic_check_badge, "출석 페이지 열기 ›") {} } }
        paparazzi.snapshot(FrameLayout(paparazzi.context).apply { addView(menu) })
    }
}
