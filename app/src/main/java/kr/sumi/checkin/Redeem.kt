package kr.sumi.checkin

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/** 리딤 코드를 받을 게임 캐릭터. 출석과 달리 리딤 보상은 서버·UID 하나로만 간다. */
data class GameRole(val uid: String, val region: String, val regionName: String, val nickname: String, val level: Int) {
    /** 서버 이름(한국어). 모르는 서버면 호요랩이 준 이름. */
    val serverName get() = SERVER_NAMES[region] ?: regionName

    fun label() = "$serverName · $nickname (Lv.$level) · UID $uid"

    /** 짧은 이름: '아시아 서버 · 여행자'. */
    fun shortLabel() = "$serverName · $nickname"

    companion object {
        private val SERVER_NAMES = mapOf(
            "os_asia" to "아시아 서버", "os_usa" to "미국 서버", "os_euro" to "유럽 서버", "os_cht" to "TW·HK·MO 서버",
        )

        /** 화면에 보일 대표 캐릭터: 리딤용으로 고른 캐릭터, 없으면 레벨이 가장 높은 캐릭터. */
        fun representative(roles: List<GameRole>, chosen: GameRole?): GameRole? =
            roles.firstOrNull { it.uid == chosen?.uid } ?: roles.maxByOrNull { it.level }
    }
}

/** 코드 하나를 입력한 결과. */
enum class RedeemStatus { REDEEMED, USED, EXPIRED, INVALID, NOT_LOGGED_IN, TOO_FAST, OTHER }

/** 코드 하나를 한 캐릭터로 입력한 결과. role은 그 캐릭터의 짧은 이름. */
data class RedeemAttempt(val code: String, val status: RedeemStatus, val message: String, val role: String? = null)

data class RedeemReport(val found: Int, val attempts: List<RedeemAttempt>, val stopReason: String? = null) {
    /** 받은 코드(여러 캐릭터가 같은 코드를 받았으면 한 번만). */
    val redeemed get() = attempts.filter { it.status == RedeemStatus.REDEEMED }.map { it.code }.distinct()
    /** 코드를 받은 캐릭터 수. */
    val redeemedRoles get() = attempts.filter { it.status == RedeemStatus.REDEEMED }.mapNotNull { it.role }.distinct().size
    /** 이번에 입력해 본 캐릭터 수. */
    val roleCount get() = attempts.mapNotNull { it.role }.distinct().size
}

/**
 * 리딤 코드 자동 입력.
 * 코드는 세 곳에서 찾는다.
 * 1. 원신 Fandom 위키의 Promotional_Code 문서: 지금 유효한 코드만 서버·만료일·묶음과 함께 정리돼 있어 주 출처로 쓴다.
 * 2. 호요랩 원신 홈의 공식 코드 목록(방송 코드 등, 있을 때만).
 * 3. 호요랩 커뮤니티 글의 '보상 받기' 링크(genshin.hoyoverse.com/.../gift?code=...). 위키에 아직 안 올라온 새 코드용.
 * 한 번 입력해 본 코드는 기억해 두고 다시 입력하지 않는다. 입력 사이에는 호요랩 제한대로 6초씩 쉰다.
 */
object Redeem {
    private const val BBS = "https://bbs-api-os.hoyolab.com"
    /** 호요랩 공식 코드 목록. 생방송 코드도 방송 중에 여기 올라온다. */
    private const val OFFICIAL_URL = "$BBS/community/painter/wapi/circle/channel/guide/material?game_id=2"
    private const val GAME_BIZ = "hk4e_global"
    private const val CODE_GAP_MS = 6_000L
    /**
     * 이보다 오래된 커뮤니티 글은 보지 않는다. 코드는 며칠에서 몇 달까지 유효해서 넉넉히 잡는다.
     * 만료된 코드는 한 번 입력해 보고 기억하므로 다시 시도하지 않는다.
     */
    private const val POST_MAX_AGE_SEC = 90L * 24 * 3600
    private const val MAX_POSTS = 10
    /** 이미 읽은 글은 다시 열지 않는다. 다만 막 올라온 글은 코드가 추가될 수 있어 이 기간 동안은 다시 본다. */
    private const val POST_RECHECK_SEC = 2L * 24 * 3600
    /** 링크를 띄어쓰기 없이 이어 붙인 글(…code=ABC123https://…)도 있어 코드 뒤에 http가 오면 거기서 끊는다. */
    private val CODE_IN_LINK =
        Regex("""gift\?code=([A-Za-z0-9]{6,20}?)(?=https?:|[^A-Za-z0-9]|$)""", RegexOption.IGNORE_CASE)
    private const val WIKI_URL = "https://genshin-impact.fandom.com/api.php" +
        "?action=parse&page=Promotional_Code&prop=wikitext&format=json&formatversion=2"
    private val WIKI_SERVERS = mapOf("os_asia" to "SEA", "os_usa" to "NA", "os_euro" to "EU", "os_cht" to "SAR")
    /** 리딤 보상에 자주 나오는 아이템의 한국어 이름. 나머지는 위키의 영어 이름으로 보여 준다. */
    private val ITEM_NAMES = mapOf(
        "Primogem" to "원석", "Mora" to "모라",
        "Hero's Wit" to "영웅의 경험", "Adventurer's Experience" to "모험가의 경험", "Wanderer's Advice" to "방랑자의 경험",
        "Mystic Enhancement Ore" to "정제용 마법 광석", "Fine Enhancement Ore" to "정제용 양질 광석", "Enhancement Ore" to "정제용 광석",
        "Fragile Resin" to "취약한 레진",
    )
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val NAMED_PARAM = Regex("""^[A-Za-z_]+\s*=.*""", RegexOption.DOT_MATCHES_ALL)
    private val SERVER_SPLIT = Regex("""[,/\s]+""")
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val CODE_TEXT = Regex("[A-Z0-9]{6,20}")
    private val BBS_HEADERS = mapOf("x-rpc-client_type" to "4", "x-rpc-app_version" to "2.50.0", "x-rpc-language" to "ko-kr")

    /** 이 계정의 원신 캐릭터 목록(서버별). */
    @Throws(IOException::class)
    fun roles(client: CheckInClient): List<GameRole> =
        parseRoles(client.get("https://api-account-os.hoyolab.com/binding/api/getUserGameRolesByCookie?game_biz=$GAME_BIZ"))

    /**
     * 새 코드를 찾아 고른 캐릭터마다 입력한다. 코드는 서버(UID)마다 따로 쓸 수 있어서 캐릭터별로 기억한다.
     * 수동 실행과 예약 실행이 겹치지 않게 한 번에 하나만.
     * progress: 화면에 보일 단계와 진행률(0~1). 코드 찾기가 앞 40%, 입력이 나머지다.
     */
    @Throws(IOException::class)
    fun run(store: Store, onProgress: (String, Float) -> Unit = { _, _ -> }): RedeemReport = synchronized(this) {
        try {
            runLocked(store) { step, done -> running = step to done; onProgress(step, done) }
        } finally {
            running = null
        }
    }

    /**
     * 지금 돌고 있는 리딤(예약 실행 포함)의 단계와 진행률. 안 돌고 있으면 null.
     * 화면은 예약 실행이 도는 중에 버튼을 누르면 새로 돌리지 않고 이것을 보여 준다(같은 계정 코드를 두 번 입력하지 않게).
     */
    @Volatile var running: Pair<String, Float>? = null
        private set

    private fun runLocked(store: Store, progress: (String, Float) -> Unit): RedeemReport {
        progress("리딤 코드 찾는 중...", 0f)
        val client = CheckInClient(store.cookie ?: return RedeemReport(0, emptyList(), "로그인이 필요해요."))
        // 리딤 코드는 HoYoverse 계정 전체에 한 번이라, 고른 캐릭터 하나로만 입력한다.
        val roles = listOfNotNull(store.redeemRole ?: chooseOnlyRole(client, store))
        if (roles.isEmpty()) return RedeemReport(0, emptyList(), "리딤 보상을 받을 캐릭터를 골라 주세요.")

        // 코드 찾기는 한 번만 하고, 위키 코드는 캐릭터의 서버에 맞는 것만 고른다.
        val (wiki, extra) = findSources(client, store) { step, done -> progress(step, done * 0.4f) }
        val attempts = mutableListOf<RedeemAttempt>()
        var found = 0
        var stop: String? = null
        roles@ for (role in roles) {
            val tried = store.redeemTried(role)
            // 묶음 코드는 하나만 쓸 수 있으니 묶음 단위로 다룬다. 묶음 안에서 하나라도 받았거나 이미 썼으면 나머지는 건너뛴다.
            val groups = codeGroups(wiki, extra, role.region, ServerClock.today())
                .map { group -> group.filter { it !in tried } }.filter { it.isNotEmpty() }
            found += groups.sumOf { it.size }
            // 진행 표시는 코드 단위로 센다(끝난 뒤 결과 창의 개수와 맞게). 묶음에서 앞 코드가 통해 건너뛴 코드도 센 것으로 친다.
            val total = groups.sumOf { it.size }
            var counted = 0
            for (group in groups) {
                for (code in group) {
                    counted++
                    progress("코드 넣어 보는 중 ($counted/$total)", 0.4f + 0.6f * (counted - 1) / total)
                    if (attempts.isNotEmpty()) Thread.sleep(CODE_GAP_MS)
                    var attempt = redeem(client, role, code)
                    if (attempt.status == RedeemStatus.TOO_FAST) {
                        Thread.sleep(CODE_GAP_MS)
                        attempt = redeem(client, role, code)
                    }
                    attempts += attempt
                    if (attempt.status == RedeemStatus.NOT_LOGGED_IN) {
                        stop = "로그인이 풀려서 리딤 코드를 입력하지 못했어요. 출석도 같이 멈춰요. '로그인 상태 확인'에서 이 계정으로 다시 로그인해 주세요."
                        break@roles
                    }
                    if (attempt.status == RedeemStatus.TOO_FAST) continue
                    if (attempt.status == RedeemStatus.REDEEMED) {
                        store.redeemLog = listOf(RedeemedCode(code, ServerClock.now(), rewardOf(code), role.shortLabel())) + store.redeemLog
                    }
                    if (attempt.status == RedeemStatus.REDEEMED || attempt.status == RedeemStatus.USED) {
                        store.setRedeemTried(role, store.redeemTried(role) + group)
                        counted += group.size - group.indexOf(code) - 1
                        break
                    }
                    store.setRedeemTried(role, store.redeemTried(role) + code)
                }
            }
        }
        progress("마무리 중...", 1f)
        val report = RedeemReport(found, attempts, stop)
        store.lastRedeemAt = ServerClock.now()
        store.lastRedeemSummary = summary(report)
        return report
    }

    /**
     * 계정의 대표 캐릭터(닉네임 표시용)를 불러와 저장한다. 네트워크 오류는 IOException.
     * 캐릭터가 하나뿐이면 리딤 캐릭터도 바로 정해, 로그인 직후부터 '골라 주세요' 대신 그 캐릭터가 보이게 한다.
     */
    @Throws(IOException::class)
    fun refreshAccount(store: Store) {
        applyRoles(store, roles(CheckInClient(store.cookie ?: return)))
    }

    /** 불러온 캐릭터 목록을 반영한다: 하나뿐이면 리딤 캐릭터로 정하고, 대표 캐릭터를 저장한다. */
    fun applyRoles(store: Store, roles: List<GameRole>) {
        if (store.redeemRole == null && roles.size == 1) store.redeemRoles = roles
        store.accountRole = GameRole.representative(roles, store.redeemRole)
    }

    /** 캐릭터가 하나뿐이면 묻지 않고 그 캐릭터로 정한다. */
    private fun chooseOnlyRole(client: CheckInClient, store: Store): GameRole? {
        val roles = roles(client)
        if (roles.size != 1) return null
        store.redeemRoles = roles
        return roles.single()
    }

    /**
     * 위키 문서와, 공식 목록 + 최근 커뮤니티 글 + 사이트 코드 목록에서 찾은 코드를 모은다. 한쪽이 실패해도 나머지는 쓴다.
     * 위키는 서버별로 걸러야 해서 문서째 돌려주고, 나머지 코드는 서버 정보가 없어 그대로 돌려준다.
     */
    private fun findSources(client: CheckInClient, store: Store, progress: (String, Float) -> Unit): Pair<JSONObject?, List<String>> {
        // 위키는 다른 서버라 호요랩 글을 읽는 동안 따로 받아 둔다(느릴 때 기다리는 시간을 줄인다).
        var wiki: JSONObject? = null
        val wikiThread = Thread {
            try {
                wiki = wikiPage()
            } catch (e: Exception) {
            }
        }.apply { start() }
        val codes = linkedSetOf<String>()
        progress("공식 코드 목록 보는 중...", 0.1f)
        try {
            codes += parseOfficialCodes(client.get(OFFICIAL_URL, BBS_HEADERS))
        } catch (e: Exception) {
        }
        try {
            val keyword = URLEncoder.encode("리딤코드", "UTF-8")
            val search = client.get("$BBS/community/search/wapi/search/post?keyword=$keyword&size=20&game_id=2", BBS_HEADERS)
            val nowSec = ServerClock.now() / 1000
            val fresh = recentPostIds(search, nowSec - POST_RECHECK_SEC).toSet()
            val scanned = store.redeemScannedPosts
            val toRead = recentPostIds(search, nowSec - POST_MAX_AGE_SEC).take(MAX_POSTS)
                .filter { it !in scanned || it in fresh }
            for ((index, postId) in toRead.withIndex()) {
                progress("커뮤니티 글 보는 중 (${index + 1}/${toRead.size})", 0.2f + 0.6f * index / toRead.size)
                Thread.sleep(CheckInClient.REQUEST_GAP_MS)
                codes += parseCodesInPost(client.get("$BBS/community/post/wapi/getPostFull?post_id=$postId", BBS_HEADERS))
                store.redeemScannedPosts = store.redeemScannedPosts + postId
            }
        } catch (e: Exception) {
        }
        progress("위키 보는 중...", 0.9f)
        codes += runCatching { siteCodes(fetchPublic(PublicCodes.SITE_URL)) }.getOrDefault(emptyList())
        wikiThread.join()
        return wiki to codes.toList()
    }

    /** 한 캐릭터가 입력할 코드 묶음: 그 서버의 위키 코드(묶음 그대로) + 위키에 없는 나머지 코드(하나씩). */
    internal fun codeGroups(wiki: JSONObject?, extra: List<String>, region: String, today: LocalDate): List<List<String>> {
        val groups = wiki?.let { parseWikiCodes(it, region, today) }.orEmpty().toMutableList()
        val known = groups.flatten().toSet()
        groups += extra.filter { it !in known }.distinct().map { listOf(it) }
        return groups
    }

    /** 여러 계정을 연달아 돌 때 위키를 계정마다 받지 않도록 잠깐 기억해 둔다. */
    private var wikiCache: Pair<Long, JSONObject>? = null

    private fun wikiPage(): JSONObject {
        wikiCache?.let { (at, json) -> if (System.currentTimeMillis() - at < 10 * 60_000) return json }
        return fetchPublic(WIKI_URL).also {
            wikiCache = System.currentTimeMillis() to it
            // 처음 보는 보상 아이템의 한국어 이름을 배워 둔다(한 번 배우면 다시 묻지 않는다).
            ItemNames.learnFromWiki(it, ITEM_NAMES.keys)
        }
    }

    /** 로그인 쿠키 없이 공개 페이지(위키, 호요랩 공식 코드 목록)를 받는다. */
    private fun fetchPublic(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "SumiLab-Checkin/1.0 (Android)")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun redeem(client: CheckInClient, role: GameRole, code: String): RedeemAttempt {
        val url = "https://sg-hk4e-api.hoyoverse.com/common/apicdkey/api/webExchangeCdkeyHyl" +
            "?cdkey=$code&game_biz=$GAME_BIZ&lang=ko&region=${role.region}&uid=${role.uid}&t=${ServerClock.now()}"
        val json = client.get(url, mapOf("Origin" to "https://www.hoyolab.com", "Referer" to "https://www.hoyolab.com/"))
        return RedeemAttempt(code, statusOf(json.optInt("retcode", Int.MIN_VALUE)), json.optString("message"), role.shortLabel())
    }

    fun summary(report: RedeemReport): String = when {
        report.stopReason != null && report.attempts.none { it.status == RedeemStatus.REDEEMED } -> report.stopReason
        report.redeemed.isNotEmpty() -> "새 코드 ${report.redeemed.size}개 입력" +
            (if (report.redeemedRoles > 1) " · 캐릭터 ${report.redeemedRoles}명" else "") + ": ${report.redeemed.joinToString(", ")}"
        report.attempts.isEmpty() -> "새 코드 없음"
        // 앞부분(':' 앞)은 타일·위젯에 짧게 보인다.
        else -> "받을 새 코드 없음: ${report.attempts.size}개 넣어 봄 (" + report.attempts.groupingBy { shortStatus(it.status) }.eachCount()
            .entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value}" } + ")"
    }

    private fun shortStatus(status: RedeemStatus) = when (status) {
        RedeemStatus.REDEEMED -> "받음"
        // 호요랩은 '이 계정이 받음'과 '선착순 마감'을 같은 답(-2017)으로 준다.
        RedeemStatus.USED -> "받았거나 마감"
        RedeemStatus.EXPIRED -> "만료"
        RedeemStatus.INVALID -> "잘못된 코드"
        RedeemStatus.NOT_LOGGED_IN -> "로그인 필요"
        RedeemStatus.TOO_FAST -> "너무 빠름"
        RedeemStatus.OTHER -> "기타"
    }

    /** 결과 창에 보일 자세한 내용. */
    fun details(report: RedeemReport): String {
        if (report.attempts.isEmpty()) return report.stopReason ?: "새로 올라온 코드가 없어요. 이미 입력한 코드는 다시 입력하지 않아요."
        val many = report.roleCount > 1
        val lines = report.attempts.joinToString("\n") {
            (if (many && it.role != null) "[${it.role}] " else "") + "${it.code}  ${statusText(it.status)}"
        }
        val tail = report.redeemed.takeIf { it.isNotEmpty() }?.let { "\n\n보상은 게임 우편함으로 와요." } ?: ""
        return (report.stopReason?.let { "$it\n\n" } ?: "") + lines + tail
    }

    private fun statusText(status: RedeemStatus) = when (status) {
        RedeemStatus.REDEEMED -> "받음"
        RedeemStatus.USED -> "이미 받았거나 마감된 코드"
        RedeemStatus.EXPIRED -> "만료됨"
        RedeemStatus.INVALID -> "잘못된 코드"
        RedeemStatus.NOT_LOGGED_IN -> "로그인 필요"
        RedeemStatus.TOO_FAST -> "너무 빨라서 건너뜀"
        RedeemStatus.OTHER -> "받지 못함"
    }

    /** 원신 웹 리딤 응답 코드. 0 외에는 호요랩이 공개하지 않아 자주 보이는 값만 나눈다. */
    internal fun statusOf(retcode: Int) = when (retcode) {
        0 -> RedeemStatus.REDEEMED
        -2017, -2018 -> RedeemStatus.USED
        -2001 -> RedeemStatus.EXPIRED
        -2003, -2004 -> RedeemStatus.INVALID
        -1071, -100 -> RedeemStatus.NOT_LOGGED_IN
        -2016 -> RedeemStatus.TOO_FAST
        else -> RedeemStatus.OTHER
    }

    internal fun parseRoles(json: JSONObject): List<GameRole> {
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }.map {
            GameRole(
                uid = it.optString("game_uid"),
                region = it.optString("region"),
                regionName = it.optString("region_name"),
                nickname = it.optString("nickname"),
                level = it.optInt("level"),
            )
        }.filter { it.uid.isNotEmpty() && it.region.isNotEmpty() }
    }

    /**
     * 위키 Promotional_Code 문서의 원문에서 내 서버에 쓸 수 있고 만료되지 않은 코드 묶음을 뽑는다.
     * 행 모양: {{Code Row|코드1;코드2|서버|보상|발견일|만료일|메모|notacode=yes|ref=...}}
     * 서버: G(중국 외 전체), A(전체), SEA(아시아), NA(미국), EU(유럽), SAR(TW·HK·MO), CN(중국).
     */
    internal fun parseWikiCodes(json: JSONObject, region: String, today: LocalDate): List<List<String>> {
        val text = json.optJSONObject("parse")?.optString("wikitext") ?: return emptyList()
        val mine = WIKI_SERVERS[region]
        return codeRows(text.replace(COMMENT, "")).mapNotNull { params ->
            if (params.any { it.replace(" ", "").equals("notacode=yes", ignoreCase = true) }) return@mapNotNull null
            val positional = params.filter { !NAMED_PARAM.matches(it) }
            val servers = positional.getOrNull(1)?.split(SERVER_SPLIT)?.map { it.trim().uppercase() }
                ?: return@mapNotNull null
            if (servers.none { it == "G" || it == "A" || it == mine }) return@mapNotNull null
            val expiry = positional.getOrNull(4)?.let { DATE.find(it)?.value }?.let(LocalDate::parse)
            if (expiry != null && expiry.isBefore(today)) return@mapNotNull null
            positional.getOrNull(0)?.split(";")?.map { it.trim().uppercase() }
                ?.filter { CODE_TEXT.matches(it) }?.takeIf { it.isNotEmpty() }
        }
    }

    /** 보상을 모르는 기록(예전 버전에서 받은 코드)에 위키의 보상을 채운다. 하나라도 채웠으면 true. 네트워크 오류는 false. */
    fun fillMissingRewards(store: Store): Boolean = synchronized(this) {
        val log = store.redeemLog
        if (log.none { it.reward == null }) return false
        val rewards = try { wikiRewards(wikiPage()) } catch (e: Exception) { return false }
        val filled = log.map { if (it.reward == null) it.copy(reward = rewards[it.code]) else it }
        if (filled == log) return false
        store.redeemLog = filled
        return true
    }

    /** 위키에 적힌 이 코드의 보상(위키 표기). 위키를 못 받았거나 위키에 없는 코드(커뮤니티 글에서 찾은 것)면 null. */
    private fun rewardOf(code: String): String? =
        try { wikiRewards(wikiPage())[code] } catch (e: Exception) { null }

    /** 위키 코드 줄 하나: 코드(묶음이면 여럿), 서버 표기(G, A, SEA, NA, EU, SAR, CN), 보상, 올라온 날, 만료일. */
    data class WikiCode(val codes: List<String>, val servers: List<String>, val reward: String?, val discovered: LocalDate?, val expiry: LocalDate?)

    /** 로그인 전 화면처럼 서버를 가리지 않고 보여 줄 때 쓰는 위키 코드 목록(코드가 아닌 줄은 뺀다). */
    internal fun wikiEntries(json: JSONObject): List<WikiCode> {
        val text = json.optJSONObject("parse")?.optString("wikitext") ?: return emptyList()
        return codeRows(text.replace(COMMENT, "")).mapNotNull { params ->
            if (params.any { it.replace(" ", "").equals("notacode=yes", ignoreCase = true) }) return@mapNotNull null
            val positional = params.filter { !NAMED_PARAM.matches(it) }
            val codes = positional.getOrNull(0)?.split(";")?.map { it.trim().uppercase() }?.filter { CODE_TEXT.matches(it) }
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            WikiCode(
                codes = codes,
                servers = positional.getOrNull(1)?.split(SERVER_SPLIT)?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }.orEmpty(),
                reward = positional.getOrNull(2)?.trim()?.takeIf { it.contains('*') },
                discovered = positional.getOrNull(3)?.let { DATE.find(it)?.value }?.let(LocalDate::parse),
                expiry = positional.getOrNull(4)?.let { DATE.find(it)?.value }?.let(LocalDate::parse),
            )
        }
    }

    /** 위키의 코드별 보상 표기. {{Code Row|코드|서버|보상|...}}의 세 번째 인자다. */
    internal fun wikiRewards(json: JSONObject): Map<String, String> {
        val text = json.optJSONObject("parse")?.optString("wikitext") ?: return emptyMap()
        val rewards = mutableMapOf<String, String>()
        for (params in codeRows(text.replace(COMMENT, ""))) {
            val positional = params.filter { !NAMED_PARAM.matches(it) }
            val reward = positional.getOrNull(2)?.trim()?.takeIf { it.contains('*') } ?: continue
            positional.getOrNull(0)?.split(";")?.map { it.trim().uppercase() }?.forEach { rewards[it] = reward }
        }
        return rewards
    }

    /** 위키 보상 표기를 (한국어 이름, 개수) 목록으로. 모르는 아이템은 영어 이름 그대로 둔다. */
    fun rewardItems(raw: String): List<Pair<String, Int>> = raw.split(";").mapNotNull { part ->
        // 위키 링크·틀 괄호를 뗀다. 안드로이드의 정규식(ICU)은 짝 없는 '}'를 오류로 보므로 정규식을 쓰지 않는다.
        val name = listOf("[[", "]]", "{{", "}}").fold(part.substringBefore("*")) { text, mark -> text.replace(mark, "") }.trim()
        val count = part.substringAfter("*", "").trim().replace(",", "").toIntOrNull() ?: return@mapNotNull null
        if (name.isEmpty()) null else (ITEM_NAMES[name] ?: ItemNames.korean(name) ?: name) to count
    }

    /** {{Code Row ...}} 틀마다 최상위 '|'로 나눈 인자 목록. 안쪽 {{ }}·[[ ]]의 '|'는 나누지 않는다. */
    private fun codeRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var start = text.indexOf("{{Code Row")
        while (start >= 0) {
            val head = start + "{{Code Row".length
            if (text.startsWith("/", head)) { // {{Code Row/Header}} 같은 다른 틀
                start = text.indexOf("{{Code Row", head)
                continue
            }
            val params = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var i = head
            while (i < text.length) {
                val two = text.substring(i, minOf(i + 2, text.length))
                if (two == "{{" || two == "[[") {
                    depth++; current.append(two); i += 2; continue
                }
                if (two == "}}" && depth == 0) break
                if (two == "}}" || two == "]]") {
                    depth--; current.append(two); i += 2; continue
                }
                if (text[i] == '|' && depth == 0) {
                    params += current.toString().trim(); current.clear()
                } else {
                    current.append(text[i])
                }
                i++
            }
            params += current.toString().trim()
            rows += params.drop(1) // 틀 이름과 첫 '|' 사이의 빈 칸
            start = text.indexOf("{{Code Row", i)
        }
        return rows
    }

    internal fun parseOfficialCodes(json: JSONObject): List<String> {
        val modules = json.optJSONObject("data")?.optJSONArray("modules") ?: return emptyList()
        val codes = mutableListOf<String>()
        for (i in 0 until modules.length()) {
            val bonuses = modules.getJSONObject(i).optJSONObject("exchange_group")?.optJSONArray("bonuses") ?: continue
            for (j in 0 until bonuses.length()) {
                val bonus = bonuses.getJSONObject(j)
                if (bonus.optString("code_status") == "ON") {
                    bonus.optString("exchange_code").takeIf { it.isNotEmpty() }?.let { codes += it.uppercase() }
                }
            }
        }
        return codes
    }

    /** 검색 결과에서 since(초) 이후에 올라온 글 ID를 최신순으로. */
    internal fun recentPostIds(json: JSONObject, since: Long): List<String> {
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.getJSONObject(it).optJSONObject("post") }
            .filter { it.optLong("created_at") >= since }
            .sortedByDescending { it.optLong("created_at") }
            .map { it.optString("post_id") }
            .filter { it.isNotEmpty() }
    }

    internal fun parseCodesInPost(json: JSONObject): List<String> {
        val post = json.optJSONObject("data")?.optJSONObject("post")?.optJSONObject("post") ?: return emptyList()
        val text = post.optString("content") + " " + post.optString("structured_content")
        return CODE_IN_LINK.findAll(text).map { it.groupValues[1].uppercase() }.distinct().toList()
    }

    /**
     * 사용자가 직접 적은 코드 하나를 고른 캐릭터로 입력한다(친구에게 받은 코드 등). 결과는 자동 입력과 같은 기록에 남긴다.
     * 네트워크 오류는 IOException.
     */
    @Throws(IOException::class)
    fun redeemOne(store: Store, raw: String): RedeemAttempt = synchronized(this) {
        val code = raw.trim().uppercase().filter { it.isLetterOrDigit() }
        val client = CheckInClient(store.cookie ?: return RedeemAttempt(code, RedeemStatus.NOT_LOGGED_IN, "로그인이 필요해요."))
        val role = store.redeemRole ?: chooseOnlyRole(client, store)
            ?: return RedeemAttempt(code, RedeemStatus.OTHER, "리딤 보상을 받을 캐릭터를 골라 주세요.")
        val attempt = redeem(client, role, code)
        if (attempt.status == RedeemStatus.REDEEMED) {
            store.redeemLog = listOf(RedeemedCode(code, ServerClock.now(), rewardOf(code), role.shortLabel())) + store.redeemLog
        }
        if (attempt.status in setOf(RedeemStatus.REDEEMED, RedeemStatus.USED, RedeemStatus.EXPIRED, RedeemStatus.INVALID)) {
            store.setRedeemTried(role, store.redeemTried(role) + code)
        }
        attempt
    }

    /** 직접 입력 결과 한 줄: "PJ2KEBRAYSRM · 받음". 받지 못했으면 호요버스가 준 문구를 붙인다. */
    fun describeOne(a: RedeemAttempt) = "${a.code} · ${statusText(a.status)}" +
        if (a.status == RedeemStatus.OTHER && a.message.isNotEmpty()) "\n${a.message}" else ""

    /** 출석이 끝난 뒤 따로 돌린다. 코드 사이 6초 대기 때문에 알람 처리 시간 안에 넣지 않는다. */
    fun scheduleSoon(context: Context) = Jobs.scheduleOnce(context, Jobs.REDEEM, delayMs = 10_000, replace = false)

    /**
     * 예약 실행(새벽 출석 뒤, 6시간마다, 금요일 밤 알람). 리딤 자동 입력이 켜져 있으면 계정마다
     * 하루 첫 번째는 커뮤니티 글까지 다 찾아 입력하고, 그 뒤로는 위키·공식 코드 목록에 아직 안 넣은 코드가 생겼을 때만 다시 돈다.
     * 새 코드 확인은 로그인 정보 없이 공개 목록만 본다. 네트워크 오류가 있었으면 true(다시 시도).
     */
    fun runScheduled(context: Context): Boolean {
        val settings = AppSettings(context)
        if (!settings.redeemEnabled) return false
        var retry = false
        val published by lazy { publishedCodes() }
        for (store in settings.accounts()) {
            val ranToday = store.lastRedeemAt > 0 && ServerClock.dayOf(store.lastRedeemAt) == ServerClock.today()
            if (ranToday && !hasUntried(store, published)) continue
            try {
                Notifier.notifyRedeem(context, store, run(store))
                TodayWidget.updateAll(context)
            } catch (e: IOException) {
                retry = true
            }
        }
        return retry
    }

    /** 어느 계정이든 아직 안 넣은 공개 코드가 있는지(알람에서 빠르게 확인할 때). */
    fun hasNewCodes(context: Context): Boolean {
        val published = publishedCodes()
        return AppSettings(context).accounts().any { hasUntried(it, published) }
    }

    /** 공개된 코드: 위키 문서와 호요랩 공식 목록, 사이트 코드 목록. 다 못 받으면 (null, 빈 목록). */
    internal fun publishedCodes(): Pair<JSONObject?, List<String>> {
        val wiki = runCatching { wikiPage() }.getOrNull()
        val official = runCatching { parseOfficialCodes(fetchPublic(OFFICIAL_URL, BBS_HEADERS)) }.getOrDefault(emptyList())
        val site = runCatching { siteCodes(fetchPublic(PublicCodes.SITE_URL)) }.getOrDefault(emptyList())
        return wiki to (official + site).distinct()
    }

    /**
     * 사이트(apps.sumi.kr) 코드 목록에서 모든 서버용 코드. 위키에 늦게 오르는 코드와 확인 안 된 코드까지 들어 있다.
     * 자동 입력은 안 되는 코드여도 한 번 실패하고 넘어가면 그만이라 다 넣어 본다. 서버가 정해진 코드는 위키에서 서버별로 고른다.
     */
    internal fun siteCodes(json: JSONObject): List<String> {
        val a = json.optJSONArray("codes") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("server").ifEmpty { "전 서버" } == "전 서버" }
            .map { it.getString("code").uppercase() }
    }

    private fun hasUntried(store: Store, published: Pair<JSONObject?, List<String>>): Boolean {
        val role = store.redeemRole ?: return false
        val (wiki, official) = published
        return untried(codeGroups(wiki, official, role.region, ServerClock.today()), store.redeemTried(role))
    }

    /** 아직 시도하지 않은 코드가 있는 묶음이 하나라도 있는지(입력할 때와 같은 기준). */
    internal fun untried(groups: List<List<String>>, tried: Set<String>) = groups.any { group -> group.any { it !in tried } }
}
