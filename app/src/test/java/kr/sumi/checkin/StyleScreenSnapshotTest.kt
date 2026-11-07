package kr.sumi.checkin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/** 홈 꾸미기: 위 미리보기와 탭(캐릭터·배경·음성). */
class StyleScreenSnapshotTest {
    init { TestText.install() }

    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(locale = "ko"), theme = "Theme.HoyoCheckin")

    /** 사진 대신: 하늘색 → 초록 세로 그라데이션과 얼굴 자리(빨간 동그라미). */
    private fun fakeArt(): Bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawRect(0f, 0f, 1080f, 1920f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 1920f, intArrayOf(0xFF9FD3E8.toInt(), 0xFFDDEBC9.toInt(), 0xFF5E9446.toInt()), null, Shader.TileMode.CLAMP)
        })
        Canvas(it).drawOval(400f, 380f, 680f, 640f, Paint().apply { color = 0xFFE05050.toInt() })
    }

    private val owned = listOf(
        OwnedCharacter(10000035, "치치", "cryo", 5, 90, 10, 2, ""),
        OwnedCharacter(10000073, "나히다", "dendro", 5, 90, 10, 0, ""),
        OwnedCharacter(10000089, "푸리나", "hydro", 5, 80, 8, 1, ""),
        OwnedCharacter(10000037, "감우", "cryo", 5, 90, 10, 0, ""),
        OwnedCharacter(10000030, "종려", "geo", 5, 90, 10, 0, ""),
        OwnedCharacter(10000023, "향릉", "pyro", 4, 80, 10, 6, ""),
    )

    private fun screen(tab: Int, voice: String = "off", style: String = Lobby.STYLE_LOBBY) {
        val ctx = MemoryPrefsContext(paparazzi.context)
        val settings = AppSettings(ctx)
        settings.saveLogin("100000000", "test") // 가짜 계정(메모리에만)
        settings.favorite = Favorite("치치", "cryo", "", 10000035)
        settings.homeStyle = style
        settings.voiceLanguage = voice
        settings.lobbyBackground = Lobby.BG_OFFICIAL
        val activity = LobbyStyleActivity()
        android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(activity, ctx)
        // 화면 없이 만든 Activity라 테마를 직접 넣는다(Activity가 테마를 만들 때 시스템 서비스를 찾는다).
        android.view.ContextThemeWrapper::class.java.getDeclaredField("mTheme").apply { isAccessible = true }.set(activity, paparazzi.context.theme)
        val root = activity.buildContent()
        activity.showForTest(settings, owned, tab, fakeArt())
        paparazzi.snapshot(root)
    }

    @Test fun characterTab() = screen(LobbyStyleActivity.TAB_CHARACTER)
    @Test fun backgroundTab() = screen(LobbyStyleActivity.TAB_BG)
    @Test fun voiceTab() = screen(LobbyStyleActivity.TAB_VOICE, voice = "ja")
    @Test fun cardStyle() = screen(LobbyStyleActivity.TAB_CHARACTER, style = Lobby.STYLE_CARD)
}
