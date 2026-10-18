package com.lesumein.hoyocheckin

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 리딤 코드를 받을 게임 캐릭터. 출석과 달리 리딤 보상은 서버·UID 하나로만 간다. */
data class GameRole(val uid: String, val region: String, val regionName: String, val nickname: String, val level: Int) {
    fun label() = "$regionName · $nickname (Lv.$level) · UID $uid"
}

/** 코드 하나를 입력한 결과. */
enum class RedeemStatus { REDEEMED, USED, EXPIRED, INVALID, NOT_LOGGED_IN, TOO_FAST, OTHER }

data class RedeemAttempt(val code: String, val status: RedeemStatus, val message: String)

data class RedeemReport(val found: Int, val attempts: List<RedeemAttempt>, val stopReason: String? = null) {
    val redeemed get() = attempts.filter { it.status == RedeemStatus.REDEEMED }.map { it.code }
}

/**
 * 리딤 코드 자동 입력.
 * 코드는 두 곳에서 찾는다: 호요랩 원신 홈의 공식 코드 목록(방송 코드 등, 있을 때만)과
 * 호요랩 커뮤니티 글의 '보상 받기' 링크(genshin.hoyoverse.com/.../gift?code=...).
 * 한 번 입력해 본 코드는 기억해 두고 다시 입력하지 않는다. 입력 사이에는 호요랩 제한대로 6초씩 쉰다.
 */
object Redeem {
    private const val BBS = "https://bbs-api-os.hoyolab.com"
    private const val GAME_BIZ = "hk4e_global"
    private const val CODE_GAP_MS = 6_000L
    /** 이보다 오래된 커뮤니티 글은 보지 않는다. 코드는 보통 며칠~몇 주면 만료된다. */
    private const val POST_MAX_AGE_SEC = 14L * 24 * 3600
    private const val MAX_POSTS = 6
    private val CODE_IN_LINK = Regex("""gift\?code=([A-Za-z0-9]{6,20})""")
    private val BBS_HEADERS = mapOf("x-rpc-client_type" to "4", "x-rpc-app_version" to "2.50.0", "x-rpc-language" to "ko-kr")

    /** 이 계정의 원신 캐릭터 목록(서버별). */
    @Throws(IOException::class)
    fun roles(client: CheckInClient): List<GameRole> =
        parseRoles(client.get("https://api-account-os.hoyolab.com/binding/api/getUserGameRolesByCookie?game_biz=$GAME_BIZ"))

    /** 새 코드를 찾아 고른 캐릭터로 입력한다. 수동 실행과 예약 실행이 겹치지 않게 한 번에 하나만. */
    @Throws(IOException::class)
    fun run(context: Context): RedeemReport = synchronized(this) {
        val store = Store(context)
        val client = CheckInClient(store.cookie ?: return RedeemReport(0, emptyList(), "로그인이 필요해요."))
        val role = store.redeemRole ?: chooseOnlyRole(client, store)
            ?: return RedeemReport(0, emptyList(), "리딤 보상을 받을 캐릭터를 골라 주세요.")

        val tried = store.redeemTried
        val codes = findCodes(client).filter { it !in tried }
        val attempts = mutableListOf<RedeemAttempt>()
        var stop: String? = null
        for (code in codes) {
            if (attempts.isNotEmpty()) Thread.sleep(CODE_GAP_MS)
            var attempt = redeem(client, role, code)
            if (attempt.status == RedeemStatus.TOO_FAST) {
                Thread.sleep(CODE_GAP_MS)
                attempt = redeem(client, role, code)
            }
            attempts += attempt
            if (attempt.status == RedeemStatus.NOT_LOGGED_IN) {
                stop = "리딤 코드 입력용 로그인 정보가 없어요. '로그인 상태 확인'에서 다른 계정으로 로그인을 눌러 다시 로그인해 주세요."
                break
            }
            if (attempt.status != RedeemStatus.TOO_FAST) store.redeemTried = store.redeemTried + code
        }
        val report = RedeemReport(codes.size, attempts, stop)
        store.lastRedeemAt = ServerClock.now()
        store.lastRedeemSummary = summary(report)
        report
    }

    /** 캐릭터가 하나뿐이면 묻지 않고 그 캐릭터로 정한다. */
    private fun chooseOnlyRole(client: CheckInClient, store: Store): GameRole? {
        val roles = roles(client)
        if (roles.size != 1) return null
        store.redeemRole = roles.single()
        return roles.single()
    }

    /** 공식 목록 + 최근 커뮤니티 글에서 코드를 모은다. 한쪽이 실패해도 다른 쪽은 쓴다. */
    private fun findCodes(client: CheckInClient): List<String> {
        val codes = linkedSetOf<String>()
        try {
            codes += parseOfficialCodes(client.get("$BBS/community/painter/wapi/circle/channel/guide/material?game_id=2", BBS_HEADERS))
        } catch (e: Exception) {
        }
        try {
            val keyword = URLEncoder.encode("리딤코드", "UTF-8")
            val search = client.get("$BBS/community/search/wapi/search/post?keyword=$keyword&size=20&game_id=2", BBS_HEADERS)
            val since = ServerClock.now() / 1000 - POST_MAX_AGE_SEC
            for (postId in recentPostIds(search, since).take(MAX_POSTS)) {
                Thread.sleep(CheckInClient.REQUEST_GAP_MS)
                codes += parseCodesInPost(client.get("$BBS/community/post/wapi/getPostFull?post_id=$postId", BBS_HEADERS))
            }
        } catch (e: Exception) {
        }
        return codes.toList()
    }

    private fun redeem(client: CheckInClient, role: GameRole, code: String): RedeemAttempt {
        val url = "https://sg-hk4e-api.hoyoverse.com/common/apicdkey/api/webExchangeCdkeyHyl" +
            "?cdkey=$code&game_biz=$GAME_BIZ&lang=ko&region=${role.region}&uid=${role.uid}&t=${ServerClock.now()}"
        val json = client.get(url, mapOf("Origin" to "https://www.hoyolab.com", "Referer" to "https://www.hoyolab.com/"))
        return RedeemAttempt(code, statusOf(json.optInt("retcode", Int.MIN_VALUE)), json.optString("message"))
    }

    fun summary(report: RedeemReport): String = when {
        report.stopReason != null && report.attempts.none { it.status == RedeemStatus.REDEEMED } -> report.stopReason
        report.redeemed.isNotEmpty() -> "새 코드 ${report.redeemed.size}개 입력: ${report.redeemed.joinToString(", ")}"
        report.attempts.isEmpty() -> "새 코드 없음"
        else -> "새 코드 ${report.attempts.size}개를 확인했지만 받을 수 있는 게 없었어요"
    }

    /** 결과 창에 보일 자세한 내용. */
    fun details(report: RedeemReport): String {
        if (report.attempts.isEmpty()) return report.stopReason ?: "새로 올라온 코드가 없어요. 이미 입력한 코드는 다시 입력하지 않아요."
        val lines = report.attempts.joinToString("\n") { "${it.code}  ${statusText(it.status)}" }
        val tail = report.redeemed.takeIf { it.isNotEmpty() }?.let { "\n\n보상은 게임 우편함으로 와요." } ?: ""
        return (report.stopReason?.let { "$it\n\n" } ?: "") + lines + tail
    }

    private fun statusText(status: RedeemStatus) = when (status) {
        RedeemStatus.REDEEMED -> "받음"
        RedeemStatus.USED -> "이미 받은 코드"
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

    /** 출석이 끝난 뒤 따로 돌린다. 코드 사이 6초 대기 때문에 알람 처리 시간 안에 넣지 않는다. */
    fun scheduleSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<RedeemWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("redeem", ExistingWorkPolicy.KEEP, request)
    }
}

class RedeemWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val store = Store(applicationContext)
        if (!store.redeemEnabled || store.cookie == null) return Result.success()
        // 하루 한 번이면 충분하다.
        if (store.lastRedeemAt > 0 && ServerClock.dayOf(store.lastRedeemAt) == ServerClock.today()) return Result.success()
        return try {
            Notifier.notifyRedeem(applicationContext, Redeem.run(applicationContext))
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        }
    }
}
