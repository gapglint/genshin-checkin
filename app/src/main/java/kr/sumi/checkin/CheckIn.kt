package kr.sumi.checkin

import android.content.Context
import java.io.IOException

object CheckIn {

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
        if (Store.loginExpiryDue(store.loginAt, store.loginExpiryNotifiedFor, ServerClock.now())) {
            Notifier.notifyLoginExpiry(context, store)
        }
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
            store.lastProblemKind = when (outcome) {
                is Outcome.LoginExpired -> "login"
                is Outcome.CaptchaRequired -> "captcha"
                else -> null
            }
            store.lastProblemDay = today
        }
        store.lastRunAt = ServerClock.now()
        Notifier.notify(context, store, outcome)
        AppWidgets.updateAll(context)
        if (outcome is Outcome.Signed || outcome is Outcome.AlreadySigned) {
            checkMakeUp(context, settings, store, client, today)
            if (settings.redeemEnabled) Redeem.scheduleSoon(context)
        }
        return outcome
    }

    /**
     * 오늘 출석을 확인한 직후, 보충 출석이 가능한지 본다. 앱 화면의 보충 카드는 알림 설정과 관계없이 보이고,
     * 알림은 켜 둔 경우에만 보낸다. 실패해도 출첵 결과에는 영향이 없다.
     */
    private fun checkMakeUp(context: Context, settings: AppSettings, store: Store, client: CheckInClient, today: String) {
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
        if (settings.makeUpAlerts) Notifier.notifyMakeUp(context, store, missed)
    }

    /** 접힌 알림 한 줄용 짧은 설명. 자세한 내용은 펼치면 [describe]로 보인다. */
    fun brief(outcome: Outcome): String = when (outcome) {
        is Outcome.Signed -> outcome.reward?.let { Text.get(R.string.core_checkin_brief_signed_reward, outcome.day, it) }
            ?: Text.get(R.string.core_checkin_brief_signed, outcome.day)
        is Outcome.AlreadySigned -> Text.get(R.string.core_checkin_brief_already, outcome.day)
        Outcome.LoginExpired -> Text.get(R.string.core_checkin_brief_login)
        Outcome.CaptchaRequired -> Text.get(R.string.core_checkin_brief_captcha)
        Outcome.NotConfirmed -> Text.get(R.string.core_checkin_brief_not_confirmed)
        is Outcome.RateLimited -> Text.get(R.string.core_checkin_brief_rate_limited)
        is Outcome.Failed -> outcome.message
    }

    fun describe(outcome: Outcome): String = when (outcome) {
        is Outcome.Signed -> outcome.reward?.let { Text.get(R.string.core_checkin_signed_reward, outcome.day, it) }
            ?: Text.get(R.string.core_checkin_signed, outcome.day)
        is Outcome.AlreadySigned -> Text.get(R.string.core_checkin_already, outcome.day)
        Outcome.LoginExpired -> Text.get(R.string.core_checkin_login_expired)
        Outcome.CaptchaRequired -> Text.get(R.string.core_checkin_captcha)
        Outcome.NotConfirmed -> Text.get(R.string.core_checkin_not_confirmed)
        is Outcome.RateLimited -> Text.get(R.string.core_checkin_rate_limited, outcome.step)
        is Outcome.Failed -> Text.get(R.string.core_checkin_failed, outcome.message)
    }

    /**
     * 자동 출석 예약. 두 겹으로 건다.
     * 1. 서버 기준으로 하루가 바뀐 직후의 알람(DailyAlarm). 주된 출석 시각이다.
     * 2. 6시간마다 확인하는 주기 작업. 알람이 절전 등으로 밀리거나 실패했을 때의 안전장치다.
     *    이미 출석했으면 아무것도 안 한다.
     * 처음 예약할 때와 restart(로그인 직후)일 때는 지금 바로 한 번 돈다.
     * 앱을 열 때·재부팅·업데이트 뒤에 부르므로, 오늘 출석을 놓친 계정이 있으면 그때도 곧 한 번 돈다.
     */
    fun schedule(context: Context, restart: Boolean = false) {
        DailyAlarm.schedule(context)
        val firstTime = Jobs.schedulePeriodic(context)
        // 처음 예약할 때와 로그인 직후에는 지금 바로 한 번 돌린다.
        if (firstTime || restart) retrySoon(context)
        // 오늘 출석을 놓쳤으면(리셋 뒤 알람 전에 앱이 업데이트되면 알람이 지워진다 등) 6시간 확인을 기다리지 않고 곧 한 번.
        // 오늘 이미 문제(로그인 만료·캡차 등)를 알렸으면 되풀이하지 않는다. 이미 기다리는 작업이 있으면 그대로 둔다.
        else if (missedToday(AppSettings(context).accounts().map { it.signedDay to it.lastProblemDay }, Store.checkInDay().toString())) {
            Jobs.scheduleOnce(context, Jobs.RETRY, replace = false)
        }
    }

    /** 계정마다 (출석한 날, 문제를 알린 날) 중 오늘 출석도 문제 알림도 없는 계정이 있는지. */
    internal fun missedToday(accounts: List<Pair<String?, String?>>, today: String) =
        accounts.any { (signed, problem) -> signed != today && problem != today }

    fun cancel(context: Context) {
        DailyAlarm.cancel(context)
        Jobs.cancel(context, Jobs.PERIODIC)
        Jobs.cancel(context, Jobs.RETRY)
    }

    /** 지금 바로(네트워크가 연결되면) 모든 계정을 한 번 출석한다. 알람 시각에 실패했을 때도 쓴다. */
    fun retrySoon(context: Context) = Jobs.scheduleOnce(context, Jobs.RETRY)

}
