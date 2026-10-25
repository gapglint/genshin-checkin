package kr.sumi.checkin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            .put(JSONObject().put("title", "첫 만남…").put("desc", "<p>안녕하세요 (「루미네」 전용 음성)</p>"))
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
            Lobby.VoiceLine("첫 만남…", "안녕하세요", null, gendered = true),
            Lobby.VoiceLine("눈이 올 때…", "같이 할래요?", null, fetter = 6),
            Lobby.VoiceLine("우리에 대해", "이제 우리 이야기야", null, fetter = 4, story = true),
            Lobby.VoiceLine("누군가에 대해", "미안해서랄까", null, fetter = 4, story = true),
        ), lines)
        assertEquals(1, Lobby.pick(lines, "아침 인사").size)
        // 호감도 4면 Lv.6 대사는 아직 잠겨 있고, 스토리 조건 대사는 늘 뺀다.
        assertEquals(listOf("아침 인사…", "돌파의 느낌·기"), Lobby.unlocked(lines, 4).map { it.title })
        assertEquals(listOf("아침 인사…", "돌파의 느낌·기", "눈이 올 때…"), Lobby.unlocked(lines, 10).map { it.title })
    }

    @Test fun greetingMixesOfficialAndTalk() {
        val lines = listOf(
            Lobby.VoiceLine("아침 인사…", "아침인가요?", null),
            Lobby.VoiceLine("잡담·더위에 약함", "시원한 곳으로 가고 싶어", null),
            Lobby.VoiceLine("치치의 취미…", "애완동물이 있었으면 좋겠어요", null),
            Lobby.VoiceLine("저녁 인사…", "저녁이네", null),
            Lobby.VoiceLine("비가 올 때…", "우산을 깜빡했네", null),
            Lobby.VoiceLine("호두에 대해…", "싫어", null),
        )
        val seen = (0 until 200).mapNotNull { Lobby.greetingLine(lines, "치치", 8, kotlin.random.Random(it))?.text }.toSet()
        // 아침에는 아침 인사와 평소 이야기만(저녁 인사·날씨·다른 캐릭터 이야기는 안 나온다).
        assertEquals(setOf("아침인가요?", "시원한 곳으로 가고 싶어", "애완동물이 있었으면 좋겠어요"), seen)
        // 공식 인사가 없는 캐릭터는 평소 이야기만.
        assertEquals("시원한 곳으로 가고 싶어", Lobby.greetingLine(lines.drop(1).take(1), "치치", 8)?.text)
    }

    @Test fun parsesWikiCharacterList() {
        fun item(name: String, element: String?) = JSONObject().put("name", name).put("entry_page_id", "1").put("icon_url", "https://example.com/$name.png")
            .put("filter_values", JSONObject().apply {
                if (element != null) put("character_vision", JSONObject().put("value_types", org.json.JSONArray().put(JSONObject().put("enum_string", element))))
            })
        val list = Lobby.parseWikiList(org.json.JSONArray().put(item("발레리", "electro")).put(item("여행자(물)", "hydro")).put(item("별인형·여성", null)).put(item("새 캐릭터", null)))
        assertEquals(listOf(Lobby.WikiCharacter("발레리", "1", "electro", "https://example.com/발레리.png"), Lobby.WikiCharacter("새 캐릭터", "1", "", "https://example.com/새 캐릭터.png")), list)
    }

    @Test fun waitingLineUsesRerunData() {
        val data = Reruns.parse(JSONObject(javaClass.getResource("/reruns.json")!!.readText()))
        val today = java.time.LocalDate.of(2026, 10, 24)
        assertEquals("아직 만나지 못했어요 · 지금 픽업 중!", Lobby.waitingLine(data, "스커크", today))
        assertTrue(Lobby.waitingLine(data, "호두", today).startsWith("아직 만나지 못했어요 · 마지막 픽업 "))
        assertEquals("아직 만나지 못했어요", Lobby.waitingLine(data, "향릉", today))
        assertEquals("아직 만나지 못했어요", Lobby.waitingLine(null, "호두", today))
    }
}
