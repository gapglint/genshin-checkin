package kr.sumi.checkin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTest {
    private val at = 1_700_000_000_000L
    private fun note() = Notes.parse(JSONObject(javaClass.getResource("/daily_note.json")!!.readText()), at)!!

    @Test fun parsesDailyNote() {
        val n = note()
        assertEquals(142, n.resin)
        assertEquals(200, n.maxResin)
        assertEquals(27_840L, n.resinFullSec)
        assertEquals(2, n.expeditionsDone)
        assertEquals(5, n.expeditionsTotal)
        // 가장 늦게 끝나는 파견 기준
        assertEquals(7_200L, n.expeditionsLeftSec)
        assertEquals(1_840, n.homeCoin)
        assertEquals(3, n.commissionsDone)
        assertEquals(n, DailyNote.fromJson(n.toJson()))
    }

    @Test fun signsLikeHoyolabWeb() {
        assertEquals("1700000000,abcdef,52ac4768378434146675f980be7d092a", Notes.ds(1_700_000_000L, "abcdef"))
        assertTrue(Regex("""\d+,[A-Za-z]{6},[0-9a-f]{32}""").matches(Notes.ds()))
    }

    @Test fun resinGrowsEightMinutesEach() {
        val n = note()
        assertEquals(142, n.resinAt(at))
        // 가득 차기 8분 전이면 199개, 가득 찬 뒤로는 200개
        assertEquals(199, n.resinAt(n.resinFullAt - Notes.RESIN_MS))
        assertEquals(200, n.resinAt(n.resinFullAt + 3_600_000))
        // 180개는 가득 차기 20×8분 전
        assertEquals(n.resinFullAt - 20 * Notes.RESIN_MS, n.resinReachAt(180))
        assertEquals(n.resinFullAt, n.resinReachAt(200))
        assertEquals(at, n.resinReachAt(120))
    }

    @Test fun thresholdZeroMeansFull() {
        assertEquals(200, Notes.target(note(), 0))
        assertEquals(200, Notes.target(note(), 250))
        assertEquals(160, Notes.target(note(), 160))
    }

    @Test fun alertsOnceUntilResinIsSpent() {
        val reached = note().copy(resin = 181)
        val (first, flags) = Notes.evaluate(reached, NoteFlags(), resinAlert = true, threshold = 180, extra = false)
        assertTrue(first.resin)
        assertTrue(flags.resin)
        // 같은 상태로 다시 보면 알리지 않는다
        val (again, _) = Notes.evaluate(reached.copy(resin = 190), flags, resinAlert = true, threshold = 180, extra = false)
        assertFalse(again.any)
        // 레진을 쓰면 풀리고, 다시 차면 또 알린다
        val (_, spent) = Notes.evaluate(reached.copy(resin = 40), flags, resinAlert = true, threshold = 180, extra = false)
        assertFalse(spent.resin)
        assertTrue(Notes.evaluate(reached, spent, resinAlert = true, threshold = 180, extra = false).first.resin)
        // 꺼져 있으면 알리지 않는다
        assertFalse(Notes.evaluate(reached, NoteFlags(), resinAlert = false, threshold = 180, extra = false).first.any)
    }

    @Test fun expeditionsAndCoinOnlyWhenExtraIsOn() {
        val done = note().copy(expeditionsDone = 5, expeditionsLeftSec = 0, homeCoin = 2400)
        assertFalse(Notes.evaluate(done, NoteFlags(), resinAlert = false, threshold = 180, extra = false).first.any)
        val alerts = Notes.evaluate(done, NoteFlags(), resinAlert = false, threshold = 180, extra = true).first
        assertTrue(alerts.expeditions)
        assertTrue(alerts.coin)
    }

    @Test fun nextAlertIsTheEarliestPending() {
        val n = note()
        assertEquals(n.resinReachAt(180), Notes.nextAlertAt(n, NoteFlags(), resinAlert = true, threshold = 180, extra = false))
        // 파견(2시간 뒤)이 레진 180개(약 5시간 뒤)보다 먼저
        assertEquals(n.expeditionsDoneAt, Notes.nextAlertAt(n, NoteFlags(), resinAlert = true, threshold = 180, extra = true))
        assertNull(Notes.nextAlertAt(n, NoteFlags(resin = true), resinAlert = true, threshold = 180, extra = false))
    }

    @Test fun durations() {
        assertEquals("7시간 44분", Notes.duration(27_840_000))
        assertEquals("2시간", Notes.duration(7_200_000))
        assertEquals("1분", Notes.duration(1_000))
        assertEquals("1일 3시간", Notes.duration(27 * 3_600_000L))
    }
}
