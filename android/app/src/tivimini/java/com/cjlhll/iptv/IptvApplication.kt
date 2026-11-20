package com.cjlhll.iptv

import android.app.Application

class IptvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LegacyPreferences.migrate(this)
        // t172 (F3): the startup/interval policy now lives in AutoUpdateScheduler, which honours
        // the user's 自动更新播放列表/EPG choice (关闭 = no automatic pull at all) and the failure
        // backoff. Still runs on IO, never on the main thread.
        AutoUpdateScheduler.start(this)
    }
}
