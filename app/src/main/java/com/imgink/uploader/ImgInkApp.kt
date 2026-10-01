package com.imgink.uploader

import android.app.Application
import com.imgink.uploader.data.AppLog

class ImgInkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.log("app", "process start, version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
    }
}
