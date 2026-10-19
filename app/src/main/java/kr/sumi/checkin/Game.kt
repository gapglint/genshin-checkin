package kr.sumi.checkin

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 원신 게임 앱. 깔려 있으면 바로 열고, 없으면 플레이스토어의 원신 페이지를 연다. */
object Game {
    const val PACKAGE = "com.miHoYo.GenshinImpact"

    fun launchIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
}
