package com.lesumein.hoyocheckin

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
    /** 요청이 너무 잦다(-1004). 잠시 뒤 다시 시도한다. */
    object RateLimited : Outcome()
    data class Failed(val message: String) : Outcome()
}

/** 원신 호요랩 출석체크 API. 네트워크 호출이므로 백그라운드 스레드에서 부른다. */
class CheckInClient(private val cookie: String) {

    /** 오늘 출첵이 안 돼 있으면 출첵한다. 네트워크 오류는 IOException으로 던진다. */
    @Throws(IOException::class)
    fun run(): Outcome {
        val info = request("GET", "$API/info?lang=$LANG&act_id=$ACT_ID")
        infoError(info)?.let { return it }
        val data = info.getJSONObject("data")
        if (data.optBoolean("is_sign")) {
            return Outcome.AlreadySigned(data.optInt("total_sign_day"))
        }

        Thread.sleep(REQUEST_GAP_MS)
        val sign = request("POST", "$API/sign?lang=$LANG", JSONObject().put("act_id", ACT_ID).toString())
        val day = data.optInt("total_sign_day") + 1
        return interpretSign(sign, day) { rewardFor(day) }
    }

    /** 누적 출첵 일수에 해당하는 보상 이름. 실패해도 출첵 결과에는 영향이 없다. */
    private fun rewardFor(day: Int): String? = try {
        Thread.sleep(REQUEST_GAP_MS)
        val home = request("GET", "$API/home?lang=$LANG&act_id=$ACT_ID")
        rewardName(home, day)
    } catch (e: Exception) {
        null
    }

    private fun request(method: String, url: String, body: String? = null): JSONObject {
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
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                ?: throw IOException("HTTP ${conn.responseCode}")
            return JSONObject(stream.bufferedReader().use { it.readText() })
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

        private val NOT_LOGGED_IN = setOf(-100, -101)
        private const val TOO_FREQUENT = -1004
        /** 브라우저처럼 요청 사이에 잠깐 쉰다. */
        private const val REQUEST_GAP_MS = 1000L

        /** info 응답이 정상이 아니면 그에 맞는 결과, 정상이면 null. */
        internal fun infoError(json: JSONObject): Outcome? {
            val code = json.optInt("retcode", Int.MIN_VALUE)
            return when {
                code in NOT_LOGGED_IN -> Outcome.LoginExpired
                code == TOO_FREQUENT -> Outcome.RateLimited
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
                TOO_FREQUENT -> Outcome.RateLimited
                else -> Outcome.Failed(describe(json).ifEmpty { "retcode $code" })
            }
        }

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
