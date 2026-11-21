package com.cjlhll.iptv

import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap

data class EpgProgram(
    val channelId: String,
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val sourceOffsetSeconds: Int?
) : Serializable

data class EpgData(
    val programsByChannelId: Map<String, List<EpgProgram>>,
    val normalizedDisplayNameToChannelId: Map<String, String>
) : Serializable {
    @Transient
    private var resolvedChannelIdCache: ConcurrentHashMap<String, String>? = null

    private fun channelIdCache(): ConcurrentHashMap<String, String> {
        val existing = resolvedChannelIdCache
        if (existing != null) return existing
        val created = ConcurrentHashMap<String, String>()
        resolvedChannelIdCache = created
        return created
    }

    fun resolveChannelId(channel: Channel): String? {
        val key = channel.url
        val cache = channelIdCache()
        val cached = cache[key]
        if (cached != null) return if (cached == NOT_FOUND) null else cached

        val resolved = resolveChannelIdUncached(channel)
        cache[key] = resolved ?: NOT_FOUND
        return resolved
    }

    private fun resolveChannelIdUncached(channel: Channel): String? {
        val tvgId = channel.tvgId?.trim().orEmpty()
        if (tvgId.isNotEmpty()) {
            if (programsByChannelId.containsKey(tvgId)) return tvgId
            for (key in EpgNormalize.keys(tvgId)) {
                val id = normalizedDisplayNameToChannelId[key]
                if (id != null && programsByChannelId.containsKey(id)) return id
            }
        }

        val tvgName = channel.tvgName?.trim().orEmpty()
        if (tvgName.isNotEmpty()) {
            for (key in EpgNormalize.keys(tvgName)) {
                val id = normalizedDisplayNameToChannelId[key]
                if (id != null && programsByChannelId.containsKey(id)) return id
            }
        }

        for (key in EpgNormalize.keys(channel.title)) {
            val id = normalizedDisplayNameToChannelId[key]
            if (id != null && programsByChannelId.containsKey(id)) return id
        }
        return null
    }

    fun nowProgramTitle(channel: Channel, nowMillis: Long): String? {
        val channelId = resolveChannelId(channel) ?: return null
        val programs = programsByChannelId[channelId] ?: return null
        if (programs.isEmpty()) return null

        var lo = 0
        var hi = programs.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val p = programs[mid]
            if (nowMillis < p.startMillis) {
                hi = mid - 1
            } else if (nowMillis >= p.endMillis) {
                lo = mid + 1
            } else {
                return p.title
            }
        }

        val insert = lo
        val prev = programs.getOrNull(insert - 1)
        val next = programs.getOrNull(insert)

        val prevDistance = prev?.let { kotlin.math.abs(nowMillis - it.endMillis) }
        val nextDistance = next?.let { kotlin.math.abs(it.startMillis - nowMillis) }

        val nearest = when {
            prev == null -> next
            next == null -> prev
            prevDistance == null -> next
            nextDistance == null -> prev
            prevDistance <= nextDistance -> prev
            else -> next
        } ?: return null

        val maxSkewMillis = 72L * 60L * 60L * 1000L
        val distance = minOf(
            kotlin.math.abs(nowMillis - nearest.startMillis),
            kotlin.math.abs(nowMillis - nearest.endMillis)
        )
        if (distance > maxSkewMillis) return null

        return if (nowMillis < nearest.startMillis) nearest.title else "回看：${nearest.title}"
    }

    fun nowProgram(channel: Channel, nowMillis: Long): EpgProgram? {
        val channelId = resolveChannelId(channel) ?: return null
        val programs = programsByChannelId[channelId] ?: return null
        if (programs.isEmpty()) return null

        var lo = 0
        var hi = programs.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val p = programs[mid]
            if (nowMillis < p.startMillis) {
                hi = mid - 1
            } else if (nowMillis >= p.endMillis) {
                lo = mid + 1
            } else {
                return p
            }
        }
        return null
    }

    /** 当前节目之后的下一个节目（用于信息条“接下来”）。 */
    fun nextProgram(channel: Channel, nowMillis: Long): EpgProgram? {
        val channelId = resolveChannelId(channel) ?: return null
        val programs = programsByChannelId[channelId] ?: return null
        if (programs.isEmpty()) return null
        val current = nowProgram(channel, nowMillis)
        val currentIndex = if (current != null) programs.indexOf(current) else -1
        if (currentIndex >= 0) {
            return programs.getOrNull(currentIndex + 1)
        }
        // 无正在播出的节目时，返回下一个将要开始的节目
        return programs.firstOrNull { it.startMillis > nowMillis }
    }

    /** 该频道是否有任何节目数据（区别于“尚未配置 EPG”）。 */
    fun hasProgramsFor(channel: Channel): Boolean {
        val channelId = resolveChannelId(channel) ?: return false
        return !programsByChannelId[channelId].isNullOrEmpty()
    }

    companion object {
        private const val NOT_FOUND = "\u0000"
    }
}

data class NowProgramUi(
    val title: String,
    val progress: Float?,
    val nextTitle: String? = null,
    // t24（INFO_CARD_V3 §9.2）：节目起止，供右侧节目行显示 “HH:mm–HH:mm · 剩余 N 分钟”。
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
)
