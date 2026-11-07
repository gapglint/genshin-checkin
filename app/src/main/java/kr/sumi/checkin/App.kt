package kr.sumi.checkin

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.apply(this)
        Text.init(this)
        DataLang.init()
        CharNames.init(this)
        Notifier.createChannel(this)
        ItemNames.init(this)
        ServerClock.offsetMs = AppSettings(this).clockOffset
        Jobs.removeLegacy(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 화면 회전 등으로 시스템이 설정을 다시 넣으면 앱 안에서 고른 언어를 다시 씌운다(Android 12까지).
        AppLocale.apply(this)
    }
}
