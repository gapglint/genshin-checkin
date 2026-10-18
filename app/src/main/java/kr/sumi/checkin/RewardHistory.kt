package kr.sumi.checkin

import org.json.JSONObject
import java.io.IOException

/** 보상 한 건. date는 서버 기준 'yyyy-MM-dd'. key는 '어디까지 확인했는지' 표시에 쓰는 고유값. */
data class RewardEntry(val key: String, val name: String, val count: Int, val date: String)

/**
 * 보상 내역과 '확인' 상태.
 * 알림함처럼 '모두 확인'을 누른 뒤에 받은 보상만 새 보상으로 친다.
 * 출석·보충 보상은 최신순 목록에서 확인 당시 맨 위였던 항목보다 앞에 있으면 새것이고,
 * 리딤 코드는 확인 시각 이후에 받았으면 새것이다.
 */
object RewardHistory {
    const val PAGE_SIZE = 20

    /** 출석 보상 기록 응답을 보상 목록으로. created_at은 '날짜 시각' 문자열이거나 초 단위 숫자다. */
    fun parseAwards(json: JSONObject): List<RewardEntry> {
        val array = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }.map {
            val name = it.optString("name")
            val count = it.optInt("cnt")
            val created = it.optString("created_at")
            RewardEntry(it.optString("id").ifEmpty { "$created|$name|$count" }, name, count, dateOf(created))
        }
    }

    /** 최신순 목록에서 새 보상 수. 확인 표시가 목록에 없으면(그 뒤로 많이 받음) 목록 전체가 새것. */
    fun unseenAwards(entries: List<RewardEntry>, seenKey: String?): Int {
        if (seenKey == null) return 0
        val index = entries.indexOfFirst { it.key == seenKey }
        return if (index < 0) entries.size else index
    }

    fun unseenCodes(log: List<Pair<String, Long>>, seenAt: Long) = log.count { it.second > seenAt }

    /**
     * 처음 보는 계정이면 지금까지의 내역을 확인한 것으로 기준을 잡는다(수백 개가 전부 새것으로 뜨지 않게).
     * 내역이 비어 있으면 빈 기준을 남겨, 앞으로 받는 보상은 모두 새것이 된다.
     */
    fun ensureBaseline(store: Store, firstPage: List<RewardEntry>) {
        if (store.historySeenAwardKey == null) store.historySeenAwardKey = firstPage.firstOrNull()?.key ?: ""
    }

    /** 첫 페이지만 받아 새 보상 수를 세어 저장한다(메인 화면용). 네트워크 오류는 IOException. */
    @Throws(IOException::class)
    fun countUnseen(store: Store): Int {
        store.historyCountedAt = ServerClock.now()
        val cookie = store.cookie ?: return 0
        val json = CheckInClient(cookie).awardHistory(1, PAGE_SIZE)
        if (json.optInt("retcode", -1) != 0) return store.historyUnseen
        val entries = parseAwards(json)
        ensureBaseline(store, entries)
        val count = unseenAwards(entries, store.historySeenAwardKey) + unseenCodes(store.redeemLog, store.historySeenRedeemAt)
        store.historyUnseen = count
        return count
    }

    /** '모두 확인': 지금 맨 위 보상과 지금 시각을 확인 표시로 남긴다. */
    fun markAllSeen(store: Store, newest: RewardEntry?) {
        newest?.let { store.historySeenAwardKey = it.key }
        store.historySeenRedeemAt = ServerClock.now()
        store.historyUnseen = 0
    }

    private fun dateOf(raw: String): String = raw.toLongOrNull()
        ?.let { ServerClock.dayOf(if (it < 10_000_000_000L) it * 1000 else it).toString() }
        ?: raw.take(10)

    fun monthLabel(yearMonth: String): String {
        val parts = yearMonth.split("-")
        return if (parts.size == 2) "${parts[0]}년 ${parts[1].toInt()}월" else yearMonth
    }

    fun dayLabel(date: String): String {
        val parts = date.split("-")
        return if (parts.size == 3) "${parts[1].toInt()}월 ${parts[2].toInt()}일" else date
    }
}
