package com.cjlhll.iptv

import android.content.Context
import android.view.KeyEvent

/** 节目点击行为：历史且可回看的节目默认直接回看，或先弹确认。 */
enum class ProgramClickMode(val label: String) {
    DIRECT("直接回看"),
    CONFIRM("确认后回看"),
}

/** 播放列表刷新策略。 */
enum class PlaylistRefreshPolicy(val label: String) {
    STARTUP_6H("启动时 + 每6小时"),
    STARTUP_ONLY("仅启动时"),
    MANUAL("仅手动"),
}

/** EPG 刷新策略。 */
enum class EpgRefreshPolicy(val label: String) {
    STARTUP_6H("启动时 + 每6小时"),
    DAILY("每天首次启动"),
    MANUAL("仅手动"),
}

/** 可在“遥控器”设置中重新映射的动作（不含 Back/Home/Power/音量/数字/OK/Menu）。 */
enum class RemoteAction(val key: String, val label: String) {
    PREV_CHANNEL("prev_channel", "上一频道"),
    NEXT_CHANNEL("next_channel", "下一频道"),
    PREV_WATCHED("prev_watched", "上一观看频道"),
    CHANNEL_LIST("channel_list", "频道列表"),
    PROGRAM_GUIDE("program_guide", "节目单"),
    SETTINGS("settings", "设置"),
    INFO("info", "信息条"),
}

/**
 * 应用级基础设置（A09）持久化到与 Prefs 相同的 SharedPreferences。
 *
 * 所有设置必须有默认值、进程重启后保留、并能影响实际行为；不允许只做静态占位控件。
 */
object AppSettings {
    private const val PREF_NAME = "iptv_prefs"
    private const val KEY_RESTORE_LAST_CHANNEL = "settings_restore_last_channel"
    private const val KEY_PROGRAM_CLICK_MODE = "settings_program_click_mode"
    private const val KEY_PLAYLIST_REFRESH_POLICY = "settings_playlist_refresh_policy"
    private const val KEY_EPG_REFRESH_POLICY = "settings_epg_refresh_policy"
    private const val KEY_PLAYLIST_LAST_SUCCESS = "refresh_playlist_last_success"
    private const val KEY_PLAYLIST_LAST_ATTEMPT = "refresh_playlist_last_attempt"
    private const val KEY_EPG_LAST_SUCCESS = "refresh_epg_last_success"
    private const val KEY_EPG_LAST_ATTEMPT = "refresh_epg_last_attempt"
    private const val REMOTE_PREFIX = "settings_remote_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ---- 启动播放 ----
    fun isRestoreLastChannel(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RESTORE_LAST_CHANNEL, true)

    fun setRestoreLastChannel(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESTORE_LAST_CHANNEL, enabled).apply()
    }

    // ---- 节目选择 ----
    fun getProgramClickMode(context: Context): ProgramClickMode =
        runCatching {
            ProgramClickMode.valueOf(
                prefs(context).getString(KEY_PROGRAM_CLICK_MODE, ProgramClickMode.DIRECT.name)
                    ?: ProgramClickMode.DIRECT.name
            )
        }.getOrDefault(ProgramClickMode.DIRECT)

    fun setProgramClickMode(context: Context, mode: ProgramClickMode) {
        prefs(context).edit().putString(KEY_PROGRAM_CLICK_MODE, mode.name).apply()
    }

    // ---- 刷新策略 ----
    fun getPlaylistRefreshPolicy(context: Context): PlaylistRefreshPolicy =
        runCatching {
            PlaylistRefreshPolicy.valueOf(
                prefs(context).getString(
                    KEY_PLAYLIST_REFRESH_POLICY,
                    PlaylistRefreshPolicy.STARTUP_6H.name
                ) ?: PlaylistRefreshPolicy.STARTUP_6H.name
            )
        }.getOrDefault(PlaylistRefreshPolicy.STARTUP_6H)

    fun setPlaylistRefreshPolicy(context: Context, policy: PlaylistRefreshPolicy) {
        prefs(context).edit().putString(KEY_PLAYLIST_REFRESH_POLICY, policy.name).apply()
    }

    fun getEpgRefreshPolicy(context: Context): EpgRefreshPolicy =
        runCatching {
            EpgRefreshPolicy.valueOf(
                prefs(context).getString(KEY_EPG_REFRESH_POLICY, EpgRefreshPolicy.STARTUP_6H.name)
                    ?: EpgRefreshPolicy.STARTUP_6H.name
            )
        }.getOrDefault(EpgRefreshPolicy.STARTUP_6H)

    fun setEpgRefreshPolicy(context: Context, policy: EpgRefreshPolicy) {
        prefs(context).edit().putString(KEY_EPG_REFRESH_POLICY, policy.name).apply()
    }

    // ---- 刷新最近成功/尝试时间（按源隔离，R09） ----
    private fun srcSuffix(source: String): String = Integer.toHexString(source.hashCode())

    fun getPlaylistLastSuccess(context: Context, source: String): Long =
        prefs(context).getLong("${KEY_PLAYLIST_LAST_SUCCESS}_${srcSuffix(source)}", 0L)
    fun setPlaylistLastSuccess(context: Context, source: String, time: Long) {
        prefs(context).edit().putLong("${KEY_PLAYLIST_LAST_SUCCESS}_${srcSuffix(source)}", time).apply()
    }
    fun getPlaylistLastAttempt(context: Context, source: String): Long =
        prefs(context).getLong("${KEY_PLAYLIST_LAST_ATTEMPT}_${srcSuffix(source)}", 0L)
    fun setPlaylistLastAttempt(context: Context, source: String, time: Long) {
        prefs(context).edit().putLong("${KEY_PLAYLIST_LAST_ATTEMPT}_${srcSuffix(source)}", time).apply()
    }
    fun getEpgLastSuccess(context: Context, source: String): Long =
        prefs(context).getLong("${KEY_EPG_LAST_SUCCESS}_${srcSuffix(source)}", 0L)
    fun setEpgLastSuccess(context: Context, source: String, time: Long) {
        prefs(context).edit().putLong("${KEY_EPG_LAST_SUCCESS}_${srcSuffix(source)}", time).apply()
    }
    fun getEpgLastAttempt(context: Context, source: String): Long =
        prefs(context).getLong("${KEY_EPG_LAST_ATTEMPT}_${srcSuffix(source)}", 0L)
    fun setEpgLastAttempt(context: Context, source: String, time: Long) {
        prefs(context).edit().putLong("${KEY_EPG_LAST_ATTEMPT}_${srcSuffix(source)}", time).apply()
    }

    // 兼容旧全局键（仅用于展示/旧数据回退）
    fun getPlaylistLastSuccess(context: Context): Long = prefs(context).getLong(KEY_PLAYLIST_LAST_SUCCESS, 0L)
    fun setPlaylistLastSuccess(context: Context, time: Long) { prefs(context).edit().putLong(KEY_PLAYLIST_LAST_SUCCESS, time).apply() }
    fun getPlaylistLastAttempt(context: Context): Long = prefs(context).getLong(KEY_PLAYLIST_LAST_ATTEMPT, 0L)
    fun setPlaylistLastAttempt(context: Context, time: Long) { prefs(context).edit().putLong(KEY_PLAYLIST_LAST_ATTEMPT, time).apply() }
    fun getEpgLastSuccess(context: Context): Long = prefs(context).getLong(KEY_EPG_LAST_SUCCESS, 0L)
    fun setEpgLastSuccess(context: Context, time: Long) { prefs(context).edit().putLong(KEY_EPG_LAST_SUCCESS, time).apply() }
    fun getEpgLastAttempt(context: Context): Long = prefs(context).getLong(KEY_EPG_LAST_ATTEMPT, 0L)
    fun setEpgLastAttempt(context: Context, time: Long) { prefs(context).edit().putLong(KEY_EPG_LAST_ATTEMPT, time).apply() }

    // ---- 遥控器映射 ----
    fun defaultKey(action: RemoteAction): Int = when (action) {
        RemoteAction.PREV_CHANNEL -> KeyEvent.KEYCODE_DPAD_UP
        RemoteAction.NEXT_CHANNEL -> KeyEvent.KEYCODE_DPAD_DOWN
        RemoteAction.PREV_WATCHED -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        RemoteAction.CHANNEL_LIST -> KeyEvent.KEYCODE_DPAD_LEFT
        RemoteAction.PROGRAM_GUIDE -> KeyEvent.KEYCODE_GUIDE
        RemoteAction.SETTINGS -> KeyEvent.KEYCODE_DPAD_RIGHT
        RemoteAction.INFO -> KeyEvent.KEYCODE_DPAD_CENTER
    }

    fun getRemoteKey(context: Context, action: RemoteAction): Int =
        prefs(context).getInt(REMOTE_PREFIX + action.key, defaultKey(action))

    /** 当前是否使用自定义映射（用于设置界面显示“默认/自定义”）。 */
    fun isRemoteCustom(context: Context, action: RemoteAction): Boolean =
        prefs(context).contains(REMOTE_PREFIX + action.key)

    fun setRemoteKey(context: Context, action: RemoteAction, keyCode: Int) {
        prefs(context).edit().putInt(REMOTE_PREFIX + action.key, keyCode).apply()
    }

    fun clearRemoteKey(context: Context, action: RemoteAction) {
        prefs(context).edit().remove(REMOTE_PREFIX + action.key).apply()
    }

    fun resetRemote(context: Context) {
        val editor = prefs(context).edit()
        RemoteAction.entries.forEach { editor.remove(REMOTE_PREFIX + it.key) }
        editor.apply()
    }

    /** 返回与 keyCode 冲突的其它动作（用于冲突拒绝提示）。 */
    fun conflictingAction(context: Context, action: RemoteAction, keyCode: Int): RemoteAction? {
        return RemoteAction.entries.firstOrNull { other ->
            other != action && getRemoteKey(context, other) == keyCode
        }
    }

    /** 某个物理按键当前映射到的动作（Live 无面板时使用）。 */
    fun actionForKey(context: Context, keyCode: Int): RemoteAction? =
        RemoteAction.entries.firstOrNull { getRemoteKey(context, it) == keyCode }

    /**
     * 禁止分配的系统/基础职责键：Back、Home、Power、音量、数字 0–9、OK/Enter、Menu。
     * 这些键保持基础职责，不允许自定义覆盖。
     */
    fun isForbiddenKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MENU -> true
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> true
            else -> false
        }
    }

    /** 允许分配的普通功能键集合（方向、Channel±、Guide、MediaPrevious 等）。 */
    fun isAssignableKey(keyCode: Int): Boolean {
        if (isForbiddenKey(keyCode)) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_TV,
            KeyEvent.KEYCODE_TV_INPUT -> true
            else -> keyCode > 0
        }
    }

    fun keyDisplayName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "方向上"
        KeyEvent.KEYCODE_DPAD_DOWN -> "方向下"
        KeyEvent.KEYCODE_DPAD_LEFT -> "方向左"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "方向右"
        KeyEvent.KEYCODE_DPAD_CENTER -> "OK"
        KeyEvent.KEYCODE_CHANNEL_UP -> "频道+"
        KeyEvent.KEYCODE_CHANNEL_DOWN -> "频道-"
        KeyEvent.KEYCODE_GUIDE -> "Guide"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "上一曲/上一频道"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "下一曲/下一频道"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "播放/暂停"
        KeyEvent.KEYCODE_MEDIA_STOP -> "停止"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "快退"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "快进"
        KeyEvent.KEYCODE_TV -> "TV"
        KeyEvent.KEYCODE_TV_INPUT -> "信号源"
        else -> "按键#$keyCode"
    }
}
