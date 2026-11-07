package kr.sumi.checkin

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Text.init(this)
        DataLang.init()
        CharNames.init(this)
        Notifier.createChannel(this)
        ItemNames.init(this)
        ServerClock.offsetMs = AppSettings(this).clockOffset
        Jobs.removeLegacy(this)
    }
}
