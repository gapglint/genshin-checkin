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
        RerunList(context, watched = setOf("Hu Tao", "Furina"), onToggle = {}).fill(list, data, LocalDate.of(2026, 10, 22))
        paparazzi.snapshot(ScrollView(context).apply { setBackgroundResource(R.color.bg); addView(list) })
    }

    /** '4성도 보기'를 켠 모습: 픽업 판(5성 뒤에 4성), 그리고 맨 아래 4성 판·옵션 줄(한 화면에 담으려고 가운데 판들은 뺀다). */
    @Test fun rerunListWithFours() {
        val data = Reruns.parse(JSONObject(javaClass.getResource("/reruns-fours.json")!!.readText()))
        val context = paparazzi.context
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.bg)
            val d = context.resources.displayMetrics.density
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), (24 * d).toInt())
        }
        RerunList(context, watched = setOf("Mika"), onToggle = {}, showFours = true, onFours = {}).fill(list, data, LocalDate.of(2026, 10, 25))
        // 픽업 판(0)과 끝의 4성 판·옵션 줄·출처(마지막 셋)만 남긴다.
        while (list.childCount > 4) list.removeViewAt(1)
        paparazzi.snapshot(ScrollView(context).apply { setBackgroundResource(R.color.bg); addView(list) })
    }
}
