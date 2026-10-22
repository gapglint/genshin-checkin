package kr.sumi.checkin

import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/** 복각표 원소 아이콘 7개를 크게 그려 모양을 확인한다. */
class ElementIconSheetTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 700))

    @Test fun elementIcons() {
        val c = paparazzi.context
        val d = c.resources.displayMetrics.density
        val sheet = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.color.bg); setPadding(0, (16 * d).toInt(), 0, 0) }
        for (line in listOf("Pyro", "Hydro", "Electro", "Cryo", "Anemo", "Geo", "Dendro").chunked(4)) sheet.addView(LinearLayout(c).apply {
            setPadding(0, 0, 0, (12 * d).toInt())
            for (e in line) addView(ImageView(c).apply {
            setImageResource(RerunActivity.elementIcon(e)!!)
            setPadding((9 * d).toInt(), (9 * d).toInt(), (9 * d).toInt(), (9 * d).toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c.getColor(RerunActivity.elementColor(e))) }
            }, LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()).apply { marginStart = (12 * d).toInt() })
        })
        paparazzi.snapshot(sheet)
    }
}
