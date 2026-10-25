package kr.sumi.checkin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.ViewGroup
import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import org.junit.Rule
import org.junit.Test

/** 최애 그림 알림의 본문(접힌·펼친 모습). 공식 그림 대신 얼굴 자리에 동그라미를 그린 가짜 세로 그림을 쓴다. */
class NotificationArtSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(nightMode = com.android.resources.NightMode.NIGHT), theme = "Theme.HoyoCheckin",
        renderingMode = SessionParams.RenderingMode.SHRINK, useDeviceResolution = true,
    )

    /** 1080x1920 세로 그림, 얼굴은 가로 41~63%, 세로 31~42%(치치 그림의 실제 위치와 비슷하게). */
    private fun art(small: Boolean): Bitmap {
        val src = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        Canvas(src).apply {
            drawColor(0xFFB8CCE0.toInt())
            drawCircle(560f, 900f, 300f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6A5A9A.toInt() })
            drawCircle(560f, 700f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3E1D6.toInt() })
        }
        val art = Lobby.notifArts(src, floatArrayOf(0.411f, 0.3084f, 0.6281f, 0.4232f))
        return if (small) art.small else art.big
    }

    private fun place(layout: Int, quote: String?, heightDp: Int) {
        val c = paparazzi.context
        val d = c.resources.displayMetrics.density
        val frame = FrameLayout(c).apply {
            setBackgroundColor(0xFF2A2F3C.toInt())
            val pad = (16 * d).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val views = Notifier.artViews(c, layout, "원신 출첵 완료", "출첵 완료! (18일째) · 보상: 원석 ×20", quote, art(layout == R.layout.notification_art))
        frame.addView(views.apply(c, frame), FrameLayout.LayoutParams((330 * d).toInt(), if (heightDp > 0) (heightDp * d).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT))
        paparazzi.snapshot(frame)
    }

    @Test fun collapsed() = place(R.layout.notification_art, null, 48)

    @Test fun expanded() = place(R.layout.notification_art_big, "치치 「아침인가요? 오늘 할 일은… 메모 좀 봐야겠어요」", 0)

    /** 알림에 붙이는 가장 긴 대사(70자 가까이)도 펼치면 다 보인다. */
    @Test fun expandedLongQuote() = place(R.layout.notification_art_big,
        "테스트 「알림에 붙이는 대사는 일흔 자까지라서, 펼친 알림에서는 여섯 줄 안에 끝까지 다 보여야 해요. 여기까지 보이면 돼요」", 0)
}
