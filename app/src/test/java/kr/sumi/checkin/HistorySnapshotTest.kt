package kr.sumi.checkin

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 보상 내역 화면(RewardHistoryScreen)을 예시 데이터로 그려 본다. */
class HistorySnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, theme = "Theme.HoyoCheckin")

    private fun at(day: Int, hour: Int) =
        LocalDateTime.of(2026, 10, day, hour, 5).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

    private val entries = listOf(
        RewardEntry("18", "원석", 20, "2026-10-18"),
        RewardEntry("17", "모라", 5000, "2026-10-17"),
        RewardEntry("16", "영웅의 경험", 3, "2026-10-16"),
        RewardEntry("15", "원석", 20, "2026-10-15"),
        RewardEntry("14", "정제용 양질 광석", 3, "2026-10-14"),
        RewardEntry("13", "모라", 5000, "2026-10-13"),
    )
    private val state = HistoryUiState(
        title = "보상 내역 · 여행자",
        status = "출석·보충 보상 총 412번 받았어요",
        month = "2026-10",
        entries = entries,
        total = 412,
        loaded = true,
        codes = listOf(
            RedeemedCode("GA7TQLBCNS9V", at(18, 21), "Primogem*60;Mora*10000;Hero's Wit*2"),
            RedeemedCode("5T8B9KQW2RJM", at(18, 21), null),
            RedeemedCode("OPERACOLLAB", at(2, 12), "Primogem*30;Mora*20000;Hero's Wit*2"),
        ),
        newAwardKeys = setOf("18"),
        seenRedeemAt = at(10, 0),
    )

    private fun snap(night: Boolean) {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT))
        val screen = RewardHistoryScreen(paparazzi.context)
        screen.render(state)
        paparazzi.snapshot(screen.root)
    }

    @Test fun historyLight() = snap(night = false)
    @Test fun historyDark() = snap(night = true)
}
