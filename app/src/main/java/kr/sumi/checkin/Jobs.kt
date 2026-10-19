package kr.sumi.checkin

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.io.IOException

/**
 * 백그라운드 작업 예약. 안드로이드 기본 JobScheduler를 쓴다(별도 라이브러리 없이).
 * 모두 네트워크가 연결됐을 때만 돌고, 재부팅 뒤에도 유지되며, 실패하면 간격을 늘려 다시 시도한다.
 */
object Jobs {
    // 예전 버전(WorkManager)이 남긴 작업 번호와 겹치지 않게 큰 번호를 쓴다.
    /** 6시간마다 출석 확인. 새벽 1시 알람이 밀리거나 실패했을 때의 안전장치. */
    const val PERIODIC = 0x6C0001
    /** 지금 바로 한 번 출석(로그인 직후, 알람 시각 실패 후). */
    const val RETRY = 0x6C0002
    /** 출석 뒤 리딤 코드 입력. */
    const val REDEEM = 0x6C0003

    private const val PERIOD_MS = 6 * 60 * 60_000L

    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)

    private fun builder(context: Context, id: Int, needsNetwork: Boolean = true) =
        JobInfo.Builder(id, ComponentName(context, CheckInJobService::class.java))
            .setRequiredNetworkType(if (needsNetwork) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_NONE)
            .setPersisted(true)

    /**
     * 예약은 앱을 멈추게 해서는 안 된다. 시스템이 네트워크 조건을 거부하면(권한 문제 등) 조건 없이 다시 건다.
     * 네트워크가 없을 때 돌면 작업이 IOException으로 다시 시도하므로 결과는 같다.
     */
    private fun schedule(context: Context, id: Int, configure: JobInfo.Builder.() -> JobInfo.Builder) {
        try {
            scheduler(context).schedule(builder(context, id).configure().build())
        } catch (e: SecurityException) {
            try {
                scheduler(context).schedule(builder(context, id, needsNetwork = false).configure().build())
            } catch (e: RuntimeException) {
            }
        } catch (e: IllegalStateException) {
            // 예약 개수 제한 등. 다음에 앱을 열거나 알람이 울릴 때 다시 건다.
        }
    }

    /** 6시간 주기 작업을 건다. 이미 있으면 그대로 두고 false, 새로 걸었으면 true. */
    fun schedulePeriodic(context: Context): Boolean {
        if (scheduler(context).getPendingJob(PERIODIC) != null) return false
        schedule(context, PERIODIC) {
            setPeriodic(PERIOD_MS).setBackoffCriteria(15 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        }
        return true
    }

    /** 한 번만 도는 작업. replace가 false면 이미 기다리는 같은 작업이 있을 때 그대로 둔다. */
    fun scheduleOnce(context: Context, id: Int, delayMs: Long = 0, replace: Boolean = true) {
        if (!replace && scheduler(context).getPendingJob(id) != null) return
        schedule(context, id) {
            setMinimumLatency(delayMs).setBackoffCriteria(5 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        }
    }

    fun cancel(context: Context, id: Int) = scheduler(context).cancel(id)

    /** 예전 버전의 WorkManager가 걸어 둔 작업(이제 없는 서비스를 가리킴)을 지운다. */
    fun removeLegacy(context: Context) {
        val ours = CheckInJobService::class.java.name
        for (job in scheduler(context).allPendingJobs) {
            if (job.service.className != ours) scheduler(context).cancel(job.id)
        }
    }
}

/** 예약 작업을 실제로 실행한다. 메인 스레드에서 불리므로 별도 스레드에서 돌리고 끝나면 알린다. */
class CheckInJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            val retry = try {
                when (params.jobId) {
                    Jobs.REDEEM -> Redeem.runScheduled(app)
                    else -> {
                        // 재부팅 등으로 알람이 사라졌을 수 있으니 출석 작업이 돌 때마다 다시 건다.
                        if (AppSettings(app).hasAccounts) DailyAlarm.schedule(app)
                        val limited = CheckIn.runAll(app).any { it is Outcome.RateLimited }
                        // 6시간 주기 작업에 실시간 메모 확인도 얹는다. 알람이 밀렸어도 여기서 다시 잡힌다.
                        if (params.jobId == Jobs.PERIODIC) Notes.checkAll(app)
                        limited
                    }
                }
            } catch (e: IOException) {
                true
            }
            jobFinished(params, retry)
        }.start()
        return true
    }

    /** 시스템이 중간에 멈추면 다시 시도하게 한다. */
    override fun onStopJob(params: JobParameters): Boolean = true
}
