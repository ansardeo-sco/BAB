package com.ansardeo.binancebot

import android.app.Application
import com.ansardeo.binancebot.paper.PaperMonitor

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PaperMonitor.start(this)
    }
}
