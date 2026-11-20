package com.cjlhll.iptv

/**
 * t133/t141: "recent channels" + "last channel" bookkeeping.
 *
 * Pure Kotlin (no Android deps) so de-dup / cap / resolve / clear rules are unit-testable.
 * Identity is the **channel group key** ([ChannelGroup.key]) — not the variant url/id — so a
 * quality/line switch inside the same channel never counts as a new channel (RECENT-01).
 *
 * Entry encoding (fields '\u0001', entries '\u0002'):
 *   groupKey \1 number \1 title \1 logoUrl \1 variantIndex \1 fallbackUrl
 * (5-field legacy rows decode with groupKey derived from the fallback url.)
 */
object RecentChannels {
    const val MAX = 10

    private const val FIELD = '\u0001'
    private const val ENTRY = '\u0002'

    data class Entry(
        val groupKey: String,
        val number: Int,
        val title: String,
        val logoUrl: String?,
        val variantIndex: Int,
        val fallbackUrl: String,
    )

    /** Legacy-compatible fallback identity when only a channel is available (no group). */
    fun stableKey(channel: Channel): String =
        if (channel.id != 0L) "id:${channel.id}" else "url:${channel.url}"

    private fun sanitize(value: String): String =
        value.replace(FIELD, ' ').replace(ENTRY, ' ').trim()

    /**
     * Push a group to the front. De-duplicates by **group key** (switching quality/variant inside the
     * same channel updates its variant instead of adding a row) and caps at [MAX].
     */
    fun push(
        current: List<Entry>,
        group: ChannelGroup,
        number: Int,
        variantIndex: Int,
        title: String = group.displayTitle,
        logoUrl: String? = group.logoUrl,
    ): List<Entry> {
        val safeVariant = variantIndex.coerceIn(0, (group.variants.size - 1).coerceAtLeast(0))
        val entry = Entry(
            groupKey = sanitize(group.key),
            number = number,
            title = sanitize(title.ifBlank { group.displayTitle }),
            logoUrl = logoUrl?.let { sanitize(it) }?.takeIf { it.isNotBlank() },
            variantIndex = safeVariant,
            fallbackUrl = sanitize(group.variants.getOrNull(safeVariant)?.channel?.url ?: ""),
        )
        val without = current.filterNot { it.groupKey == entry.groupKey }
        return (listOf(entry) + without).take(MAX)
    }

    fun encode(entries: List<Entry>): String = entries.joinToString(ENTRY.toString()) { e ->
        listOf(
            sanitize(e.groupKey),
            e.number.toString(),
            sanitize(e.title),
            sanitize(e.logoUrl ?: ""),
            e.variantIndex.toString(),
            sanitize(e.fallbackUrl),
        ).joinToString(FIELD.toString())
    }

    fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(ENTRY).mapNotNull { line ->
            val parts = line.split(FIELD)
            when {
                parts.size >= 6 -> {
                    val groupKey = parts[0].trim()
                    val fallbackUrl = parts[5].trim()
                    if (groupKey.isBlank() && fallbackUrl.isBlank()) return@mapNotNull null
                    Entry(
                        groupKey = groupKey.ifBlank { "url:$fallbackUrl" },
                        number = parts[1].toIntOrNull() ?: 0,
                        title = parts[2],
                        logoUrl = parts[3].ifBlank { null },
                        variantIndex = parts[4].toIntOrNull() ?: 0,
                        fallbackUrl = fallbackUrl,
                    )
                }
                parts.size >= 5 -> {
                    // legacy row (t133 format): key \1 number \1 title \1 logo \1 url
                    // The old key was a variant identity, not a group key — resolve via url instead.
                    val url = parts[4].trim()
                    if (url.isBlank()) return@mapNotNull null
                    Entry(
                        groupKey = "url:$url",
                        number = parts[1].toIntOrNull() ?: 0,
                        title = parts[2],
                        logoUrl = parts[3].ifBlank { null },
                        variantIndex = 0,
                        fallbackUrl = url,
                    )
                }
                else -> null
            }
        }.take(MAX)
    }

    /**
     * Map entries onto the current playlist by **group key**; a missing group is skipped, and the
     * fallback url/legacy row can still resolve through the channel urls. Never throws.
     */
    fun resolve(
        entries: List<Entry>,
        groups: List<ChannelGroup>,
    ): List<Triple<Entry, ChannelGroup, Channel>> {
        if (entries.isEmpty() || groups.isEmpty()) return emptyList()
        val byKey = HashMap<String, Int>()
        groups.forEachIndexed { index, g -> byKey[g.key] = index }
        val byUrl = HashMap<String, Int>()
        groups.forEachIndexed { index, g -> g.variants.forEach { byUrl.putIfAbsent(it.channel.url, index) } }

        return entries.mapNotNull { e ->
            val groupIndex: Int? = byKey[e.groupKey]
                ?: if (e.groupKey.startsWith("url:")) byUrl[e.groupKey.removePrefix("url:")] else null
                ?: byUrl[e.fallbackUrl]
            if (groupIndex == null) return@mapNotNull null
            val group = groups[groupIndex]
            val variant = e.variantIndex.coerceIn(0, (group.variants.size - 1).coerceAtLeast(0))
            Triple(e, group, group.variants[variant].channel)
        }
    }

    /** "Previous channel" = the most recent group that is not the currently playing group. */
    fun previous(entries: List<Entry>, currentGroupKey: String?): Entry? {
        if (entries.isEmpty()) return null
        return entries.firstOrNull { it.groupKey != currentGroupKey }
    }
}
