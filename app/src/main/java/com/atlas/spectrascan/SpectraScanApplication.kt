package com.atlas.spectrascan

import android.app.Application
import android.content.Context
import android.util.Log
import org.opencv.android.OpenCVLoader

class SpectraScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        openCvReady = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
        Log.i("SpectraScan", "OpenCV ready=$openCvReady")
    }

    companion object {
        lateinit var appContext: Context
            private set
        @Volatile var openCvReady: Boolean = false
            private set
    }
}
