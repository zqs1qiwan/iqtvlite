package com.cjlhll.iptv

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val PREF_NAME = "iptv_prefs"
    private const val KEY_LIVE_SOURCE = "live_source"
    private const val KEY_EPG_SOURCE = "epg_source"
    private const val KEY_LAST_CHANNEL_URL = "last_channel_url"
    private const val KEY_LAST_CHANNEL_TITLE = "last_channel_title"
    private const val KEY_PLAYLIST_FILE_NAME = "playlist_file_name"
    private const val KEY_LAST_EPG_UPDATE_TIME = "last_epg_update_time"
    private const val KEY_SOURCE_VERSION = "source_version"
    private const val KEY_RECENT_CHANNELS = "recent_channels_v1"
    private const val KEY_RECENT_CLEARED = "recent_channels_cleared"
    private const val KEY_LOGO_PREFETCH_ENABLED = "logo_prefetch_enabled"
    private const val KEY_LOGO_LOAD_ENABLED = "logo_load_enabled"
    // t172 (F3): automatic update policy + attempt bookkeeping.
    private const val KEY_AUTO_UPDATE_PLAYLIST = "auto_update_playlist"
    private const val KEY_AUTO_UPDATE_EPG = "auto_update_epg"
    private const val KEY_PLAYLIST_LAST_SUCCESS = "playlist_last_success_at"
    private const val KEY_PLAYLIST_LAST_FAILURE = "playlist_last_failure_at"
    private const val KEY_PLAYLIST_FAIL_COUNT = "playlist_fail_count"
    private const val KEY_EPG_LAST_FAILURE = "epg_last_failure_at"
    private const val KEY_EPG_FAIL_COUNT = "epg_fail_count"
    // t186 (F1): remote-control habits.
    private const val KEY_REMOTE_UPDOWN = "remote_updown"
    private const val KEY_REMOTE_OK = "remote_ok"
    private const val KEY_REMOTE_BACK = "remote_back"
    private const val KEY_REMOTE_DIGITS = "remote_digits"
    private const val KEY_REMOTE_CHANNEL_KEYS = "remote_channel_keys"
    // t205: start-up buffer tier (300/400/500 ms), default 稳/500.
    private const val KEY_PLAYBACK_BUFFER_MIN = "playback_buffer_min"
    // t218: neighbour pre-connect experiment (default OFF — opt-in only).
    private const val KEY_ZAP_PRECONNECT = "zap_preconnect_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getLiveSource(context: Context): String {
        return getPrefs(context).getString(KEY_LIVE_SOURCE, "") ?: ""
    }

    fun setLiveSource(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_LIVE_SOURCE, url).apply()
    }

    fun getEpgSource(context: Context): String {
        return getPrefs(context).getString(KEY_EPG_SOURCE, "") ?: ""
    }

    fun setEpgSource(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_EPG_SOURCE, url).apply()
    }

    fun getLastEpgUpdateTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_EPG_UPDATE_TIME, 0L)
    }

    fun setLastEpgUpdateTime(context: Context, time: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_EPG_UPDATE_TIME, time).apply()
    }

    fun getLastChannel(context: Context): Pair<String?, String?> {
        val prefs = getPrefs(context)
        val url = prefs.getString(KEY_LAST_CHANNEL_URL, null)
        val title = prefs.getString(KEY_LAST_CHANNEL_TITLE, null)
        return Pair(url, title)
    }

    fun setLastChannel(context: Context, url: String, title: String) {
        getPrefs(context).edit()
            .putString(KEY_LAST_CHANNEL_URL, url)
            .putString(KEY_LAST_CHANNEL_TITLE, title)
            .apply()
    }

    fun getPlaylistFileName(context: Context): String? {
        return getPrefs(context).getString(KEY_PLAYLIST_FILE_NAME, null)
    }

    fun setPlaylistFileName(context: Context, fileName: String) {
        getPrefs(context).edit().putString(KEY_PLAYLIST_FILE_NAME, fileName).apply()
    }

    /** 单 Editor 原子提交源元组（live/epg/playlist 指针），避免三次 apply 的中间态（R03 边界）。 */
    fun setSourceConfig(context: Context, live: String, epg: String, fileName: String) {
        getPrefs(context).edit()
            .putString(KEY_LIVE_SOURCE, live)
            .putString(KEY_EPG_SOURCE, epg)
            .putString(KEY_PLAYLIST_FILE_NAME, fileName)
            .apply()
    }

    fun getSourceVersion(context: Context): Long = getPrefs(context).getLong(KEY_SOURCE_VERSION, 0L)

    fun setSourceVersion(context: Context, version: Long) {
        getPrefs(context).edit().putLong(KEY_SOURCE_VERSION, version).apply()
    }

    // t133/t159: recent channels — one compact string (see RecentChannels.encode), ≤10 entries.
    // Writes use commit(): the value is tiny, and apply() can lose the write when the process is
    // force-stopped right after the user clears the list (the CI10104 blocker).
    fun getRecentChannels(context: Context): String? =
        getPrefs(context).getString(KEY_RECENT_CHANNELS, null)

    fun setRecentChannels(context: Context, encoded: String) {
        getPrefs(context).edit()
            .putString(KEY_RECENT_CHANNELS, encoded)
            .putBoolean(KEY_RECENT_CLEARED, false)
            .commit()
    }

    /** Remove the list and set the "user cleared it" marker in one atomic commit. */
    fun clearRecentChannels(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_RECENT_CHANNELS)
            .putBoolean(KEY_RECENT_CLEARED, true)
            .commit()
    }

    fun isRecentChannelsCleared(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_RECENT_CLEARED, false)

    // t154: perf-isolation switches (default ON; off isolates prefetch / logo decode cost).
    fun isLogoPrefetchEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_LOGO_PREFETCH_ENABLED, true)

    fun setLogoPrefetchEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LOGO_PREFETCH_ENABLED, enabled).apply()
    }

    fun isLogoLoadEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_LOGO_LOAD_ENABLED, true)

    fun setLogoLoadEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LOGO_LOAD_ENABLED, enabled).apply()
    }

    // ---- t172: automatic playlist / EPG updates ----
    fun getAutoUpdatePlaylist(context: Context): AutoUpdateConfig.Interval =
        AutoUpdateConfig.parse(getPrefs(context).getString(KEY_AUTO_UPDATE_PLAYLIST, null))

    fun setAutoUpdatePlaylist(context: Context, interval: AutoUpdateConfig.Interval) {
        getPrefs(context).edit().putString(KEY_AUTO_UPDATE_PLAYLIST, AutoUpdateConfig.encode(interval)).apply()
    }

    fun getAutoUpdateEpg(context: Context): AutoUpdateConfig.Interval =
        AutoUpdateConfig.parse(getPrefs(context).getString(KEY_AUTO_UPDATE_EPG, null))

    fun setAutoUpdateEpg(context: Context, interval: AutoUpdateConfig.Interval) {
        getPrefs(context).edit().putString(KEY_AUTO_UPDATE_EPG, AutoUpdateConfig.encode(interval)).apply()
    }

    fun getPlaylistLastSuccess(context: Context): Long =
        getPrefs(context).getLong(KEY_PLAYLIST_LAST_SUCCESS, 0L)

    fun setPlaylistLastSuccess(context: Context, at: Long) {
        getPrefs(context).edit()
            .putLong(KEY_PLAYLIST_LAST_SUCCESS, at)
            .putLong(KEY_PLAYLIST_LAST_FAILURE, 0L)
            .putInt(KEY_PLAYLIST_FAIL_COUNT, 0)
            .apply()
    }

    fun getPlaylistLastFailure(context: Context): Long =
        getPrefs(context).getLong(KEY_PLAYLIST_LAST_FAILURE, 0L)

    fun getPlaylistFailCount(context: Context): Int =
        getPrefs(context).getInt(KEY_PLAYLIST_FAIL_COUNT, 0)

    fun recordPlaylistFailure(context: Context, at: Long) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putLong(KEY_PLAYLIST_LAST_FAILURE, at)
            .putInt(KEY_PLAYLIST_FAIL_COUNT, prefs.getInt(KEY_PLAYLIST_FAIL_COUNT, 0) + 1)
            .apply()
    }

    fun getEpgLastFailure(context: Context): Long =
        getPrefs(context).getLong(KEY_EPG_LAST_FAILURE, 0L)

    fun getEpgFailCount(context: Context): Int =
        getPrefs(context).getInt(KEY_EPG_FAIL_COUNT, 0)

    fun recordEpgFailure(context: Context, at: Long) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putLong(KEY_EPG_LAST_FAILURE, at)
            .putInt(KEY_EPG_FAIL_COUNT, prefs.getInt(KEY_EPG_FAIL_COUNT, 0) + 1)
            .apply()
    }

    /** t205: the persisted start-up buffer tier; unknown values fall back to 稳/500. */
    fun getPlaybackBufferMin(context: Context): Int =
        LoadControlTuning.sanitize(getPrefs(context).getInt(KEY_PLAYBACK_BUFFER_MIN, LoadControlTuning.DEFAULT_BUFFER_MIN_MS))

    /** t218: neighbour pre-connect is an opt-in experiment; default OFF keeps behaviour unchanged. */
    fun getZapPreconnectEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ZAP_PRECONNECT, false)

    fun setZapPreconnectEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ZAP_PRECONNECT, enabled).apply()
    }

    fun setPlaybackBufferMin(context: Context, value: Int) {
        getPrefs(context).edit().putInt(KEY_PLAYBACK_BUFFER_MIN, LoadControlTuning.sanitize(value)).apply()
    }

    // ---- t186: remote habits (read on every key event, so a change applies immediately) ----
    fun getRemoteSettings(context: Context): RemoteHabits.Settings {
        val p = getPrefs(context)
        return RemoteHabits.Settings(
            upDown = RemoteHabits.parseUpDown(p.getString(KEY_REMOTE_UPDOWN, null)),
            ok = RemoteHabits.parseOk(p.getString(KEY_REMOTE_OK, null)),
            back = RemoteHabits.parseBack(p.getString(KEY_REMOTE_BACK, null)),
            digits = RemoteHabits.parseDigits(p.getString(KEY_REMOTE_DIGITS, null)),
            channelKeysEnabled = p.getBoolean(KEY_REMOTE_CHANNEL_KEYS, true),
        )
    }

    fun setRemoteUpDown(context: Context, value: RemoteHabits.UpDown) {
        getPrefs(context).edit().putString(KEY_REMOTE_UPDOWN, value.name).apply()
    }

    fun setRemoteOk(context: Context, value: RemoteHabits.Ok) {
        getPrefs(context).edit().putString(KEY_REMOTE_OK, value.name).apply()
    }

    fun setRemoteBack(context: Context, value: RemoteHabits.Back) {
        getPrefs(context).edit().putString(KEY_REMOTE_BACK, value.name).apply()
    }

    fun setRemoteDigits(context: Context, value: RemoteHabits.Digits) {
        getPrefs(context).edit().putString(KEY_REMOTE_DIGITS, value.name).apply()
    }

    fun setRemoteChannelKeys(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_REMOTE_CHANNEL_KEYS, enabled).apply()
    }

    /** t186: 「恢复默认」 — clears every remote key so the built-in defaults apply again. */
    fun resetRemoteSettings(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_REMOTE_UPDOWN)
            .remove(KEY_REMOTE_OK)
            .remove(KEY_REMOTE_BACK)
            .remove(KEY_REMOTE_DIGITS)
            .remove(KEY_REMOTE_CHANNEL_KEYS)
            .apply()
    }

    /** t189: write the EPG failure state in one step (single source: AutoUpdateConfig). */
    fun setEpgFailureState(context: Context, lastFailureAt: Long, failCount: Int) {
        getPrefs(context).edit()
            .putLong(KEY_EPG_LAST_FAILURE, lastFailureAt)
            .putInt(KEY_EPG_FAIL_COUNT, failCount)
            .apply()
    }

    fun clearEpgFailure(context: Context) {
        getPrefs(context).edit()
            .putLong(KEY_EPG_LAST_FAILURE, 0L)
            .putInt(KEY_EPG_FAIL_COUNT, 0)
            .apply()
    }
}
