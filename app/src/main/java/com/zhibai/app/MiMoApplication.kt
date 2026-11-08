package com.zhibai.app

import android.app.Application
import com.zhibai.app.adb.AdbManager

class MiMoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AdbManager.init(this)
    }
}
