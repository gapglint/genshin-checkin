package kr.sumi.checkin

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 이번 달 출석 보상 한 칸. 호요랩은 날짜가 아니라 출석 횟수 순서대로 이 보상을 준다. */
data class CalendarSlot(val name: String, val count: Int)

/**
 * 메인 화면의 출석 보상 달력. 호요랩 출석 페이지와 같은 정보(이번 달 보상 목록, 누적 출석, 놓친 날)를
 * 하루 한 번만 받아 계정별로 저장해 둔다.
 */
object CheckInCalendar {
    fun parseAwards(home: JSONObject): List<CalendarSlot> {
        val awards = home.optJSONObject("data")?.optJSONArray("awards") ?: return emptyList()
        return (0 until awards.length()).map { awards.getJSONObject(it) }.map { CalendarSlot(it.optString("name"), it.optInt("cnt")) }
    }

    /** 오늘 이미 받아 왔으면 다시 받지 않는다. */
    fun isFresh(store: Store) = store.calendarDay == ServerClock.today().toString() && store.calendarAwards.isNotEmpty()

    /** 보상 목록과 누적 출석·놓친 날을 받아 저장한다. 네트워크 오류는 IOException. */
    @Throws(IOException::class)
    fun refresh(store: Store) {
        val client = CheckInClient(store.cookie ?: return)
        val info = client.info().optJSONObject("data") ?: return
        Thread.sleep(CheckInClient.REQUEST_GAP_MS)
        val awards = parseAwards(client.home())
        if (awards.isEmpty()) return
        store.calendarAwards = awards
        store.calendarMissed = info.optInt("sign_cnt_missed")
        store.signedTotal = info.optInt("total_sign_day", store.signedTotal)
        store.calendarDay = ServerClock.today().toString()
    }

    /** 칸에 들어갈 짧은 개수: 20, 5천, 1만, 1.5만. */
    fun compact(count: Int): String = when {
        count >= 10_000 -> (count / 1000 / 10.0).let { if (it % 1.0 == 0.0) "${it.toInt()}만" else "${it}만" }
        count >= 1_000 -> "${count / 1000}천"
        else -> "$count"
    }

    internal fun toJson(slots: List<CalendarSlot>) =
        JSONArray(slots.map { JSONObject().put("name", it.name).put("cnt", it.count) }).toString()

    internal fun fromJson(raw: String): List<CalendarSlot> = JSONArray(raw).let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }.map { CalendarSlot(it.optString("name"), it.optInt("cnt")) }
    }
}
