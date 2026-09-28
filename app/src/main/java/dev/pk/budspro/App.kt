package dev.pk.budspro

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Buds.init(this)
        GuardService.createChannel(this)
    }
}
