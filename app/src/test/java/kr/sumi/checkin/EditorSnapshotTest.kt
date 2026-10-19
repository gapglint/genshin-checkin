package kr.sumi.checkin

import android.view.View
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

/** 메인 화면 편집(카드 줄과 서랍). */
class EditorSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, theme = "Theme.HoyoCheckin")

    private fun snap(night: Boolean) {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT))
        val view = paparazzi.inflate<View>(R.layout.activity_main)
        val screen = MainScreen(view)
        screen.render(MainUiState(loggedIn = true, month = 10))
        // 스냅숏 렌더러에서는 설정이 저장되지 않아 기본값(모든 카드 보이기)으로 그린다.
        val settings = AppSettings(paparazzi.context)
        MainEditor(screen.editPanel, settings, {}, {}).render()
        screen.editPanel.visibility = View.VISIBLE
        paparazzi.snapshot(view)
    }

    @Test fun light() = snap(night = false)
    @Test fun dark() = snap(night = true)
}
