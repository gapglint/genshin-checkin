package kr.sumi.checkin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RedeemTest {
    @Test fun parsesRoles() {
        val roles = Redeem.parseRoles(JSONObject("""{"retcode":0,"data":{"list":[
            {"game_biz":"hk4e_global","region":"os_asia","game_uid":"812345678","nickname":"여행자","level":60,"region_name":"Asia Server"},
            {"game_biz":"hk4e_global","region":"os_usa","game_uid":"612345678","nickname":"Traveler","level":35,"region_name":"America Server"}]}}"""))
        assertEquals(2, roles.size)
        assertEquals("아시아 서버 · 여행자 (Lv.60) · UID 812345678", roles[0].label())
        assertEquals(roles[0], GameRole.representative(roles, chosen = null))
        assertEquals(roles[1], GameRole.representative(roles, chosen = roles[1]))
        assertEquals("os_usa", roles[1].region)
    }

    @Test fun findsCodesInPostLinks() {
        val post = JSONObject().put("data", JSONObject().put("post", JSONObject().put("post", JSONObject()
            .put("content", """<p><a href="https://genshin.hoyoverse.com/ko/gift?code=OKTCV81F9H8D"><strong>OKTCV81F9H8D</strong></a></p>
                <a href="https://genshin.hoyoverse.com/gift?code=genshingift">x</a>""")
            .put("structured_content", """[{"insert":"OKTCV81F9H8D","attributes":{"link":"https://genshin.hoyoverse.com/ko/gift?code=OKTCV81F9H8D"}}]"""))))
        assertEquals(listOf("OKTCV81F9H8D", "GENSHINGIFT"), Redeem.parseCodesInPost(post))
    }

    @Test fun splitsLinksGluedTogether() {
        val post = JSONObject().put("data", JSONObject().put("post", JSONObject().put("post", JSONObject()
            .put("content", "https://genshin.hoyoverse.com/ko/gift?code=PFY1S40I88T9https://genshin.hoyoverse.com/ko/gift?code=MageNicolesPuzzlehttps://genshin.hoyoverse.com/ko/gift?code=OHOHONICOLE\\n"))))
        assertEquals(listOf("PFY1S40I88T9", "MAGENICOLESPUZZLE", "OHOHONICOLE"), Redeem.parseCodesInPost(post))
    }

    @Test fun keepsOnlyRecentPostsNewestFirst() {
        val search = JSONObject("""{"data":{"list":[
            {"post":{"post_id":"1","created_at":1000}},
            {"post":{"post_id":"2","created_at":3000}},
            {"post":{"post_id":"3","created_at":2000}}]}}""")
        assertEquals(listOf("2", "3"), Redeem.recentPostIds(search, since = 1500))
    }

    @Test fun officialCodesOnlyWhenActive() {
        val material = JSONObject("""{"data":{"modules":[{"module_type":1},{"exchange_group":{"bonuses":[
            {"exchange_code":"live2026","code_status":"ON"},{"exchange_code":"OLDCODE","code_status":"OFF"}]}}]}}""")
        assertEquals(listOf("LIVE2026"), Redeem.parseOfficialCodes(material))
    }

    /** 2026-10-18에 받아 둔 위키 원문으로 확인한다. */
    private fun wiki() = JSONObject(javaClass.getResource("/wiki_promotional_code.json")!!.readText())

    @Test fun wikiActiveCodesForAsia() {
        val groups = Redeem.parseWikiCodes(wiki(), "os_asia", java.time.LocalDate.of(2026, 10, 18))
        val all = groups.flatten()
        assertEquals("6LS3F3LS5K87", groups.first().single())
        // 글로벌 코드는 들어가고, 중국 서버 전용(YuanShen)은 빠진다
        assert("BALLETCOLLAB" in all && "VESNAONPATROL" in all)
        assert("YUANSHEN" !in all)
        // 하나만 쓸 수 있는 묶음 코드는 한 묶음으로
        assertEquals(listOf("GS71XAVWDS", "GS71XDYGEO", "GS71XYNSYJ", "GS71XOXYLG"), groups.first { it.size > 1 })
        assertEquals(12, groups.size)
    }

    @Test fun wikiSkipsExpired() {
        // VesnaOnPatrol은 2026-10-22 만료
        val all = Redeem.parseWikiCodes(wiki(), "os_asia", java.time.LocalDate.of(2026, 10, 23)).flatten()
        assert("VESNAONPATROL" !in all)
    }

    @Test fun wikiRewardsInKorean() {
        val raw = Redeem.wikiRewards(wiki())["6LS3F3LS5K87"]
        assertEquals("Primogem*20;Geode of Replication*160", raw)
        assertEquals(listOf("원석" to 20, "Geode of Replication" to 160), Redeem.rewardItems(raw!!))
        assertEquals(listOf("모라" to 10000, "영웅의 경험" to 2), Redeem.rewardItems("[[Mora]]*10,000;Hero's Wit*2"))
    }

    @Test fun mapsRetcodes() {
        assertEquals(RedeemStatus.REDEEMED, Redeem.statusOf(0))
        assertEquals(RedeemStatus.EXPIRED, Redeem.statusOf(-2001))
        assertEquals(RedeemStatus.USED, Redeem.statusOf(-2017))
        assertEquals(RedeemStatus.NOT_LOGGED_IN, Redeem.statusOf(-1071))
        assertEquals(RedeemStatus.TOO_FAST, Redeem.statusOf(-2016))
    }
}

class RewardHistoryTest {
    @Test fun parsesAwardHistory() {
        val json = JSONObject("""{"retcode":0,"data":{"total":2,"list":[
            {"id":"11","name":"원석","cnt":20,"created_at":"2026-10-18 01:02:03"},
            {"id":"","name":"모라","cnt":5000,"created_at":"1792249323"}]}}""")
        val entries = RewardHistory.parseAwards(json)
        assertEquals(RewardEntry("11", "원석", 20, "2026-10-18"), entries[0])
        assertEquals("모라", entries[1].name)
        assertEquals("1792249323|모라|5000", entries[1].key)
        assertEquals(10, entries[1].date.length)
        assertEquals("2026년 10월", RewardHistory.monthLabel("2026-10"))
        assertEquals("10월 8일", RewardHistory.dayLabel("2026-10-08"))
    }

    private fun e(key: String) = RewardEntry(key, "원석", 20, "2026-10-18")

    @Test fun unseenAreThoseAboveTheSeenMarker() {
        val newestFirst = listOf(e("5"), e("4"), e("3"), e("2"))
        assertEquals(2, RewardHistory.unseenAwards(newestFirst, seenKey = "3"))
        assertEquals(0, RewardHistory.unseenAwards(newestFirst, seenKey = "5"))
        // 확인 표시가 목록 밖이면(그 뒤로 많이 받음) 전부 새것, 기준이 없으면(처음) 0
        assertEquals(4, RewardHistory.unseenAwards(newestFirst, seenKey = "1"))
        assertEquals(4, RewardHistory.unseenAwards(newestFirst, seenKey = ""))
        assertEquals(0, RewardHistory.unseenAwards(newestFirst, seenKey = null))
    }

    @Test fun unseenCodesAfterSeenTime() {
        assertEquals(1, RewardHistory.unseenCodes(listOf(RedeemedCode("A", 300L, null), RedeemedCode("B", 100L, null)), seenAt = 200L))
    }

    @Test fun totalsPutPrimogemFirstAndFormat() {
        val totals = RewardHistory.totals(listOf("모라" to 5000, "원석" to 20, "모라" to 5000, "영웅의 경험" to 2, "원석" to 40))
        assertEquals(listOf("원석" to 60, "모라" to 10000, "영웅의 경험" to 2), totals)
        assertEquals("원석 ×60 · 모라 ×10,000 외 1종", RewardHistory.formatTotals(totals, max = 2))
        assertEquals(null, RewardHistory.formatTotals(emptyList()))
    }
}

class CheckInCalendarTest {
    @Test fun compactCounts() {
        assertEquals("20", CheckInCalendar.compact(20))
        assertEquals("5천", CheckInCalendar.compact(5000))
        assertEquals("1만", CheckInCalendar.compact(10000))
        assertEquals("1.5만", CheckInCalendar.compact(15000))
    }

    @Test fun parsesAwardsAndRoundTrips() {
        val home = JSONObject("""{"retcode":0,"data":{"month":10,"awards":[{"name":"원석","cnt":20},{"name":"모라","cnt":5000}]}}""")
        val slots = CheckInCalendar.parseAwards(home)
        assertEquals(listOf(CalendarSlot("원석", 20), CalendarSlot("모라", 5000)), slots)
        assertEquals(slots, CheckInCalendar.fromJson(CheckInCalendar.toJson(slots)))
    }
}
