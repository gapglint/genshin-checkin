package kr.sumi.checkin

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** 홈 화면 위젯을 한꺼번에 다시 그린다. 앱이 출석·메모·리딤·복각표를 새로 받았거나 앱을 나갈 때 부른다. */
object AppWidgets {
    fun updateAll(context: Context) {
        ResinWidget.updateAll(context)
        TodayWidget.updateAll(context)
        PickupWidget.updateAll(context)
    }

    internal fun updateEach(context: Context, provider: Class<*>, build: (Context) -> RemoteViews) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        if (ids.isEmpty()) return
        val views = build(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }

    /** 누르면 열 화면. 테스트(화면 스냅숏)에서는 null이 올 수 있어 그때는 누르기를 걸지 않는다. */
    internal fun RemoteViews.openOnClick(context: Context, intent: Intent, request: Int) {
        val pending: PendingIntent? = PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        pending?.let { setOnClickPendingIntent(android.R.id.background, it) }
    }
}

/** '오늘' 위젯에 보일 것. 화면 스냅숏 테스트에서도 같은 값으로 그린다. */
data class TodayWidgetUi(
    val who: String,
    val status: String,
    val done: Boolean,
    val days: Int,
    val resin: String,
    val redeem: String,
)

/**
 * '오늘' 위젯(2×2): 오늘 출석했는지, 이번 달 누적 일수, 레진, 마지막 리딤 결과.
 * 앱이 이미 받아 둔 정보로만 그리고 호요랩에 따로 묻지 않는다. 30분마다(레진·날짜 바뀜) 다시 그린다.
 */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)

    companion object {
        fun updateAll(context: Context) = AppWidgets.updateEach(context, TodayWidget::class.java) { views(it, state(it)) }

        internal fun state(context: Context): TodayWidgetUi {
            val settings = AppSettings(context)
            val store = settings.current()
                ?: return TodayWidgetUi("매일 출석", "로그인", false, 0, "앱에서 로그인해 주세요", "리딤 코드")
            val today = Store.checkInDay().toString()
            val sameMonth = store.signedDay?.startsWith(today.take(7)) == true
            val problem = store.lastProblem != null && store.lastProblemDay == today && !store.signedToday
            return TodayWidgetUi(
                who = store.accountRole?.nickname ?: "매일 출석",
                status = when {
                    store.signedToday -> "완료"
                    problem -> "확인 필요"
                    else -> "출석 전"
                },
                done = store.signedToday,
                days = if (sameMonth) store.signedTotal else 0,
                resin = store.note?.let { Notes.ui(it) }?.let { "${it.resin} / ${it.maxResin}" } ?: "레진 · 앱을 열면 불러와요",
                redeem = when {
                    !settings.redeemEnabled -> "리딤 자동 입력 꺼짐"
                    else -> store.lastRedeemSummary?.substringBefore(":")?.trim() ?: "리딤 코드 기다리는 중"
                },
            )
        }

        internal fun views(context: Context, s: TodayWidgetUi) = RemoteViews(context.packageName, R.layout.widget_today).apply {
            setTextViewText(R.id.todayEyebrow, s.who)
            setTextViewText(R.id.todayStatus, s.status)
            setInt(R.id.todayStatus, "setBackgroundResource", if (s.done) R.drawable.bg_widget_chip else R.drawable.bg_widget_chip_off)
            setTextColor(R.id.todayStatus, context.getColor(if (s.done) R.color.on_gold else R.color.hero_ink))
            setTextViewText(R.id.todayDays, s.days.toString())
            setTextViewText(R.id.todayResin, s.resin)
            setTextViewText(R.id.todayRedeem, s.redeem)
            with(AppWidgets) { openOnClick(context, Intent(context, MainActivity::class.java), 1) }
        }
    }
}

/**
 * '지금 픽업' 위젯(4×2): 메인 화면 복각표 카드와 같은 내용(지금 또는 다음 픽업 캐릭터, 기간, 가장 오래 기다린 캐릭터).
 * 복각표 자료는 앱이 받아 둔 것을 쓰고, 날짜가 바뀌면 남은 날이 줄어들도록 30분마다 다시 그린다.
 */
class PickupWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)

    companion object {
        private val TOKENS = listOf(
            intArrayOf(R.id.pickToken1, R.id.pickCircle1, R.id.pickIcon1, R.id.pickName1),
            intArrayOf(R.id.pickToken2, R.id.pickCircle2, R.id.pickIcon2, R.id.pickName2),
            intArrayOf(R.id.pickToken3, R.id.pickCircle3, R.id.pickIcon3, R.id.pickName3),
            intArrayOf(R.id.pickToken4, R.id.pickCircle4, R.id.pickIcon4, R.id.pickName4),
        )

        /** 홈 화면에 놓여 있는지. 놓여 있으면 앱을 안 열어도 복각표 자료를 주기적으로 새로 받는다. */
        fun installed(context: Context) =
            AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, PickupWidget::class.java))?.isNotEmpty() == true

        fun updateAll(context: Context) = AppWidgets.updateEach(context, PickupWidget::class.java) {
            views(it, Reruns.cached(it)?.card(ServerClock.today()))
        }

        internal fun views(context: Context, card: RerunCardUi?) = RemoteViews(context.packageName, R.layout.widget_pickup).apply {
            setTextViewText(R.id.pickTitle, card?.title ?: "복각표")
            setTextViewText(R.id.pickPeriod, if (card == null) "앱을 열면 불러와요" else card.period)
            val chars = card?.chars.orEmpty()
            for ((i, ids) in TOKENS.withIndex()) {
                val (token, circle, icon, name) = ids.toList()
                val c = chars.getOrNull(i)
                setViewVisibility(token, if (c == null) View.GONE else View.VISIBLE)
                if (c == null) continue
                val color = context.getColor(RerunActivity.elementColor(c.second))
                setInt(circle, "setColorFilter", color)
                RerunActivity.elementIcon(c.second)?.let { setImageViewResource(icon, it) }
                setTextViewText(name, c.first)
                setTextColor(name, RerunActivity.light(color))
            }
            setTextViewText(R.id.pickOldest, card?.oldest ?: "")
            setViewVisibility(R.id.pickOldest, if (card?.oldest == null) View.GONE else View.VISIBLE)
            with(AppWidgets) { openOnClick(context, Intent(context, RerunActivity::class.java), 2) }
        }
    }
}
