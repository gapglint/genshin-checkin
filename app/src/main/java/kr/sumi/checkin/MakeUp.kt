package kr.sumi.checkin

import android.content.Context
import org.json.JSONObject
import java.io.IOException

/** 보충 출석 현황. resign_info 응답의 data. */
data class ResignInfo(
    val missed: Int,
    val cards: Int,
    val cost: Int,
    val dailyUsed: Int,
    val dailyLimit: Int,
    val monthlyUsed: Int,
    val monthlyLimit: Int,
) {
    val dailyLeft get() = (dailyLimit - dailyUsed).coerceAtLeast(0)
    val monthlyLeft get() = (monthlyLimit - monthlyUsed).coerceAtLeast(0)
    /** 놓친 날이 있고 오늘·이번 달 횟수가 남았는지. */
    val canResign get() = missed > 0 && dailyLeft > 0 && monthlyLeft > 0
    val hasCards get() = cards >= cost
}

/** 보충권을 주는 임무. status는 TT_Ready(진행 전) / TT_Done(받기 가능) / TT_Award(받음). */
data class MakeUpTask(val id: Int, val status: String, val type: String, val name: String) {
    val claimable get() = status == "TT_Done"
    /** 링크만 열면 끝나는 임무. 앱이 대신 완료할 수 있다. */
    val autoCompletable get() = status == "TT_Ready" && type == "TT_JUMP"
}

sealed class MakeUpResult {
    data class Done(val days: Int, val rewards: List<String>) : MakeUpResult()
    /** 보충할 게 없거나 할 수 없는 이유. */
    data class Unavailable(val reason: String) : MakeUpResult()
    object LoginExpired : MakeUpResult()
    object RateLimited : MakeUpResult()
    data class Failed(val message: String) : MakeUpResult()
}

/**
 * 보충 출석: 놓친 날을 보충권으로 채운다.
 * 보충권은 출석 페이지의 임무로 얻는데, 링크 방문 임무는 완료 처리만 하면 받을 수 있다.
 * 호요랩은 오늘 출석을 먼저 해야 보충을 허용한다.
 */
object MakeUp {

    /** 지금 보충할 수 있는지 확인한다(요청 1~2번). 알림을 띄울지 정할 때 쓴다. 할 수 있으면 놓친 날 수. */
    @Throws(IOException::class)
    fun availableDays(client: CheckInClient): Int? {
        val info = parseInfo(client.resignInfo()) ?: return null
        if (!info.canResign) return null
        if (info.hasCards) return info.missed
        Thread.sleep(CheckInClient.REQUEST_GAP_MS)
        val earnable = parseTasks(client.taskList()).any { it.claimable || it.autoCompletable }
        return if (earnable) info.missed else null
    }

    /**
     * 한 계정의 보충을 할 수 있는 만큼 한다. 출첵과 겹치지 않게 같은 잠금을 쓴다.
     * progress로 지금 하는 단계를 알려 준다(보충권 받기, 몇 번째 보충 중).
     */
    @Throws(IOException::class)
    fun perform(store: Store, progress: (String) -> Unit = {}): MakeUpResult = synchronized(CheckIn) {
        val cookie = store.cookie ?: return MakeUpResult.LoginExpired
        if (!store.signedToday) {
            return MakeUpResult.Unavailable("오늘 출석을 먼저 해야 보충할 수 있어요.")
        }
        val client = CheckInClient(cookie)
        val result = performWith(client, progress)
        store.makeUpDay = null
        if (result is MakeUpResult.Done) store.signedTotal += result.days
        result
    }

    private fun performWith(client: CheckInClient, progress: (String) -> Unit): MakeUpResult {
        var info = fetchInfo(client) ?: return MakeUpResult.Failed("보충 출석 정보를 받지 못했어요.")
        infoProblem(info)?.let { return it }

        val rewards = mutableListOf<String>()
        while (info.canResign) {
            if (!info.hasCards) {
                progress("보충권 받는 중...")
                earnCards(client)?.let { return finish(rewards, it) }
                info = fetchInfo(client) ?: break
                if (!info.hasCards) return finish(rewards, MakeUpResult.Unavailable("보충권이 부족해요. 오늘 받을 수 있는 임무를 다 했어요."))
            }
            progress("보충 출석 중... (${rewards.size + 1}번째)")
            pause()
            val json = client.resign()
            when (val code = json.optInt("retcode", Int.MIN_VALUE)) {
                0 -> rewards += rewardOf(json) ?: "보상"
                in CheckInClient.NOT_LOGGED_IN -> return finish(rewards, MakeUpResult.LoginExpired)
                CheckInClient.TOO_FREQUENT -> return finish(rewards, MakeUpResult.RateLimited)
                else -> return finish(rewards, MakeUpResult.Failed("${json.optString("message")} (retcode $code)"))
            }
            info = fetchInfo(client) ?: break
        }
        return finish(rewards, infoProblem(info) ?: MakeUpResult.Unavailable("보충할 날이 더 없어요."))
    }

    /** 이미 몇 번 보충했으면 그 결과를, 아니면 멈춘 이유를 돌려준다. */
    private fun finish(rewards: List<String>, stop: MakeUpResult) =
        if (rewards.isEmpty()) stop else MakeUpResult.Done(rewards.size, rewards)

    private fun infoProblem(info: ResignInfo): MakeUpResult? = when {
        info.missed == 0 -> MakeUpResult.Unavailable("이번 달에 놓친 날이 없어요.")
        info.monthlyLeft == 0 -> MakeUpResult.Unavailable("이번 달 보충 횟수(${info.monthlyLimit}번)를 다 썼어요.")
        info.dailyLeft == 0 -> MakeUpResult.Unavailable("오늘 보충 횟수(${info.dailyLimit}번)를 다 썼어요. 내일 다시 할 수 있어요.")
        else -> null
    }

    /** 받을 수 있는 보충권을 받고, 링크 방문 임무는 완료 처리 후 받는다. 막히면 그 결과. */
    private fun earnCards(client: CheckInClient): MakeUpResult? {
        pause()
        for (task in parseTasks(client.taskList())) {
            if (task.autoCompletable) {
                pause()
                stopReason(client.completeTask(task.id))?.let { return it }
            }
            if (task.claimable || task.autoCompletable) {
                pause()
                stopReason(client.claimTask(task.id))?.let { return it }
            }
        }
        return null
    }

    /** 계속 진행하면 안 되는 응답(로그인 만료, 요청 과다)이면 그 결과. */
    private fun stopReason(json: JSONObject): MakeUpResult? = when (json.optInt("retcode")) {
        in CheckInClient.NOT_LOGGED_IN -> MakeUpResult.LoginExpired
        CheckInClient.TOO_FREQUENT -> MakeUpResult.RateLimited
        else -> null
    }

    private fun fetchInfo(client: CheckInClient): ResignInfo? {
        pause()
        return parseInfo(client.resignInfo())
    }

    private fun pause() = Thread.sleep(CheckInClient.REQUEST_GAP_MS)

    fun describe(result: MakeUpResult): String = when (result) {
        is MakeUpResult.Done -> "${result.days}일 보충했어요.\n보상: ${result.rewards.joinToString(", ")}"
        is MakeUpResult.Unavailable -> result.reason
        MakeUpResult.LoginExpired -> "로그인이 풀렸어요. 다시 로그인해 주세요."
        MakeUpResult.RateLimited -> "요청이 너무 잦다고 해요. 잠시 뒤 다시 시도해 주세요."
        is MakeUpResult.Failed -> "보충 출석 실패: ${result.message}"
    }

    internal fun parseInfo(json: JSONObject): ResignInfo? {
        if (json.optInt("retcode", -1) != 0) return null
        val d = json.optJSONObject("data") ?: return null
        return ResignInfo(
            missed = d.optInt("sign_cnt_missed"),
            cards = d.optInt("quality_cnt"),
            cost = d.optInt("cost", 1).coerceAtLeast(1),
            dailyUsed = d.optInt("resign_cnt_daily"),
            dailyLimit = d.optInt("resign_limit_daily"),
            monthlyUsed = d.optInt("resign_cnt_monthly"),
            monthlyLimit = d.optInt("resign_limit_monthly"),
        )
    }

    internal fun parseTasks(json: JSONObject): List<MakeUpTask> {
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }.map {
            MakeUpTask(it.optInt("id"), it.optString("status"), it.optString("type"), it.optString("name"))
        }
    }

    /** 보충 응답의 보상 이름. 응답 모양이 공개돼 있지 않아 흔한 모양 몇 가지를 본다. */
    internal fun rewardOf(json: JSONObject): String? {
        val d = json.optJSONObject("data") ?: return null
        val award = d.optJSONObject("award") ?: d.optJSONArray("awards")?.optJSONObject(0) ?: d
        val name = award.optString("name").takeIf { it.isNotEmpty() } ?: return null
        val cnt = award.optInt("cnt")
        return if (cnt > 0) "$name x$cnt" else name
    }
}
