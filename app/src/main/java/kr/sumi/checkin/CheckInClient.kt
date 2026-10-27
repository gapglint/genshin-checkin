package kr.sumi.checkin

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 출첵 한 번의 결과. */
sealed class Outcome {
    data class Signed(val day: Int, val reward: String?) : Outcome()
    data class AlreadySigned(val day: Int) : Outcome()
    /** 쿠키가 만료됐거나 잘못됨. 다시 로그인해야 한다. */
    object LoginExpired : Outcome()
    /** 호요랩이 출첵 요청에 캡차를 걸었다. 출첵 페이지에서 직접 눌러야 한다. */
    object CaptchaRequired : Outcome()
    /** 출석 성공 응답을 받았는데 다시 확인해 보니 반영되지 않았다. 직접 확인해야 한다. */
    object NotConfirmed : Outcome()
    /** 요청이 너무 잦다(-1004). 잠시 뒤 다시 시도한다. step은 어느 요청에서 막혔는지(원인 파악용). */
    data class RateLimited(val step: String) : Outcome()
    data class Failed(val message: String) : Outcome()
}

/** 원신 호요랩 출석체크 API. 네트워크 호출이므로 백그라운드 스레드에서 부른다. */
class CheckInClient(private val cookie: String) {

    /** 서버가 알려 준 오늘 날짜(info 응답의 today). 응답에 없으면 null. */
    var serverToday: String? = null
        private set

    /**
     * 오늘 출첵이 안 돼 있으면 출첵한다. 네트워크 오류는 IOException으로 던진다.
     * 캡차가 걸리면 응답의 challenge로 한 번 더 시도하고, 성공하면 다시 조회해 실제로 반영됐는지 확인한다.
     */
    @Throws(IOException::class)
    fun run(): Outcome {
        val info = fetchInfo()
        infoError(info)?.let { return it }
        val data = info.getJSONObject("data")
        if (data.optBoolean("is_sign")) {
            return Outcome.AlreadySigned(data.optInt("total_sign_day"))
        }

        val day = data.optInt("total_sign_day") + 1
        Thread.sleep(REQUEST_GAP_MS)
        var sign = sign()
        var outcome = interpretSign(sign, day) { null }
        if (outcome == Outcome.CaptchaRequired) {
            val challenge = challengeOf(sign) ?: return outcome
            Thread.sleep(REQUEST_GAP_MS)
            sign = sign(challenge)
            outcome = interpretSign(sign, day) { null }
        }
        if (outcome !is Outcome.Signed) return outcome
        return confirm(day)
    }

    /** 출석 후 다시 조회해 반영됐는지 본다. 반영됐으면 보상 이름까지 붙여 돌려준다. */
    private fun confirm(day: Int): Outcome {
        Thread.sleep(REQUEST_GAP_MS)
        val check = fetchInfo()
        val data = check.optJSONObject("data")
        if (infoError(check) != null || data == null) return Outcome.Signed(day, rewardFor(day))
        if (!data.optBoolean("is_sign")) return Outcome.NotConfirmed
        val total = data.optInt("total_sign_day", day)
        return Outcome.Signed(total, rewardFor(total))
    }

    private fun fetchInfo(): JSONObject {
        val info = request("GET", "$API/info?lang=$LANG&act_id=$ACT_ID")
        info.optJSONObject("data")?.optString("today")?.takeIf { it.isNotEmpty() }?.let { serverToday = it }
        return info
    }

    private fun sign(challenge: String? = null): JSONObject = request(
        "POST", "$API/sign?lang=$LANG", JSONObject().put("act_id", ACT_ID).toString(),
        challenge?.let { mapOf("x-rpc-challenge" to it) } ?: emptyMap(),
    )

    /** 이 쿠키로 로그인된 상태인지. 만료됐을 때만 false. 네트워크 오류는 IOException으로 던진다. */
    @Throws(IOException::class)
    fun isLoggedIn(): Boolean =
        infoError(request("GET", "$API/info?lang=$LANG&act_id=$ACT_ID")) != Outcome.LoginExpired

    /** 오늘 출석 여부·누적 출석·놓친 날. */
    @Throws(IOException::class)
    fun info(): JSONObject = fetchInfo()

    /** 이번 달 출석 보상 목록(출석 횟수 순서). */
    @Throws(IOException::class)
    fun home(): JSONObject = request("GET", "$API/home?lang=$LANG&act_id=$ACT_ID")

    @Throws(IOException::class)
    fun resignInfo(): JSONObject = request("GET", "$API/resign_info?lang=$LANG&act_id=$ACT_ID")

    @Throws(IOException::class)
    fun taskList(): JSONObject = request("GET", "$API/task/list?lang=$LANG&act_id=$ACT_ID")

    /** 링크 방문 임무를 완료로 표시한다. */
    @Throws(IOException::class)
    fun completeTask(id: Int): JSONObject = request("POST", "$API/task/complete?lang=$LANG", taskBody(id))

    /** 완료한 임무의 보충권을 받는다. */
    @Throws(IOException::class)
    fun claimTask(id: Int): JSONObject = request("POST", "$API/task/award?lang=$LANG", taskBody(id))

    /** 놓친 날 하나를 보충권으로 채운다. */
    @Throws(IOException::class)
    fun resign(): JSONObject =
        request("POST", "$API/resign?lang=$LANG", JSONObject().put("act_id", ACT_ID).put("lang", LANG).toString())

    /** 출석·보충으로 받은 보상 내역(최신순). page는 1부터. */
    @Throws(IOException::class)
    fun awardHistory(page: Int, size: Int): JSONObject =
        request("GET", "$API/award?act_id=$ACT_ID&current_page=$page&page_size=$size&lang=$LANG")

    private fun taskBody(id: Int) =
        JSONObject().put("id", id).put("act_id", ACT_ID).put("lang", LANG).toString()

    /** 누적 출첵 일수에 해당하는 보상 이름. 실패해도 출첵 결과에는 영향이 없다. */
    private fun rewardFor(day: Int): String? = try {
        Thread.sleep(REQUEST_GAP_MS)
        val home = request("GET", "$API/home?lang=$LANG&act_id=$ACT_ID")
        rewardName(home, day)
    } catch (e: Exception) {
        null
    }

    /** 출석 외 호요랩 API(리딤 등)도 같은 쿠키·헤더·서버 시각 기록을 쓰도록 연다. */
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap()): JSONObject = request("GET", url, null, headers)

    @Throws(IOException::class)
    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): JSONObject = request("POST", url, body, headers)

    private fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Origin", "https://act.hoyolab.com")
            conn.setRequestProperty("Referer", "https://act.hoyolab.com/")
            conn.setRequestProperty("x-rpc-signgame", "hk4e")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            ServerClock.observe(conn.getHeaderFieldDate("Date", 0))
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                ?: throw IOException("HTTP ${conn.responseCode}")
            val text = stream.bufferedReader().use { it.readText() }
            // 와이파이 로그인 화면·서버 오류 화면처럼 JSON이 아닌 응답은 네트워크 오류로 다룬다(다시 시도하게, 앱이 죽지 않게).
            return try { JSONObject(text) } catch (e: org.json.JSONException) { throw IOException("HTTP ${conn.responseCode}: 응답을 읽을 수 없어요") }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val ACT_ID = "e202102251931481"
        const val LANG = "ko-kr"
        private const val API = "https://sg-hk4e-api.hoyolab.com/event/sol"
        const val CHECK_IN_PAGE =
            "https://act.hoyolab.com/ys/event/signin-sea-v3/index.html?act_id=$ACT_ID&lang=$LANG"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        internal val NOT_LOGGED_IN = setOf(-100, -101)
        internal const val TOO_FREQUENT = -1004
        /** 브라우저처럼 요청 사이에 잠깐 쉰다. */
        const val REQUEST_GAP_MS = 1000L

        /** info 응답이 정상이 아니면 그에 맞는 결과, 정상이면 null. */
        internal fun infoError(json: JSONObject): Outcome? {
            val code = json.optInt("retcode", Int.MIN_VALUE)
            return when {
                code in NOT_LOGGED_IN -> Outcome.LoginExpired
                code == TOO_FREQUENT -> Outcome.RateLimited("출석 확인")
                code != 0 || json.optJSONObject("data") == null -> Outcome.Failed(describe(json))
                else -> null
            }
        }

        internal fun interpretSign(json: JSONObject, day: Int, reward: () -> String?): Outcome {
            return when (val code = json.optInt("retcode", Int.MIN_VALUE)) {
                0 -> {
                    // 성공 코드여도 gt_result에 위험 판정이 있으면 캡차를 풀어야 실제로 출첵된다.
                    val gt = json.optJSONObject("data")?.optJSONObject("gt_result")
                    val risky = gt != null && (gt.optBoolean("is_risk") || gt.optInt("risk_code") != 0)
                    if (risky) Outcome.CaptchaRequired else Outcome.Signed(day, reward())
                }
                -5003 -> Outcome.AlreadySigned(day - 1)
                in NOT_LOGGED_IN -> Outcome.LoginExpired
                TOO_FREQUENT -> Outcome.RateLimited("출석")
                else -> Outcome.Failed(describe(json).ifEmpty { "retcode $code" })
            }
        }

        /** 캡차 판정 응답의 challenge. 없으면 null. */
        internal fun challengeOf(json: JSONObject): String? =
            json.optJSONObject("data")?.optJSONObject("gt_result")?.optString("challenge")?.takeIf { it.isNotEmpty() }

        internal fun rewardName(home: JSONObject, day: Int): String? {
            val awards = home.optJSONObject("data")?.optJSONArray("awards") ?: return null
            if (day < 1 || day > awards.length()) return null
            val award = awards.getJSONObject(day - 1)
            return "${award.optString("name")} x${award.optInt("cnt")}"
        }

        private fun describe(json: JSONObject) =
            "${json.optString("message")} (retcode ${json.opt("retcode")})"
    }
}
