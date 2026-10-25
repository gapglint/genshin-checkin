package kr.sumi.checkin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RerunsTest {
    private val data = Reruns.parse(JSONObject(javaClass.getResource("/reruns.json")!!.readText()))
    private val day = LocalDate.of(2026, 10, 22) // 7.1 기간

    private fun since(name: String) = data.since(data.characters.first { it.name == name }, day)

    @Test fun countsVersionsSinceLastBanner() {
        assertEquals("7.1", data.current(day)!!.v)
        // 커뮤니티 복각표(7.1)와 같은 값
        assertEquals(17, since("Hu Tao"))
        assertEquals(13, since("Xianyun"))
        assertEquals(0, since("Vesna"))
    }

    @Test fun lunaVersionsHaveNames() {
        assertEquals("Luna I", data.label("6.0"))
        assertEquals("7.1", data.label("7.1"))
    }

    @Test fun rankedOldestFirstAndStandardSeparate() {
        val ranked = data.ranked(day, standard = false)
        assertEquals("호두", ranked.first().first.ko)
        assertTrue(ranked.zipWithNext().all { (a, b) -> a.second >= b.second })
        assertTrue(ranked.none { it.first.standard })
        assertTrue(data.ranked(day, standard = true).any { it.first.name == "Keqing" })
    }

    @Test fun beforeANewVersionStartsItIsNotCurrent() {
        // 7.1이 시작하기 전날은 7.0이 지금 버전
        assertEquals("7.0", data.current(LocalDate.of(2026, 9, 22))!!.v)
    }

    @Test fun liveAndUpcomingBanners() {
        assertEquals(setOf("Escoffier", "Skirk"), data.live(day).flatMap { it.chars }.toSet())
        assertTrue(data.upcoming(day).isEmpty())
    }

    @Test fun watchedCharacterAlertsOncePerBanner() {
        val due = Reruns.dueAlerts(data, setOf("Skirk", "Hu Tao"), emptySet(), day)
        assertEquals(listOf("Skirk"), due.map { it.first.name })
        val key = "Skirk|${due.single().second.start}"
        assertTrue(Reruns.dueAlerts(data, setOf("Skirk"), setOf(key), day).isEmpty())
        // 끝난 픽업은 알리지 않는다
        assertTrue(Reruns.dueAlerts(data, setOf("Vesna"), emptySet(), day).isEmpty())
    }

    @Test fun parsesFourStarsSeparately() {
        val data = Reruns.parse(JSONObject(javaClass.getResource("/reruns-fours.json")!!.readText()))
        assertTrue(data.fours.isNotEmpty() && data.fours.all { it.rarity == 4 })
        assertTrue(data.characters.none { it.rarity == 4 })
        assertEquals("Mika", data.byKo("미카")?.name)
        // 기다리는 4성도 픽업 알림 대상이다. 같은 날 5성 픽업 둘에 함께 나와도 한 번만.
        val banner = data.banners.last { "Mika" in it.four }
        val due = Reruns.dueAlerts(data, setOf("Mika"), emptySet(), banner.start)
        assertEquals(listOf("Mika"), due.map { it.first.name })
    }

    @Test fun phasesWithinAVersion() {
        val data = Reruns.parse(JSONObject(javaClass.getResource("/reruns-fours.json")!!.readText()))
        fun phase(name: String, day: LocalDate) = data.phase(data.character(name)!!, day)
        val today = LocalDate.of(2026, 10, 25) // 7.1 후반 진행 중
        assertEquals(RerunPhase.NOW, phase("Skirk", today))
        assertEquals(RerunPhase.FIRST_HALF, phase("Vesna", today)) // 7.1 전반에 나왔다 끝남
        assertEquals(RerunPhase.LATER, phase("Furina", today)) // 7.2로 발표(위키에 종료일 없음)
        assertEquals(RerunPhase.PAST, phase("Odette", today))
        // 7.1 전반 중에는 후반 캐릭터가 '후반'
        assertEquals(RerunPhase.SECOND_HALF, phase("Skirk", LocalDate.of(2026, 9, 30)))
        assertEquals(RerunPhase.NOW, phase("Vesna", LocalDate.of(2026, 9, 30)))
    }
}
