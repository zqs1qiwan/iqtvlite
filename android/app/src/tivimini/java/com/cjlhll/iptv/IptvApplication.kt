package com.cjlhll.iptv

import android.app.Application

class IptvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LegacyPreferences.migrate(this)
        BackgroundSourceUpdater.scheduleStartupRefresh(this)
    }
}
