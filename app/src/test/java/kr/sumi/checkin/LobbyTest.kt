package kr.sumi.checkin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LobbyTest {
    @Test fun parsesBattleChronicleCharacters() {
        val json = JSONObject("""{"retcode":0,"data":{"avatars":[
          {"id":10000073,"name":"나히다","element":"Dendro","rarity":5,"level":90,"fetter":10,"actived_constellation_num":2,"image":"https://example.com/n.png"},
          {"id":1,"name":"","element":"Cryo"}]}}""")
        val list = Lobby.parseCharacters(json)
        assertEquals(1, list.size)
        assertEquals(OwnedCharacter(10000073, "나히다", "dendro", 5, 90, 10, 2, "https://example.com/n.png"), list[0])
    }

    @Test fun findsWikiGalleryPortrait() {
        val data = JSONObject().put("pic", "https://example.com/神里绫华/avatar_header_ko.png").toString()
        val page = JSONObject().put("data", JSONObject().put("page", JSONObject().put("modules", org.json.JSONArray()
            .put(JSONObject().put("components", org.json.JSONArray().put(JSONObject().put("component_id", "baseInfo").put("data", "{}"))))
            .put(JSONObject().put("components", org.json.JSONArray().put(JSONObject().put("component_id", "gallery_character").put("data", data)))))))
        assertEquals("https://example.com/神里绫华/avatar_header_ko.png", Lobby.galleryPortrait(page))
        assertNull(Lobby.galleryPortrait(JSONObject("{}")))
        // 그림 주소의 한자는 인코딩해서 받는다.
        assertEquals("https://example.com/%E7%A5%9E%E9%87%8C%E7%BB%AB%E5%8D%8E/a.png", Lobby.ascii("https://example.com/神里绫华/a.png"))
    }
}
