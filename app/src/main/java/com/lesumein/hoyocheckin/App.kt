package com.lesumein.hoyocheckin

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
        ServerClock.offsetMs = AppSettings(this).clockOffset
    }
}
