package com.lesumein.hoyocheckin

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

    @Test fun mapsRetcodes() {
        assertEquals(RedeemStatus.REDEEMED, Redeem.statusOf(0))
        assertEquals(RedeemStatus.EXPIRED, Redeem.statusOf(-2001))
        assertEquals(RedeemStatus.USED, Redeem.statusOf(-2017))
        assertEquals(RedeemStatus.NOT_LOGGED_IN, Redeem.statusOf(-1071))
        assertEquals(RedeemStatus.TOO_FAST, Redeem.statusOf(-2016))
    }
}
