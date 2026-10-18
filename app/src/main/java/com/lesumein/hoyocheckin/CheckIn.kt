package com.lesumein.hoyocheckin

import android.content.Context
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
    private const val NOW = "checkin-now"

    /**
     * 출첵을 실행하고 결과를 저장·알림한다. 로그인 전이면 null.
     * 네트워크 오류는 IOException으로 던진다.
     */
    @Throws(IOException::class)
    fun run(context: Context): Outcome? {
        val store = Store(context)
        val cookie = store.cookie ?: return null
        val outcome = CheckInClient(cookie).run()
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
        is Outcome.Failed -> "출첵 실패: ${outcome.message}"
    }

    /**
     * 6시간마다 출첵 여부를 확인한다. 이미 했으면 아무것도 안 하므로
     * 하루가 바뀐 뒤 늦어도 6시간 안에 출첵된다.
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CheckInWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** 로그인 직후처럼 바로 한 번 돌리고 싶을 때. */
    fun runSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<CheckInWorker>()
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    private fun networkConstraint() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

class CheckInWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        CheckIn.run(applicationContext)
        Result.success()
    } catch (e: IOException) {
        Result.retry()
    }
}
