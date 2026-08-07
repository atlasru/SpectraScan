package com.atlas.spectrascan

import android.app.Application
import android.content.Context

class SpectraScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
