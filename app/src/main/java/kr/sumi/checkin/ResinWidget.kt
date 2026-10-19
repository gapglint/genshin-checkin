package kr.sumi.checkin

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/**
 * 홈 화면 레진 위젯. 호요랩에 따로 묻지 않고, 앱이 받아 둔 실시간 메모로 지금 레진을 계산해 보여 준다.
 * 시스템이 30분마다 다시 그리게 하고(레진 수만 바뀐다), 앱이 메모를 새로 받거나 앱을 나갈 때도 바로 다시 그린다.
 * 누르면 앱이 열리고, 앱은 5분 넘은 메모면 새로 받아 온다.
 */
class ResinWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(context, manager, id)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        update(context, manager, id)

    companion object {
        /** 이 폭(dp) 이상이면 파견·주전자·의뢰도 보인다. */
        private const val WIDE_DP = 250

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            for (id in manager.getAppWidgetIds(ComponentName(context, ResinWidget::class.java))) update(context, manager, id)
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_resin)
            val store = AppSettings(context).current()
            val note = store?.note
            if (note == null) {
                views.setTextViewText(R.id.widgetResin, "레진")
                views.setTextViewText(R.id.widgetFull, if (store == null) "앱에서 로그인해 주세요" else "앱을 열면 불러와요")
            } else {
                val ui = Notes.ui(note)
                views.setTextViewText(R.id.widgetResin, "${ui.resin} / ${ui.maxResin}")
                views.setTextViewText(R.id.widgetFull, if (ui.fullLabel.isEmpty()) ui.fullTime else "${ui.fullTime} 가득")
                views.setTextViewText(R.id.widgetExpeditions, ui.expeditions.removeSuffix(" 완료"))
                views.setTextViewText(R.id.widgetCoin, ui.coin)
                views.setTextViewText(R.id.widgetCommissions, ui.commissions)
            }
            val wide = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) >= WIDE_DP
            views.setViewVisibility(R.id.widgetStats, if (note != null && wide) View.VISIBLE else View.GONE)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(android.R.id.background, open)
            manager.updateAppWidget(id, views)
        }
    }
}
