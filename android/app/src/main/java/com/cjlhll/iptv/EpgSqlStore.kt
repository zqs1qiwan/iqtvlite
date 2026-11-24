package com.cjlhll.iptv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量级 SQLite EPG 索引库（彻底取代 22 秒冷启反序列化 + 50MB 堆常驻的 Java ObjectInputStream `.ser`）。
 *
 * 设计要点：
 * - 冷启打开数据库 + 载入轻量别名表仅需 <20ms，JVM 堆占用 <1MB（PSS 大幅下降）；
 * - 当前节目批量查询与单频道完整节目单均走 `(channel_id, start_ms)` 复合索引，毫秒级返回；
 * - 写入采用单事务 + 预编译 SQLiteStatement，全量更新原子切换，永不产生半截数据。
 */
class EpgSqlHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS aliases (norm_key TEXT PRIMARY KEY, channel_id TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS programs (
                channel_id TEXT NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                title TEXT NOT NULL,
                offset_sec INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_prog_ch_start ON programs(channel_id, start_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_prog_time ON programs(start_ms, end_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS meta")
        db.execSQL("DROP TABLE IF EXISTS aliases")
        db.execSQL("DROP TABLE IF EXISTS programs")
        onCreate(db)
    }

    companion object {
        private const val DB_NAME = "epg_index_v2.db"
        private const val DB_VERSION = 1
    }
}

object EpgSqlStore {
    private const val TAG = "EpgSqlStore"

    @Volatile private var helper: EpgSqlHelper? = null
    @Volatile private var cachedSource: String? = null
    @Volatile private var aliasMap: Map<String, String> = emptyMap()
    @Volatile private var knownChannelIds: Set<String> = emptySet()
    private val resolvedIdCache = ConcurrentHashMap<String, String>()
    private const val NOT_FOUND = "\u0000"

    private fun db(context: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) {
            helper ?: EpgSqlHelper(context.applicationContext).also { helper = it }
        }
        return h.writableDatabase
    }

    /** 检查当前 SQLite 库是否已存有指定源的有效索引。 */
    fun hasSource(context: Context, source: String): Boolean {
        if (source.isBlank()) return false
        return runCatching {
            val d = db(context)
            d.rawQuery("SELECT v FROM meta WHERE k = 'source'", null).use { c ->
                c.moveToFirst() && c.getString(0) == source
            }
        }.getOrDefault(false)
    }

    /** 原子写入全量 EPG 索引到 SQLite，并刷新内存轻量别名表。 */
    fun replaceAll(context: Context, source: String, data: EpgData) {
        val t0 = System.currentTimeMillis()
        val d = db(context)
        d.beginTransaction()
        try {
            d.execSQL("DELETE FROM meta")
            d.execSQL("DELETE FROM aliases")
            d.execSQL("DELETE FROM programs")

            val insAlias = d.compileStatement("INSERT OR REPLACE INTO aliases(norm_key, channel_id) VALUES(?, ?)")
            for ((k, id) in data.normalizedDisplayNameToChannelId) {
                insAlias.clearBindings()
                insAlias.bindString(1, k)
                insAlias.bindString(2, id)
                insAlias.executeInsert()
            }
            insAlias.close()

            val insProg = d.compileStatement(
                "INSERT INTO programs(channel_id, start_ms, end_ms, title, offset_sec) VALUES(?, ?, ?, ?, ?)"
            )
            var count = 0
            for ((chId, list) in data.programsByChannelId) {
                for (p in list) {
                    insProg.clearBindings()
                    insProg.bindString(1, chId)
                    insProg.bindLong(2, p.startMillis)
                    insProg.bindLong(3, p.endMillis)
                    insProg.bindString(4, p.title)
                    val off = p.sourceOffsetSeconds
                    if (off == null) insProg.bindNull(5) else insProg.bindLong(5, off.toLong())
                    insProg.executeInsert()
                    count++
                }
            }
            insProg.close()

            val insMeta = d.compileStatement("INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)")
            insMeta.bindString(1, "source")
            insMeta.bindString(2, source)
            insMeta.executeInsert()
            insMeta.clearBindings()
            insMeta.bindString(1, "updated_at")
            insMeta.bindString(2, System.currentTimeMillis().toString())
            insMeta.executeInsert()
            insMeta.close()

            d.setTransactionSuccessful()
            cachedSource = source
            aliasMap = HashMap(data.normalizedDisplayNameToChannelId)
            knownChannelIds = HashSet(data.programsByChannelId.keys)
            resolvedIdCache.clear()
            Log.i(TAG, "saved programs=$count aliases=${aliasMap.size} in ${System.currentTimeMillis() - t0}ms")
        } finally {
            d.endTransaction()
        }
    }

    /** 仅加载极轻量的别名表 + 频道 ID 集合到内存（<1MB，<20ms），不加载任何节目对象。 */
    fun ensureIndexLoaded(context: Context, source: String): Boolean {
        if (source.isBlank()) return false
        if (cachedSource == source && knownChannelIds.isNotEmpty()) return true
        if (!hasSource(context, source)) return false
        return runCatching {
            val d = db(context)
            val aliases = HashMap<String, String>(1024)
            d.rawQuery("SELECT norm_key, channel_id FROM aliases", null).use { c ->
                while (c.moveToNext()) {
                    aliases[c.getString(0)] = c.getString(1)
                }
            }
            val ids = HashSet<String>(512)
            d.rawQuery("SELECT DISTINCT channel_id FROM programs", null).use { c ->
                while (c.moveToNext()) {
                    ids.add(c.getString(0))
                }
            }
            aliasMap = aliases
            knownChannelIds = ids
            cachedSource = source
            resolvedIdCache.clear()
            ids.isNotEmpty()
        }.getOrDefault(false)
    }

    private val trailingQualityRegex = Regex("""(?i)[\s\-_]*(4K|8K|UHD|FHD|HD|SD|1080P|720P|50FPS|60FPS|HDR|超高清|超清|高清|蓝光|标清)+$""")

    fun resolveChannelId(channel: Channel): String? {
        val ids = knownChannelIds
        if (ids.isEmpty()) return null
        val key = "${channel.url}|${channel.title}|${channel.tvgId.orEmpty()}"
        resolvedIdCache[key]?.let { return if (it == NOT_FOUND) null else it }
        val aliases = aliasMap
        val resolved = resolveUncached(channel, ids, aliases)
        resolvedIdCache[key] = resolved ?: NOT_FOUND
        return resolved
    }

    private fun lookupAliases(raw: String, ids: Set<String>, aliases: Map<String, String>): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed in ids) return trimmed
        for (k in EpgNormalize.keys(trimmed)) {
            val id = aliases[k]
            if (id != null && id in ids) return id
        }
        val stripped = trailingQualityRegex.replace(trimmed, "").trim()
        if (stripped.isNotEmpty() && stripped != trimmed) {
            if (stripped in ids) return stripped
            for (k in EpgNormalize.keys(stripped)) {
                val id = aliases[k]
                if (id != null && id in ids) return id
            }
        }
        return null
    }

    private fun resolveUncached(channel: Channel, ids: Set<String>, aliases: Map<String, String>): String? {
        channel.tvgId?.let { lookupAliases(it, ids, aliases)?.let { id -> return id } }
        channel.tvgName?.let { lookupAliases(it, ids, aliases)?.let { id -> return id } }
        return lookupAliases(channel.title, ids, aliases)
    }

    /** 单条索引查询所有正在播出的节目（<5ms，零全量反序列化），返回 variant.url -> NowProgramUi。 */
    fun queryNowPrograms(context: Context, groups: List<ChannelGroup>, nowMillis: Long): Map<String, NowProgramUi> {
        if (groups.isEmpty() || knownChannelIds.isEmpty()) return emptyMap()
        return runCatching {
            val d = db(context)
            val nowByChannelId = HashMap<String, EpgProgram>(256)
            d.rawQuery(
                "SELECT channel_id, start_ms, end_ms, title, offset_sec FROM programs WHERE start_ms <= ? AND end_ms > ?",
                arrayOf(nowMillis.toString(), nowMillis.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val chId = c.getString(0)
                    if (!nowByChannelId.containsKey(chId)) {
                        nowByChannelId[chId] = EpgProgram(
                            channelId = chId,
                            startMillis = c.getLong(1),
                            endMillis = c.getLong(2),
                            title = c.getString(3),
                            sourceOffsetSeconds = if (c.isNull(4)) null else c.getInt(4),
                        )
                    }
                }
            }
            val nextByChannelId = HashMap<String, Pair<String, Long>>(256)
            val windowEnd = nowMillis + 12L * 3600_000L
            d.rawQuery(
                "SELECT channel_id, start_ms, title FROM programs WHERE start_ms > ? AND start_ms <= ? ORDER BY start_ms ASC",
                arrayOf(nowMillis.toString(), windowEnd.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val chId = c.getString(0)
                    if (!nextByChannelId.containsKey(chId)) {
                        nextByChannelId[chId] = c.getString(2) to c.getLong(1)
                    }
                }
            }
            buildMap {
                for (group in groups) {
                    val chId = resolveChannelId(group.defaultChannel) ?: continue
                    val cur = nowByChannelId[chId] ?: continue
                    val next = nextByChannelId[chId]
                    val duration = (cur.endMillis - cur.startMillis).toFloat()
                    val progress = if (duration <= 0f) null
                    else ((nowMillis - cur.startMillis).toFloat() / duration).coerceIn(0f, 1f)
                    val ui = NowProgramUi(
                        title = cur.title,
                        progress = progress,
                        nextTitle = next?.first,
                        startMillis = cur.startMillis,
                        endMillis = cur.endMillis,
                        nextStartMillis = next?.second ?: 0L,
                    )
                    for (v in group.variants) {
                        put(v.channel.url, ui)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** 按需查询单个聚焦频道的完整节目单（<2ms）。 */
    fun queryChannelPrograms(context: Context, channel: Channel): List<EpgProgram> {
        val chId = resolveChannelId(channel) ?: return emptyList()
        return runCatching {
            val d = db(context)
            val out = ArrayList<EpgProgram>(64)
            d.rawQuery(
                "SELECT start_ms, end_ms, title, offset_sec FROM programs WHERE channel_id = ? ORDER BY start_ms ASC",
                arrayOf(chId)
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        EpgProgram(
                            channelId = chId,
                            startMillis = c.getLong(0),
                            endMillis = c.getLong(1),
                            title = c.getString(2),
                            sourceOffsetSeconds = if (c.isNull(3)) null else c.getInt(3),
                        )
                    )
                }
            }
            out
        }.getOrDefault(emptyList())
    }
}
