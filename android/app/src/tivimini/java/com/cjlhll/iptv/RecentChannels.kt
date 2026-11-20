package com.cjlhll.iptv

/**
 * t133: "recent channels" + "last channel" bookkeeping.
 *
 * Pure Kotlin (no Android deps) so the de-dup / cap / resolve / clear rules are unit-testable.
 * Persistence is a single compact string in [Prefs] (see [Prefs.getRecentChannels]).
 *
 * Entry encoding (per entry, fields separated by '\u0001', entries separated by '\u0002'):
 *   stableKey \u0001 number \u0001 title \u0001 logoUrl \u0001 url
 *
 * `stableKey` = channel.id when known (survives playlist re-parse), else the stream url.
 */
object RecentChannels {
    const val MAX = 10

    private const val FIELD = '\u0001'
    private const val ENTRY = '\u0002'

    data class Entry(
        val key: String,
        val number: Int,
        val title: String,
        val logoUrl: String?,
        val url: String,
    )

    /** Stable identity for a channel: its parsed id when available, else its url. */
    fun stableKey(channel: Channel): String =
        if (channel.id != 0L) "id:${channel.id}" else "url:${channel.url}"

    /**
     * Push a channel to the front. De-duplicates by stable key (a re-selected channel moves to the
     * front instead of appearing twice) and caps the list at [MAX].
     */
    fun push(
        current: List<Entry>,
        channel: Channel,
        number: Int,
        title: String = channel.title,
        logoUrl: String? = channel.logoUrl,
    ): List<Entry> {
        val key = stableKey(channel)
        val entry = Entry(
            key = key,
            number = number,
            title = title.ifBlank { channel.title },
            logoUrl = logoUrl,
            url = channel.url,
        )
        val without = current.filterNot { it.key == key }
        return (listOf(entry) + without).take(MAX)
    }

    fun encode(entries: List<Entry>): String = entries.joinToString(ENTRY.toString()) { e ->
        listOf(
            e.key,
            e.number.toString(),
            e.title.replace(FIELD, ' ').replace(ENTRY, ' '),
            (e.logoUrl ?: "").replace(FIELD, ' ').replace(ENTRY, ' '),
            e.url.replace(FIELD, ' ').replace(ENTRY, ' '),
        ).joinToString(FIELD.toString())
    }

    fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(ENTRY).mapNotNull { line ->
            val parts = line.split(FIELD)
            if (parts.size < 5) return@mapNotNull null
            val number = parts[1].toIntOrNull() ?: 0
            val title = parts[2]
            val logo = parts[3].ifBlank { null }
            val url = parts[4]
            if (parts[0].isBlank() || url.isBlank()) return@mapNotNull null
            Entry(parts[0], number, title, logo, url)
        }.take(MAX)
    }

    /**
     * Map entries onto the current playlist. Channels that no longer exist are skipped (never
     * throws, never returns a half-built row) — this is what makes a playlist refresh safe.
     */
    fun resolve(entries: List<Entry>, channels: List<Channel>): List<Pair<Entry, Channel>> {
        if (entries.isEmpty() || channels.isEmpty()) return emptyList()
        val byId = HashMap<Long, Channel>()
        val byUrl = HashMap<String, Channel>()
        for (c in channels) {
            if (c.id != 0L) byId.putIfAbsent(c.id, c)
            byUrl.putIfAbsent(c.url, c)
        }
        return entries.mapNotNull { e ->
            val channel = if (e.key.startsWith("id:")) {
                e.key.removePrefix("id:").toLongOrNull()?.let { byId[it] }
            } else {
                byUrl[e.key.removePrefix("url:")]
            } ?: byUrl[e.url]
            channel?.let { e to it }
        }
    }

    /** "Previous channel" = the most recent entry that is not the channel currently playing. */
    fun previous(entries: List<Entry>, currentKey: String?): Entry? =
        entries.firstOrNull { it.key != currentKey }
}
