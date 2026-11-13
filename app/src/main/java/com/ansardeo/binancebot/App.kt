package com.ansardeo.binancebot

import android.app.Application
import com.ansardeo.binancebot.paper.PaperMonitor
import com.ansardeo.binancebot.paper.DemoStrategyMonitor

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PaperMonitor.start(this)
        DemoStrategyMonitor.start(this)
    }
}
