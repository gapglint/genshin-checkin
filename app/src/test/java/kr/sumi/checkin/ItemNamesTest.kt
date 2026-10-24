package kr.sumi.checkin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemNamesTest {
    @Test fun koreanNameFromItemPage() {
        assertEquals("「자애」의 가르침", ItemNames.koName("{{Item Infobox}}\n|ko      = 「자애」의 가르침\n|ko_rm   = x"))
        assertEquals("절운고추 치킨", ItemNames.koName("|ko      = {{tt|절운|絕雲}}고추 치킨\n|ko_rm   = Jeorun"))
        assertEquals(null, ItemNames.koName("|ko_rm = Jeorun"))
    }

    @Test fun parsesBatchAndMapsRedirectsBack() {
        val json = JSONObject("""{"query":{
            "redirects":[{"from":"Hero's Wit (Item)","to":"Hero's Wit"}],
            "pages":[
              {"title":"Hero's Wit","revisions":[{"slots":{"main":{"content":"|ko = 영웅의 경험\n"}}}]},
              {"title":"Geode of Replication","revisions":[{"slots":{"main":{"content":"|ko = 구현의 수정\n"}}}]},
              {"title":"Unknown","missing":true}
            ]}}""")
        assertEquals(mapOf("Hero's Wit (Item)" to "영웅의 경험", "Geode of Replication" to "구현의 수정"), ItemNames.parse(json))
    }
}
