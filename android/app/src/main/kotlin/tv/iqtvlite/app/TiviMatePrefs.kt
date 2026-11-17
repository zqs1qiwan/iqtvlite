package tv.iqtvlite.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * TiviMate 风格的设置 / 数据管理：
 * - 家长控制（PIN + 锁定频道）
 * - 提醒（节目开始时提醒）
 * - 自定义频道排序 / 隐藏频道 / 自定义分组
 * - 备份 / 恢复（全部数据导出为 JSON）
 *
 * 全部存本地 SharedPreferences，无云同步、无遥测（共享设备隐私）。
 */
object TiviMatePrefs {
    private const val PREF = "iqtvlite_tivimate"
    private const val KEY_PIN = "parental_pin"
    private const val KEY_LOCKED = "locked_channels"
    private const val KEY_HIDDEN = "hidden_channels"
    private const val KEY_REMINDERS = "reminders"
    private const val KEY_ORDER = "channel_order"
    private const val KEY_GROUPS = "custom_groups"
    private const val KEY_EPG_SOURCES = "epg_sources"
    private const val KEY_RESUME = "resume_positions"
    private const val KEY_PLAYBACK_PREFS = "playback_prefs"
    private const val KEY_LAST_CHANNEL = "last_channel_id"
    private const val KEY_LAST_SOURCE = "last_source_index"
    private const val KEY_HIDDEN_GROUPS = "hidden_groups"
    private const val KEY_GROUP_NAMES = "group_names"
    private const val KEY_PIP_ENABLED = "pip_enabled"
    private const val KEY_ASPECT_MODE = "aspect_mode"
    private const val KEY_DECODER_FALLBACK = "decoder_fallback"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun readLongs(ctx: Context, key: String): List<Long> {
        val raw = prefs(ctx).getString(key, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getLong(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeLongs(ctx: Context, key: String, ids: List<Long>) {
        val a = JSONArray()
        ids.forEach { a.put(it) }
        prefs(ctx).edit().putString(key, a.toString()).apply()
    }

    // ── 家长控制 ────────────────────────────────────────────────────
    fun setPin(ctx: Context, pin: String?) {
        prefs(ctx).edit().putString(KEY_PIN, pin).apply()
    }
    fun getPin(ctx: Context): String? = prefs(ctx).getString(KEY_PIN, null)
    fun isParentalEnabled(ctx: Context): Boolean = !getPin(ctx).isNullOrEmpty()

    fun isLocked(ctx: Context, id: Long): Boolean = readLongs(ctx, KEY_LOCKED).contains(id)
    fun toggleLocked(ctx: Context, id: Long): Boolean {
        val l = readLongs(ctx, KEY_LOCKED).toMutableList()
        val added = if (l.contains(id)) { l.remove(id); false } else { l.add(id); true }
        writeLongs(ctx, KEY_LOCKED, l)
        return added
    }
    fun lockedChannels(ctx: Context): List<Long> = readLongs(ctx, KEY_LOCKED)

    // ── 隐藏频道 ────────────────────────────────────────────────────
    fun isHidden(ctx: Context, id: Long): Boolean = readLongs(ctx, KEY_HIDDEN).contains(id)
    fun toggleHidden(ctx: Context, id: Long): Boolean {
        val l = readLongs(ctx, KEY_HIDDEN).toMutableList()
        val added = if (l.contains(id)) { l.remove(id); false } else { l.add(id); true }
        writeLongs(ctx, KEY_HIDDEN, l)
        return added
    }
    fun hiddenChannels(ctx: Context): List<Long> = readLongs(ctx, KEY_HIDDEN)

    // ── 自定义排序（id 顺序列表）────────────────────────────────────
    fun channelOrder(ctx: Context): List<Long> = readLongs(ctx, KEY_ORDER)
    fun saveChannelOrder(ctx: Context, ids: List<Long>) = writeLongs(ctx, KEY_ORDER, ids)

    // ── 自定义分组：tvgId → 组名 ────────────────────────────────────
    fun customGroups(ctx: Context): Map<String, String> {
        val raw = prefs(ctx).getString(KEY_GROUPS, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (_: Exception) { emptyMap() }
    }
    fun setCustomGroup(ctx: Context, tvgId: String, group: String?) {
        val m = customGroups(ctx).toMutableMap()
        if (group.isNullOrEmpty()) m.remove(tvgId) else m[tvgId] = group
        prefs(ctx).edit().putString(KEY_GROUPS, JSONObject(m as Map<*, *>).toString()).apply()
    }

    // ── 提醒 ────────────────────────────────────────────────────────
    data class Reminder(val tvgId: String, val title: String, val startTs: Long)

    fun reminders(ctx: Context): List<Reminder> {
        val raw = prefs(ctx).getString(KEY_REMINDERS, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Reminder(o.getString("tvgId"), o.getString("title"), o.getLong("startTs"))
            }
        } catch (_: Exception) { emptyList() }
    }
    fun addReminder(ctx: Context, r: Reminder) {
        val list = reminders(ctx).toMutableList()
        if (list.none { it.tvgId == r.tvgId && it.startTs == r.startTs }) list.add(r)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().apply {
            put("tvgId", it.tvgId); put("title", it.title); put("startTs", it.startTs)
        }) }
        prefs(ctx).edit().putString(KEY_REMINDERS, a.toString()).apply()
    }
    fun removeReminder(ctx: Context, r: Reminder) {
        val list = reminders(ctx).filterNot { it.tvgId == r.tvgId && it.startTs == r.startTs }
        val a = JSONArray()
        list.forEach { a.put(JSONObject().apply {
            put("tvgId", it.tvgId); put("title", it.title); put("startTs", it.startTs)
        }) }
        prefs(ctx).edit().putString(KEY_REMINDERS, a.toString()).apply()
    }

    // ── EPG 源管理（自定义源 + 优先级）─────────────────────────────
    data class EpgSource(val url: String, val priority: Int, val enabled: Boolean = true)

    fun epgSources(ctx: Context): List<EpgSource> {
        val raw = prefs(ctx).getString(KEY_EPG_SOURCES, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                EpgSource(o.getString("url"), o.optInt("priority", 30), o.optBoolean("enabled", true))
            }.sortedBy { it.priority }
        } catch (_: Exception) { emptyList() }
    }

    fun saveEpgSources(ctx: Context, sources: List<EpgSource>) {
        val a = JSONArray()
        sources.sortedBy { it.priority }.forEach { s ->
            a.put(JSONObject().apply {
                put("url", s.url); put("priority", s.priority); put("enabled", s.enabled)
            })
        }
        prefs(ctx).edit().putString(KEY_EPG_SOURCES, a.toString()).apply()
    }

    // ── 分组隐藏 / 重命名 ────────────────────────────────────────────
    fun hiddenGroups(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString(KEY_HIDDEN_GROUPS, null) ?: return emptySet()
        return try {
            val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }
    }
    fun toggleHiddenGroup(ctx: Context, group: String): Boolean {
        val set = hiddenGroups(ctx).toMutableSet()
        val hidden = if (set.contains(group)) { set.remove(group); false } else { set.add(group); true }
        prefs(ctx).edit().putString(KEY_HIDDEN_GROUPS, JSONArray(set).toString()).apply()
        return hidden
    }
    fun groupNames(ctx: Context): Map<String, String> {
        val raw = prefs(ctx).getString(KEY_GROUP_NAMES, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw); o.keys().asSequence().associateWith { o.getString(it) }
        } catch (_: Exception) { emptyMap() }
    }
    fun renameGroup(ctx: Context, original: String, newName: String) {
        val m = groupNames(ctx).toMutableMap()
        if (newName.isBlank() || newName == original) m.remove(original) else m[original] = newName
        prefs(ctx).edit().putString(KEY_GROUP_NAMES, JSONObject(m as Map<*, *>).toString()).apply()
    }

    // ── PiP 浏览预览开关 ────────────────────────────────────────────
    fun pipEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PIP_ENABLED, true)
    fun setPipEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PIP_ENABLED, enabled).apply()
    }

    // ── 画面比例 / 解码器偏好 ────────────────────────────────────────
    /** 0=自适应(默认), 1=铺满裁切(crop), 2=完整显示(letterbox) */
    fun aspectMode(ctx: Context): Int = prefs(ctx).getInt(KEY_ASPECT_MODE, 0)
    fun setAspectMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt(KEY_ASPECT_MODE, mode).apply()
    }
    /** 硬解失败时自动回退软解（某些频道硬解花屏时有用） */
    fun decoderFallback(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DECODER_FALLBACK, true)
    fun setDecoderFallback(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DECODER_FALLBACK, enabled).apply()
    }

    // ── 上次观看频道（TiviMate 秒播：启动自动播放）─────────────────
    fun saveLastChannel(ctx: Context, channelId: Long, sourceIndex: Int) {
        prefs(ctx).edit()
            .putLong(KEY_LAST_CHANNEL, channelId)
            .putInt(KEY_LAST_SOURCE, sourceIndex)
            .apply()
    }
    fun lastChannel(ctx: Context): Pair<Long, Int> =
        prefs(ctx).getLong(KEY_LAST_CHANNEL, -1L) to prefs(ctx).getInt(KEY_LAST_SOURCE, 0)

    // ── 断点续播（VOD/剧集）────────────────────────────────────────
    fun saveResume(ctx: Context, key: String, positionMs: Long, durationMs: Long) {
        val o = JSONObject(prefs(ctx).getString(KEY_RESUME, null) ?: "{}")
        o.put(key, JSONObject().apply { put("pos", positionMs); put("dur", durationMs); put("at", System.currentTimeMillis()) })
        prefs(ctx).edit().putString(KEY_RESUME, o.toString()).apply()
    }
    fun getResume(ctx: Context, key: String): Pair<Long, Long> {
        val o = JSONObject(prefs(ctx).getString(KEY_RESUME, null) ?: "{}")
        val p = o.optJSONObject(key) ?: return 0L to 0L
        return p.optLong("pos", 0) to p.optLong("dur", 0)
    }

    // ── 播放偏好（AFR / 音频直通 / 外部播放器）─────────────────────
    data class PlaybackPrefs(
        val autoFrameRate: Boolean = false,
        val audioPassthrough: Boolean = false,
        val externalPlayer: Boolean = false,
    )
    fun playbackPrefs(ctx: Context): PlaybackPrefs {
        val s = prefs(ctx).getString(KEY_PLAYBACK_PREFS, null) ?: return PlaybackPrefs()
        return try {
            val o = JSONObject(s)
            PlaybackPrefs(o.optBoolean("afr"), o.optBoolean("passthrough"), o.optBoolean("external"))
        } catch (_: Exception) { PlaybackPrefs() }
    }
    fun savePlaybackPrefs(ctx: Context, p: PlaybackPrefs) {
        prefs(ctx).edit().putString(KEY_PLAYBACK_PREFS, JSONObject().apply {
            put("afr", p.autoFrameRate); put("passthrough", p.audioPassthrough); put("external", p.externalPlayer)
        }.toString()).apply()
    }

    // ── 备份 / 恢复 ─────────────────────────────────────────────────
    private fun longsArray(ids: List<Long>) = JSONArray().apply { ids.forEach { put(it) } }

    fun exportBackup(ctx: Context): JSONObject {
        val p = prefs(ctx)
        return JSONObject().apply {
            put("version", 1)
            put("parental_pin", p.getString(KEY_PIN, null) ?: JSONObject.NULL)
            put(KEY_LOCKED, longsArray(readLongs(ctx, KEY_LOCKED)))
            put(KEY_HIDDEN, longsArray(readLongs(ctx, KEY_HIDDEN)))
            put(KEY_ORDER, longsArray(readLongs(ctx, KEY_ORDER)))
            put(KEY_GROUPS, JSONObject(customGroups(ctx) as Map<*, *>))
            val ra = JSONArray()
            reminders(ctx).forEach { ra.put(JSONObject().apply {
                put("tvgId", it.tvgId); put("title", it.title); put("startTs", it.startTs)
            }) }
            put(KEY_REMINDERS, ra)
            val ea = JSONArray()
            epgSources(ctx).forEach { ea.put(JSONObject().apply {
                put("url", it.url); put("priority", it.priority); put("enabled", it.enabled)
            }) }
            put(KEY_EPG_SOURCES, ea)
            put(KEY_HIDDEN_GROUPS, JSONArray(hiddenGroups(ctx)))
            put(KEY_GROUP_NAMES, JSONObject(groupNames(ctx) as Map<*, *>))
            put(KEY_PIP_ENABLED, pipEnabled(ctx))
            val pp = playbackPrefs(ctx)
            put(KEY_PLAYBACK_PREFS, JSONObject().apply {
                put("afr", pp.autoFrameRate); put("passthrough", pp.audioPassthrough); put("external", pp.externalPlayer)
            })
        }
    }

    fun importBackup(ctx: Context, json: JSONObject): Boolean {
        return try {
            val e = prefs(ctx).edit()
            val pin = json.opt(KEY_PIN)
            if (pin != null && pin != JSONObject.NULL) e.putString(KEY_PIN, pin.toString())
            // 恢复各列表
            fun parseLongs(key: String): List<Long> {
                val arr = json.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { arr.optString(it).toLongOrNull() }
            }
            writeLongs(ctx, KEY_LOCKED, parseLongs(KEY_LOCKED))
            writeLongs(ctx, KEY_HIDDEN, parseLongs(KEY_HIDDEN))
            writeLongs(ctx, KEY_ORDER, parseLongs(KEY_ORDER))
            val g = json.optJSONObject(KEY_GROUPS)
            e.putString(KEY_GROUPS, g?.toString() ?: "{}")
            e.putString(KEY_REMINDERS, json.optJSONArray(KEY_REMINDERS)?.toString() ?: "[]")
            e.putString(KEY_EPG_SOURCES, json.optJSONArray(KEY_EPG_SOURCES)?.toString() ?: "[]")
            e.putString(KEY_HIDDEN_GROUPS, json.optJSONArray(KEY_HIDDEN_GROUPS)?.toString() ?: "[]")
            e.putString(KEY_GROUP_NAMES, json.optJSONObject(KEY_GROUP_NAMES)?.toString() ?: "{}")
            if (json.has(KEY_PIP_ENABLED)) e.putBoolean(KEY_PIP_ENABLED, json.optBoolean(KEY_PIP_ENABLED, true))
            json.optJSONObject(KEY_PLAYBACK_PREFS)?.let { e.putString(KEY_PLAYBACK_PREFS, it.toString()) }
            e.apply()
            true
        } catch (e: Exception) { false }
    }

    fun backupFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "iqtvlite-backup.json")
}
