package kr.sumi.checkin

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLangTest {
    init { TestText.install() }

    @After fun reset() { DataLang.override = null }

    @Test fun mapsAppLanguageToDataLanguage() {
        // 준비 전(단위 테스트)에는 한국어.
        assertEquals("ko-kr", DataLang.code())
        assertEquals("ko-kr", DataLang.fromLanguages(listOf("ko")))
        assertEquals("ja-jp", DataLang.fromLanguages(listOf("ja")))
        assertEquals("en-us", DataLang.fromLanguages(listOf("en")))
        assertEquals("en-us", DataLang.fromLanguages(listOf("fr")))
        assertEquals("en-us", DataLang.fromLanguages(emptyList()))
        // 앱에 없는 언어는 건너뛰고 다음 언어(안드로이드가 문구를 고르는 순서).
        assertEquals("ja-jp", DataLang.fromLanguages(listOf("fr", "ja", "ko")))
        DataLang.override = DataLang.JA
        assertEquals("ja", DataLang.short())
        assertEquals("ja-jp", CheckInClient.LANG)
    }

    private fun voice(title: String, desc: String, vararg hashes: String) =
        Lobby.WikiVoice(title, desc, hashes.withIndex().associate { (i, h) -> "lang$i" to "https://example.com/2024/05/14/${h}_${i}123.mp3" })

    private val h1 = "c0ef4cb38254baccd963519f4978ac01"
    private val h2 = "379f671de1c8cb0b1ebdc980747b7ff9"
    private val h3 = "170a2679b3762989295fc8849b28a6e6"
    private val h4 = "12579cafd5419f067e6910167a586b66"

    @Test fun alignsForeignLinesByAudio() {
        val ko = listOf(voice("첫 만남…", "안녕", h1), voice("「시뇨라」에 대해…", "시뇨라", h2), voice("잡담·눈", "눈", h3))
        // 영어 문서는 한 줄이 빠져 있고 다른 주소에 둔 음성도 내용 해시는 같다.
        val en = listOf(voice("Hello", "Hi", "zzzz", h1), voice("Chat: Eyes", "<p>Eyes</p>", h3))
        assertEquals(listOf("Hi", null, "Eyes"), Lobby.localTexts(ko, en))
    }

    @Test fun fallsBackToSameIndexOnlyWhenOrderIsProven() {
        val ko = listOf(voice("a", "가", h1), voice("b", "나", h2), voice("c", "다", h3))
        // 음성이 다시 올라가 해시가 다른 줄은, 줄 수가 같고 맞춘 줄이 모두 같은 자리면 같은 자리 글을 쓴다.
        val ja = listOf(voice("a", "あ", h1), voice("b", "い", "ffffffffffffffffffffffff00000001"), voice("c", "う", h3))
        assertEquals(listOf("あ", "い", "う"), Lobby.localTexts(ko, ja))
        // 맞춘 줄이 자리를 벗어나면(순서가 다르면) 못 맞춘 줄은 한국어 그대로.
        val shuffled = listOf(voice("c", "う", h3), voice("b", "い", "ffffffffffffffffffffffff00000001"), voice("a", "あ", h1))
        assertEquals(listOf("あ", null, "う"), Lobby.localTexts(ko, shuffled))
        // 줄 수가 다르면 같은 자리로 맞추지 않는다.
        assertEquals(listOf("あ", null, "う"), Lobby.localTexts(ko, ja + voice("d", "え", h4)).let { listOf(it[0], it[1], it[2]) })
        // 하나도 못 맞추면 모두 한국어.
        val unknown = ko.map { voice(it.title, "x", "eeeeeeeeeeeeeeeeeeeeeeee00000002") }
        assertEquals(listOf(null, null, null), Lobby.localTexts(ko, unknown))
    }

    @Test fun replacesOnlyDisplayedText() {
        val ko = listOf(
            voice("아침 인사…", "<p>좋은 아침</p>", h1),
            voice("우리에 대해·손님", "<p>손님</p><p>호감도 Lv.6 달성 후 개방</p>", h2),
            voice("첫 만남…", "<p>안녕 (「루미네」 전용 음성)</p>", h3),
        )
        val ja = listOf(
            voice("おはよう…", "<p>おはよう。</p>", h1),
            voice("訪問について…", "<p>子供たちは君から物語を聞くのが好きらしい。</p><p><strong>解放条件：好感度Lv.6</strong></p>", h2),
            voice("初めまして…", "<p>はじめまして</p>", h3),
        )
        val lines = Lobby.voiceLines(ko, ja)
        // 제목·호감도·성별 표시는 한국어 문서로 정한다(대사 고르기가 그대로 동작).
        assertEquals(listOf("아침 인사…", "우리에 대해·손님", "첫 만남…"), lines.map { it.title })
        assertEquals(listOf("おはよう。", "子供たちは君から物語を聞くのが好きらしい。", "はじめまして"), lines.map { it.text })
        assertEquals(listOf(0, 6, 0), lines.map { it.fetter })
        assertEquals(listOf(false, false, true), lines.map { it.gendered })
        // 다른 언어 문서가 없으면 한국어 그대로.
        assertEquals(listOf("좋은 아침", "손님", "안녕"), Lobby.voiceLines(ko).map { it.text })
    }

    @Test fun stripsForeignUnlockConditions() {
        assertEquals("フォンテーヌの水龍のこと？", Lobby.foreignText("<p>フォンテーヌの水龍のこと？</p><p><strong>解放条件：好感度Lv.4、シナリオ「真実の月」をクリア</strong></p>"))
        assertEquals("The Hydro Dragon? \"Hmm\"... I'm not sure.", Lobby.foreignText(
            "<p><span style=\"color: rgb(255, 215, 128)\">Unlocks at Friendship Lv. 4</span></p><p><span style=\"color: rgb(255, 215, 128)\">Complete &#34;True Moon&#34;</span></p>" +
                "<p><br>The Hydro Dragon? &quot;Hmm&quot;... I&#39;m not sure.</p>"))
        assertEquals("Aiming a beam of moonlight...", Lobby.foreignText("<p><span>Complete \"Elegy of Dust and Lamplight\"</span><br><br>Aiming a beam of moonlight...</p>"))
        assertEquals("Thanks!", Lobby.foreignText("<p>Thanks! Unlocks at Ascension Phase 2</p>"))
        assertNull(Lobby.foreignText("<p><strong>解放条件：好感度Lv.4</strong></p>"))
        assertEquals("aa1234567890abcdef", Lobby.audioKey("https://act-upload.hoyoverse.com/x/2024/08/28/1/aa1234567890abcdef_3212179203268498344.mp3"))
        assertNull(Lobby.audioKey("https://example.com/ko.wav"))
    }

    @Test fun joinsLocalNamesByEntryId() {
        val ko = listOf(Lobby.WikiCharacter("아를레키노", "5149", "pyro", ""), Lobby.WikiCharacter("치오리", "4946", "geo", ""), Lobby.WikiCharacter("새 캐릭터", "1", "", ""))
        val items = org.json.JSONArray()
            .put(JSONObject().put("entry_page_id", "4946").put("name", "千織"))
            .put(JSONObject().put("entry_page_id", "5149").put("name", "アルレッキーノ "))
        val names = Lobby.localNames(ko, Lobby.parseWikiNames(items))
        assertEquals(mapOf("아를레키노" to "アルレッキーノ", "치오리" to "千織"), names)

        CharNames.set(DataLang.JA, names)
        DataLang.override = DataLang.JA
        assertEquals("千織", CharNames.of("치오리", "Chiori"))
        // 맞춘 이름이 없으면 영어 이름, 그것도 없으면 한국어.
        assertEquals("New One", CharNames.of("새 캐릭터", "New One"))
        assertEquals("새 캐릭터", CharNames.of("새 캐릭터"))
        assertEquals("旅人", CharNames.of("여행자"))
        // 영어는 복각표의 영어 이름이 먼저.
        DataLang.override = DataLang.EN
        CharNames.set(DataLang.EN, mapOf("치오리" to "Chiori (wiki)"))
        assertEquals("Chiori", CharNames.of("치오리", "Chiori"))
        assertEquals("Chiori (wiki)", CharNames.of("치오리"))
        // 한국어는 늘 그대로.
        DataLang.override = DataLang.KO
        assertEquals("치오리", CharNames.of("치오리", "Chiori"))
    }

    @Test fun rerunCardShowsLocalNames() {
        val data = Reruns.parse(JSONObject(javaClass.getResource("/reruns.json")!!.readText()))
        val today = java.time.LocalDate.of(2026, 10, 24)
        val ko = data.card(today)
        assertTrue(ko.names.isEmpty())
        DataLang.override = DataLang.EN
        val en = data.card(today)
        assertEquals(ko.chars, en.chars)
        assertEquals(en.chars.map { (k, _) -> data.byKo(k)!!.name }, en.chars.map { en.label(it.first) })
    }

    @Test fun eventTagsWorkInEveryLanguage() {
        fun e(name: String, path: String = "/article/1") = GameEvent("1", name, 0, 0, path, true)
        assertTrue(Events.isWebEvent(e("[웹이벤트] 「츄츄족의 기계 공방」")))
        assertTrue(Events.isWebEvent(e("【Webイベント】「ヒルチャールの機械工房」")))
        assertTrue(Events.isWebEvent(e("The \"Anniversary Memories Album\" web event is now live")))
        assertTrue(Events.isWebEvent(e("anything", "https://act.hoyolab.com/ys/event/x")))
        assertFalse(Events.isWebEvent(e("7.2 버전 미리보기 토론방")))
        assertTrue(Events.hasPrimogems(e("[원석 보상] 토픽 토론")))
        assertTrue(Events.hasPrimogems(e("【原石報酬】トピック")))
        assertTrue(Events.hasPrimogems(e("Primogem Rewards: Participate")))
        assertFalse(Events.hasPrimogems(e("Intertwined Fate ×500")))
    }

    @Test fun itemNamesFollowLanguage() {
        assertEquals(R.drawable.ic_item_primogem, ItemIcons.of("原石"))
        assertEquals(R.drawable.ic_item_mora, ItemIcons.of("モラ"))
        assertEquals(R.drawable.ic_item_book, ItemIcons.of("大英雄の経験"))
        assertEquals(R.drawable.ic_item_ore, ItemIcons.of("仕上げ用魔鉱"))
        assertEquals(R.drawable.ic_item_primogem, ItemIcons.of("원석"))
        assertEquals(R.drawable.ic_item_book, ItemIcons.of("Hero's Wit"))
        assertEquals(listOf("原石" to 140, "モラ" to 20000, "Mora" to 1), RewardHistory.totals(listOf("モラ" to 20000, "Mora" to 1, "原石" to 140)))

        assertEquals(listOf("原石" to 60, "大英雄の経験" to 2), Redeem.rewardItems("[[Primogem]]*60;Hero's Wit*2", DataLang.JA))
        assertEquals(listOf("Primogem" to 60, "Hero's Wit" to 2), Redeem.rewardItems("[[Primogem]]*60;Hero's Wit*2", DataLang.EN))
        assertEquals(listOf("원석" to 60, "영웅의 경험" to 2), Redeem.rewardItems("[[Primogem]]*60;Hero's Wit*2"))

        val json = JSONObject("""{"query":{"pages":[
              {"title":"Fragile Resin","revisions":[{"slots":{"main":{"content":"|ja      = 脆弱樹脂\n|ja_rm   = Zeijaku Jushi\n|ko      = 약한 레진\n"}}}]}
            ]}}""")
        assertEquals(mapOf("Fragile Resin" to "脆弱樹脂"), ItemNames.parse(json, DataLang.JA))
        assertEquals(mapOf("Fragile Resin" to "약한 레진"), ItemNames.parse(json))
        assertEquals("ja:Fragile Resin", ItemNames.key("Fragile Resin", DataLang.JA))
        assertNull(ItemNames.key("Fragile Resin", DataLang.EN))
    }
}
