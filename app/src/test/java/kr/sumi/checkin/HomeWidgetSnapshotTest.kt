package kr.sumi.checkin

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** 홈 화면 '오늘'·'지금 픽업' 위젯. 위젯과 같은 RemoteViews를 만들어 그대로 그린다. */
class HomeWidgetSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6, theme = "Theme.HoyoCheckin",
        renderingMode = SessionParams.RenderingMode.SHRINK, useDeviceResolution = true,
    )

    private fun place(views: android.widget.RemoteViews, widthDp: Int, heightDp: Int) {
        val c = paparazzi.context
        val d = c.resources.displayMetrics.density
        val frame = FrameLayout(c).apply {
            setBackgroundColor(0xFF9DB0D0.toInt())
            val pad = (16 * d).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val widget: View = views.apply(c, frame)
        frame.addView(widget, FrameLayout.LayoutParams((widthDp * d).toInt(), (heightDp * d).toInt()))
        paparazzi.snapshot(frame)
    }

    @Test fun today() = place(
        TodayWidget.views(paparazzi.context, TodayWidgetUi("여우별", "완료", true, 17, "142 / 200", "새 코드 3개 입력")), 170, 170,
    )

    @Test fun todayNotYet() = place(
        TodayWidget.views(paparazzi.context, TodayWidgetUi("여우별", "출석 전", false, 16, "200 / 200", "새 코드 없음")), 170, 170,
    )

    @Test fun pickup() {
        val data = Reruns.parse(JSONObject(javaClass.getResource("/reruns.json")!!.readText()))
        place(PickupWidget.views(paparazzi.context, data.card(LocalDate.of(2026, 10, 24))), 360, 170)
    }
}
