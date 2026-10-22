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
}
