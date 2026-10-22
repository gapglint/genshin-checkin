package kr.sumi.checkin

import android.widget.LinearLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class EventsSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test fun eventList() {
        val events = Events.parse(JSONObject(javaClass.getResource("/events.json")!!.readText()))
        val now = 1792627200_000L // 2026-10-22
        val live = events.filter { it.live && now / 1000 < it.end }.sortedBy { it.end }
        val c = paparazzi.context
        val d = c.resources.displayMetrics.density
        paparazzi.snapshot(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.bg)
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), 0)
            addView(EventsList(c, now) {}.build(live))
        })
    }
}
