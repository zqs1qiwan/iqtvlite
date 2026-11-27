package com.cjlhll.iptv

import android.app.Application

class IptvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LegacyPreferences.migrate(this)
        AppStrings.set(Prefs.getAppLanguage(this))
        NetUtil.setIpStackPreference(Prefs.getIpStackPreference(this))
        // AutoUpdateScheduler honours the user's playlist/EPG schedule and backoff.
        AutoUpdateScheduler.start(this)
    }
}
