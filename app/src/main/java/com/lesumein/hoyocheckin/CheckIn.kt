package com.lesumein.hoyocheckin

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit

object CheckIn {
    private const val PERIODIC = "checkin-periodic"
    private const val RETRY = "checkin-retry"

    /** 계정을 하나씩 돌 때 사이에 쉬는 시간. 같은 기기에서 요청이 몰리지 않게 한다. */
    private const val ACCOUNT_GAP_MS = 3_000L

    /**
     * 모든 계정을 차례로 출석한다(예약 실행용). 계정마다 결과를 돌려준다.
     * 한 계정이 네트워크 오류로 실패해도 다음 계정은 진행하고, 그런 계정이 있으면 마지막에 IOException을 던진다.
     */
    @Throws(IOException::class)
    fun runAll(context: Context): List<Outcome> {
        val outcomes = mutableListOf<Outcome>()
        var networkError: IOException? = null
        for ((index, store) in AppSettings(context).accounts().withIndex()) {
            if (index > 0 && !store.signedToday) Thread.sleep(ACCOUNT_GAP_MS)
            try {
                run(context, store)?.let { outcomes += it }
            } catch (e: IOException) {
                networkError = e
            }
        }
        networkError?.let { throw it }
        return outcomes
    }

    /**
     * 한 계정을 출석하고 결과를 저장·알림한다. 로그인 정보가 없으면 null.
     * 네트워크 오류는 IOException으로 던진다.
     * 예약 작업과 '지금 출첵'이 겹쳐도 요청이 한 번에 몰리지 않게 한 번에 하나만 돈다.
     */
    @Synchronized
    @Throws(IOException::class)
    fun run(context: Context, store: Store): Outcome? {
        val cookie = store.cookie ?: return null
        // 오늘 출첵을 이미 확인했으면 서버에 묻지 않는다.
        if (store.signedToday) return Outcome.AlreadySigned(store.signedTotal)

        val client = CheckInClient(cookie)
        val outcome = client.run()
        val settings = AppSettings(context)
        settings.clockOffset = ServerClock.offsetMs
        // 서버가 알려 준 날짜가 가장 정확하다. 없으면 서버 시각으로 계산한 날짜.
        val today = client.serverToday ?: Store.checkInDay().toString()
        when (outcome) {
            is Outcome.Signed -> {
                store.signedDay = today
                store.signedTotal = outcome.day
                store.lastReward = outcome.reward
            }
            is Outcome.AlreadySigned -> {
                if (store.signedDay != today) store.lastReward = null
                store.signedDay = today
                store.signedTotal = outcome.day
            }
            else -> {}
        }
        store.lastResult = describe(outcome)
        if (outcome is Outcome.Signed || outcome is Outcome.AlreadySigned) {
            store.lastProblem = null
        } else {
            store.lastProblem = describe(outcome)
            store.lastProblemDay = today
        }
        store.lastRunAt = ServerClock.now()
        Notifier.notify(context, store, outcome)
        if (outcome is Outcome.Signed || outcome is Outcome.AlreadySigned) {
            checkMakeUp(context, settings, store, client, today)
            if (settings.redeemEnabled) Redeem.scheduleSoon(context)
        }
        return outcome
    }

    /** 오늘 출석을 확인한 직후, 하루 한 번 보충 출석이 가능한지 보고 알린다. 실패해도 출첵 결과에는 영향이 없다. */
    private fun checkMakeUp(context: Context, settings: AppSettings, store: Store, client: CheckInClient, today: String) {
        if (!settings.makeUpAlerts) return
        val missed = try {
            Thread.sleep(CheckInClient.REQUEST_GAP_MS)
            MakeUp.availableDays(client)
        } catch (e: IOException) {
            null
        }
        if (missed == null) {
            store.makeUpDay = null
            return
        }
        store.makeUpDay = today
        store.makeUpMissed = missed
        Notifier.notifyMakeUp(context, store, missed)
    }

    fun describe(outcome: Outcome): String = when (outcome) {
        is Outcome.Signed -> "출첵 완료! (${outcome.day}일째)" + (outcome.reward?.let { "\n보상: $it" } ?: "")
        is Outcome.AlreadySigned -> "오늘은 이미 출첵했어요. (${outcome.day}일째)"
        Outcome.LoginExpired -> "로그인이 풀렸어요. 다시 로그인해 주세요."
        Outcome.CaptchaRequired -> "호요랩이 캡차를 요구해요. 출첵 페이지에서 직접 눌러 주세요."
        Outcome.NotConfirmed -> "출석 응답은 성공이었지만 확인해 보니 반영되지 않았어요. 출첵 페이지에서 직접 확인해 주세요."
        is Outcome.RateLimited -> "호요랩이 요청이 너무 잦다고 해요(${outcome.step} 단계). 잠시 뒤 자동으로 다시 시도해요."
        is Outcome.Failed -> "출첵 실패: ${outcome.message}"
    }

    /**
     * 자동 출석 예약. 두 겹으로 건다.
     * 1. 서버 기준으로 하루가 바뀐 직후의 알람(DailyAlarm). 주된 출석 시각이다.
     * 2. 6시간마다 확인하는 주기 작업. 알람이 절전 등으로 밀리거나 실패했을 때의 안전장치다.
     *    이미 출석했으면 아무것도 안 한다. 등록하는 순간 한 번 바로 돌고,
     *    restart면 기존 예약을 새로 걸어 지금 바로 돌린다(로그인 직후).
     */
    fun schedule(context: Context, restart: Boolean = false) {
        DailyAlarm.schedule(context)
        val request = PeriodicWorkRequestBuilder<CheckInWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        val policy = if (restart) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, policy, request)
    }

    fun cancel(context: Context) {
        DailyAlarm.cancel(context)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(RETRY)
    }

    /** 알람 시각의 출석이 네트워크 문제로 실패했을 때, 연결되면 다시 시도한다. */
    fun retrySoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<CheckInWorker>()
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY, ExistingWorkPolicy.REPLACE, request)
    }

    private fun networkConstraint() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

class CheckInWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // 재부팅 등으로 알람이 사라졌을 수 있으니 주기 작업이 돌 때마다 다시 건다.
        if (AppSettings(applicationContext).hasAccounts) DailyAlarm.schedule(applicationContext)
        return try {
            if (CheckIn.runAll(applicationContext).any { it is Outcome.RateLimited }) Result.retry() else Result.success()
        } catch (e: IOException) {
            Result.retry()
        }
    }
}
