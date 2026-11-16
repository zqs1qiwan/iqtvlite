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
            e.apply()
            true
        } catch (e: Exception) { false }
    }

    fun backupFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "iqtvlite-backup.json")
}
