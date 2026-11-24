package com.cjlhll.iptv

import android.content.Context

/** Copy only explicit pushed sources. Keep every legacy preference/cache for rollback. */
object LegacyPreferences {
    fun migrate(context: Context) {
        val current = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        if (current.getBoolean("legacy_sources_copied", false)) return
        val old = context.getSharedPreferences("iqtv_prefs", Context.MODE_PRIVATE)
        val edit = current.edit()
        if (!current.contains("live_source")) old.getString("push_live_url", null)?.let { edit.putString("live_source", it) }
        if (!current.contains("epg_source")) old.getString("push_epg_url", null)?.let { edit.putString("epg_source", it) }
        edit.putBoolean("legacy_sources_copied", true).apply()
    }
}
