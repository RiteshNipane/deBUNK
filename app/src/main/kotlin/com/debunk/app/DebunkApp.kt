package com.debunk.app

import android.app.Application
import com.debunk.app.data.DebunkRuntime

class DebunkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebunkRuntime.init(this)
    }
}
