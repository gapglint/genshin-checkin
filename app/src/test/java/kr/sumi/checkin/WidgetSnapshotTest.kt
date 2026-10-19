package kr.sumi.checkin

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

/** 홈 화면 레진 위젯(작게·넓게, 밝게·어둡게). 위젯은 RemoteViews라 같은 레이아웃을 직접 채워 그린다. */
class WidgetSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6, theme = "Theme.HoyoCheckin",
        renderingMode = SessionParams.RenderingMode.SHRINK, useDeviceResolution = true,
    )

    private fun snap(widthDp: Int, wide: Boolean, night: Boolean) {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT))
        val widget = paparazzi.inflate<View>(R.layout.widget_resin)
        widget.findViewById<TextView>(R.id.widgetResin).text = "142 / 200"
        widget.findViewById<TextView>(R.id.widgetFull).text = "오후 3:20 가득"
        widget.findViewById<TextView>(R.id.widgetExpeditions).text = "2/5"
        widget.findViewById<TextView>(R.id.widgetCoin).text = "1,840"
        widget.findViewById<TextView>(R.id.widgetCommissions).text = "3/4"
        widget.findViewById<View>(R.id.widgetStats).visibility = if (wide) View.VISIBLE else View.GONE
        val density = paparazzi.context.resources.displayMetrics.density
        // 홈 화면처럼 바탕 위에 위젯 한 칸 높이로 놓는다.
        val frame = FrameLayout(paparazzi.context).apply {
            setBackgroundColor(if (night) 0xFF202432.toInt() else 0xFF9DB0D0.toInt())
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(widget, FrameLayout.LayoutParams((widthDp * density).toInt(), (64 * density).toInt()))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        paparazzi.snapshot(frame)
    }

    @Test fun smallLight() = snap(170, wide = false, night = false)
    @Test fun wideLight() = snap(360, wide = true, night = false)
    @Test fun wideDark() = snap(360, wide = true, night = true)
}
