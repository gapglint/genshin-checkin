package kr.sumi.checkin

import android.widget.LinearLayout
import android.widget.ScrollView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class RerunSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test fun rerunList() {
        val data = Reruns.parse(JSONObject(javaClass.getResource("/reruns.json")!!.readText()))
        val context = paparazzi.context
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.bg)
            val d = context.resources.displayMetrics.density
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), (24 * d).toInt())
        }
        RerunList(context).fill(list, data, LocalDate.of(2026, 10, 22))
        paparazzi.snapshot(ScrollView(context).apply { setBackgroundResource(R.color.bg); addView(list) })
    }
}
