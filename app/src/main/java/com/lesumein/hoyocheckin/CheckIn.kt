package com.lesumein.hoyocheckin

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit

object CheckIn {
    private const val PERIODIC = "checkin-periodic"

    /**
     * 출첵을 실행하고 결과를 저장·알림한다. 로그인 전이면 null.
     * 네트워크 오류는 IOException으로 던진다.
     * 예약 작업과 '지금 출첵'이 겹쳐도 요청이 한 번에 몰리지 않게 한 번에 하나만 돈다.
     */
    @Synchronized
    @Throws(IOException::class)
    fun run(context: Context): Outcome? {
        val store = Store(context)
        val cookie = store.cookie ?: return null
        val today = Store.checkInDay().toString()
        // 오늘 출첵을 이미 확인했으면 서버에 묻지 않는다.
        if (store.signedDay == today) return Outcome.AlreadySigned(store.signedTotal)

        val outcome = CheckInClient(cookie).run()
        when (outcome) {
            is Outcome.Signed -> { store.signedDay = today; store.signedTotal = outcome.day }
            is Outcome.AlreadySigned -> { store.signedDay = today; store.signedTotal = outcome.day }
            else -> {}
        }
        store.lastResult = describe(outcome)
        store.lastRunAt = System.currentTimeMillis()
        Notifier.notify(context, store, outcome)
        return outcome
    }

    fun describe(outcome: Outcome): String = when (outcome) {
        is Outcome.Signed -> "출첵 완료! (${outcome.day}일째)" + (outcome.reward?.let { "\n보상: $it" } ?: "")
        is Outcome.AlreadySigned -> "오늘은 이미 출첵했어요. (${outcome.day}일째)"
        Outcome.LoginExpired -> "로그인이 풀렸어요. 다시 로그인해 주세요."
        Outcome.CaptchaRequired -> "호요랩이 캡차를 요구해요. 출첵 페이지에서 직접 눌러 주세요."
        Outcome.RateLimited -> "요청이 너무 잦다고 해요. 잠시 뒤 자동으로 다시 시도해요."
        is Outcome.Failed -> "출첵 실패: ${outcome.message}"
    }

    /**
     * 6시간마다 출첵 여부를 확인한다. 이미 했으면 아무것도 안 하므로
     * 하루가 바뀐 뒤 늦어도 6시간 안에 출첵된다.
     * 주기 작업은 등록하는 순간 한 번 바로 돈다. restart면 기존 예약을 새로 걸어 지금 바로 돌린다(로그인 직후).
     */
    fun schedule(context: Context, restart: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<CheckInWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        val policy = if (restart) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, policy, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    private fun networkConstraint() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

class CheckInWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        if (CheckIn.run(applicationContext) == Outcome.RateLimited) Result.retry() else Result.success()
    } catch (e: IOException) {
        Result.retry()
    }
}
