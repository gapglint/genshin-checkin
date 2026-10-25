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

    @Test fun parsesVoiceLines() {
        val data = JSONObject().put("list", org.json.JSONArray()
            .put(JSONObject().put("title", "아침 인사…").put("desc", "<p>좋은 아침이에요.<br>오늘도 힘내요</p>")
                .put("audios", org.json.JSONArray().put(JSONObject().put("name", "한국어").put("url", "https://example.com/ko.wav"))))
            .put(JSONObject().put("title", "돌파의 느낌·기").put("desc", "<p>정말 감사해요돌파 Lv.「1」 달성 후 개방</p>"))
            .put(JSONObject().put("title", "눈이 올 때…").put("desc", "<p><span class=\"wk-shady\">같이 할래요?</span></p><p>호감도 Lv.6 달성 후 개방</p>"))
            .put(JSONObject().put("title", "우리에 대해").put("desc", "<p>호감도 Lv.4 달성 후 개방</p><p>「물의 아이」 스토리 클리어</p><p>이제 우리 이야기야</p>"))
            .put(JSONObject().put("title", "누군가에 대해").put("desc", "<p>미안해서랄까</p><p>호감도 Lv.4 달성 및 「천수백안의 세상」 스토리 완료 후 개방<br><br>※주의: 더 이상 들을 수 없습니다</p>"))
            .put(JSONObject().put("title", "빈 대사").put("desc", "<p></p>"))).toString()
        val page = JSONObject().put("data", JSONObject().put("page", JSONObject().put("modules", org.json.JSONArray()
            .put(JSONObject().put("components", org.json.JSONArray().put(JSONObject().put("component_id", "voice").put("data", data)))))))
        val lines = Lobby.voiceLines(page)
        assertEquals(listOf(
            Lobby.VoiceLine("아침 인사…", "좋은 아침이에요. 오늘도 힘내요", "https://example.com/ko.wav"),
            Lobby.VoiceLine("돌파의 느낌·기", "정말 감사해요", null),
            Lobby.VoiceLine("눈이 올 때…", "같이 할래요?", null),
            Lobby.VoiceLine("우리에 대해", "이제 우리 이야기야", null),
            Lobby.VoiceLine("누군가에 대해", "미안해서랄까", null),
        ), lines)
        assertEquals(1, Lobby.pick(lines, "아침 인사").size)
    }
}
