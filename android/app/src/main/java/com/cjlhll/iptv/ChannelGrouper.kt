package com.cjlhll.iptv

data class ChannelVariant(
    val channel: Channel,
    val qualityLabel: String
)

data class ChannelGroup(
    val key: String,
    val displayTitle: String,
    val logoUrl: String?,
    val group: String?,
    val variants: List<ChannelVariant>,
    val defaultVariantIndex: Int,
    val groups: List<String> = emptyList(),
) {
    val defaultChannel: Channel get() = variants[defaultVariantIndex].channel
}

/** 带全局展示编号的频道（编号在分组过滤后保持不变）。 */
data class NumberedChannel(
    val channel: Channel,
    val number: Int,
)

object ChannelGrouper {
    private val cctvKeyRegex = Regex("""cctv\d{1,2}\+?""")
    private val cctvPrimaryKeyRegex = Regex("""cctv\d{1,2}\+?(?:#(?:4k|8k))?""")
    private val ultraHdEditionRegex = Regex("""(?i)(8k|4k|uhd|超高清)""")
    private val cctvInferRegex = Regex("""(?i)cctv[\s\-]?(\d{1,2}\+?|4k?)""")
    private val cctvTvgIdRegex = Regex("""(?i)^cctv(\d{1,2}\+?)""")
    private val satelliteRegex = Regex("""卫视$""")
    private val cartoonNameRegex = Regex("""(?i)(金鹰卡通|卡酷|嘉佳|优漫|哈哈|炫动|CCTV14|CCTV-14)""")
    private val otherChannelRegex = Regex("""(?i)^凤凰""")
    private val cctvCartoonNumbers = setOf("14")
    private val bracketUhdrRegex = Regex("""\[(4K\s*(?:HDR|SDR)|8K|4K|UHD|FHD|HD|SD)\]""", RegexOption.IGNORE_CASE)
    private val bracketResolutionRegex = Regex("""\[(2160|1440|1080|720|576|480|360)([pi])?\]""", RegexOption.IGNORE_CASE)
    private val titleEditionSuffixRegex = Regex("""\[(4K\s*(?:HDR|SDR)|8K|4K|UHD)\]""", RegexOption.IGNORE_CASE)
    private val url1080Regex = Regex("""(?<![0-9])1080[pi](?![0-9])""")
    private val url720Regex = Regex("""(?<![0-9])720p(?![0-9])""")
    private val url480Regex = Regex("""(?<![0-9])480p(?![0-9])""")

    private val groupSortOrder = listOf("央视频道", "卫视频道", "卡通频道", "其他频道", "未分组")

    fun group(channels: List<Channel>): List<ChannelGroup> {
        if (channels.isEmpty()) return emptyList()

        val keyToGroupIndex = HashMap<String, Int>()
        val groupedMembers = mutableListOf<MutableList<Channel>>()

        for (channel in channels) {
            val keys = mergeKeys(channel)
            val existingIndex = keys.firstNotNullOfOrNull { keyToGroupIndex[it] }

            if (existingIndex != null) {
                groupedMembers[existingIndex].add(channel)
                for (key in keys) {
                    keyToGroupIndex[key] = existingIndex
                }
            } else {
                val newIndex = groupedMembers.size
                groupedMembers.add(mutableListOf(channel))
                for (key in keys) {
                    keyToGroupIndex[key] = newIndex
                }
            }
        }

        return groupedMembers.map { members ->
            // 不信任 M3U response-time（大量占位 120ms）；默认优先带 logo 的源
            val variants = members.mapIndexed { index, channel ->
                ChannelVariant(channel, sourceLabel(channel, index))
            }
            val defaultIndex = variants.indexOfFirst { !it.channel.logoUrl.isNullOrBlank() }
                .takeIf { it >= 0 } ?: 0
            val defaultChannel = variants[defaultIndex].channel
            val logoUrl = members.firstNotNullOfOrNull { it.logoUrl?.takeIf { url -> url.isNotBlank() } }
            val allKeys = members.flatMap { mergeKeys(it).asIterable() }.toSet()
            val resolvedGroups = pickGroupTitles(members).ifEmpty {
                listOfNotNull(pickGroupTitle(members) ?: resolveGroup(defaultChannel))
            }
            ChannelGroup(
                key = pickPrimaryKey(allKeys),
                displayTitle = displayTitleForGroup(members, defaultChannel),
                logoUrl = logoUrl,
                group = resolvedGroups.firstOrNull(),
                variants = variants,
                defaultVariantIndex = defaultIndex,
                groups = resolvedGroups,
            )
        }
    }

    fun findGroupIndexByUrl(groups: List<ChannelGroup>, url: String?): Int? {
        if (url.isNullOrBlank()) return null
        return groups.indexOfFirst { group ->
            group.variants.any { it.channel.url == url }
        }.takeIf { it >= 0 }
    }

    fun findVariantIndexByUrl(group: ChannelGroup, url: String?): Int {
        if (url.isNullOrBlank()) return group.defaultVariantIndex
        return group.variants.indexOfFirst { it.channel.url == url }
            .takeIf { it >= 0 } ?: group.defaultVariantIndex
    }

    fun findBestGroupVariant(
        groups: List<ChannelGroup>,
        lastUrl: String?,
        lastTitle: String?,
        previousGroups: List<ChannelGroup>? = null,
        previousGroupIndex: Int? = null,
    ): Pair<Int, Int> {
        if (groups.isEmpty()) return 0 to 0

        if (!lastUrl.isNullOrBlank()) {
            findGroupIndexByUrl(groups, lastUrl)?.let { groupIndex ->
                val group = groups[groupIndex]
                return groupIndex to findVariantIndexByUrl(group, lastUrl)
            }
        }

        if (!lastTitle.isNullOrBlank()) {
            groups.forEachIndexed { groupIndex, group ->
                group.variants.forEachIndexed { variantIndex, variant ->
                    if (variant.channel.title == lastTitle) return groupIndex to variantIndex
                }
                if (group.displayTitle == lastTitle) {
                    return groupIndex to group.defaultVariantIndex
                }
            }
        }

        if (previousGroups != null && previousGroupIndex != null) {
            previousGroups.getOrNull(previousGroupIndex)?.let { oldGroup ->
                groups.indexOfFirst { group ->
                    group.key == oldGroup.key || group.displayTitle == oldGroup.displayTitle
                }.takeIf { it >= 0 }?.let { matchedIndex ->
                    return matchedIndex to groups[matchedIndex].defaultVariantIndex
                }
            }
        }

        return 0 to groups.first().defaultVariantIndex
    }

    fun containsUrl(groups: List<ChannelGroup>, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return groups.any { group -> group.variants.any { it.channel.url == url } }
    }

    fun findRecoveryVariant(
        groups: List<ChannelGroup>,
        failedUrl: String?,
        lastTitle: String?,
        previousGroups: List<ChannelGroup>? = null,
        previousGroupIndex: Int? = null,
    ): Pair<Int, Int>? {
        if (groups.isEmpty()) return null
        val (groupIndex, currentVariantIndex) = findBestGroupVariant(
            groups,
            failedUrl,
            lastTitle,
            previousGroups,
            previousGroupIndex,
        )
        val group = groups.getOrNull(groupIndex) ?: return null

        if (!failedUrl.isNullOrBlank()) {
            group.variants.forEachIndexed { index, variant ->
                if (variant.channel.url != failedUrl) {
                    return groupIndex to index
                }
            }
        }

        return groupIndex to group.defaultVariantIndex
    }

    const val ALL_GROUP = "全部"
    const val FAVORITES_GROUP = "收藏夹"

    fun displayGroupName(group: ChannelGroup): String {
        return group.groups.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: group.group?.takeIf { it.isNotBlank() }
            ?: "未分组"
    }

    fun groupNames(group: ChannelGroup): List<String> =
        group.groups.filter { it.isNotBlank() }.ifEmpty { listOf(displayGroupName(group)) }

    fun allRawGroupNames(channelGroups: List<ChannelGroup>): List<String> {
        val names = LinkedHashSet<String>()
        for (group in channelGroups) {
            names.addAll(groupNames(group))
        }
        return names.toList()
    }

    fun sortedDrawerGroups(
        channelGroups: List<ChannelGroup>,
        favoriteUrls: Set<String> = emptySet(),
        showFavorites: Boolean = true,
        showAll: Boolean = true,
        groupConfig: Prefs.SourceGroupConfig = Prefs.SourceGroupConfig(),
    ): List<String> {
        val rawNames = LinkedHashSet<String>()
        for (group in channelGroups) {
            rawNames.addAll(groupNames(group))
        }
        val visible = rawNames.filter { it !in groupConfig.hiddenGroups }
        val sorted = when (groupConfig.sortMode) {
            "NAME" -> visible.sorted()
            "MANUAL" -> {
                val ordered = groupConfig.manualOrder.filter { it in visible }
                val remaining = visible.filter { it !in ordered }
                ordered + remaining
            }
            else -> visible
        }
        val result = ArrayList<String>(sorted.size + 2)
        if (showAll) result.add(ALL_GROUP)
        if (showFavorites && favoriteUrls.isNotEmpty()) result.add(FAVORITES_GROUP)
        result.addAll(sorted)
        return result.ifEmpty { listOf(ALL_GROUP) }
    }

    internal fun resolveGroup(channel: Channel): String? {
        val raw = channel.group?.trim()?.takeIf { it.isNotBlank() }
        raw?.let { normalizeGroupAlias(it, channel) }?.let { return it }
        return inferGroupFromMetadata(channel)
    }

    fun displayChannels(groups: List<ChannelGroup>): List<Channel> {
        return groups.map { group ->
            val channel = group.defaultChannel
            channel.copy(
                title = EpgNormalize.displayName(group.displayTitle),
                logoUrl = group.logoUrl ?: channel.logoUrl,
            )
        }
    }

    /**
     * 全局展示编号：合并后的频道顺序，从 1 起。同组多线路共用一个号，
     * 分组过滤不会重新编号（A04/A05）。
     */
    fun numberedDisplayChannels(groups: List<ChannelGroup>): List<NumberedChannel> {
        return groups.mapIndexed { index, group ->
            NumberedChannel(
                channel = group.defaultChannel.copy(
                    title = EpgNormalize.displayName(group.displayTitle),
                    logoUrl = group.logoUrl ?: group.defaultChannel.logoUrl,
                ),
                number = index + 1,
            )
        }
    }

    /** url -> 全局编号（含全部线路变体，便于信息条/数字跳台/节目列表共享同一号）。 */
    fun globalNumberByUrl(groups: List<ChannelGroup>): Map<String, Int> {
        val map = HashMap<String, Int>(groups.size * 2)
        groups.forEachIndexed { index, group ->
            group.variants.forEach { variant ->
                map[variant.channel.url] = index + 1
                if (variant.channel.id != 0L) {
                    map["id:${variant.channel.id}"] = index + 1
                }
            }
        }
        return map
    }

    /** 条目唯一标识 key（优先非 0 id，避免同 URL 不同请求头互相覆盖）。 */
    fun channelIdentityKey(channel: Channel): String =
        if (channel.id != 0L) "id:${channel.id}" else "url:${channel.url}"

    /** 按分组名过滤后的展示频道，保留全局编号（可传入预计算的 [precomputedAll] 避免重复构造）。 */
    fun numberedDisplayChannelsForGroup(
        groups: List<ChannelGroup>,
        groupName: String,
        favoriteUrls: Set<String> = emptySet(),
        precomputedAll: List<NumberedChannel>? = null,
    ): List<NumberedChannel> {
        val all = precomputedAll ?: numberedDisplayChannels(groups)
        if (groupName == ALL_GROUP) return all
        if (groupName == FAVORITES_GROUP) {
            return groups.mapIndexedNotNull { index, group ->
                if (group.variants.any { it.channel.url in favoriteUrls }) all.getOrNull(index) else null
            }
        }
        return groups.mapIndexedNotNull { index, group ->
            if (groupName in groupNames(group)) all.getOrNull(index) else null
        }
    }

    /** 该分组在合并列表中的全局编号（从 1 起）。 */
    fun globalNumberOfGroup(groups: List<ChannelGroup>, groupIndex: Int): Int =
        if (groupIndex in groups.indices) groupIndex + 1 else 0

    /** 根据全局编号查找分组下标（0 基）。 */
    fun groupIndexForNumber(groups: List<ChannelGroup>, number: Int): Int? {
        val idx = number - 1
        return if (idx in groups.indices) idx else null
    }

    fun allChannels(groups: List<ChannelGroup>): List<Channel> {
        return groups.flatMap { group -> group.variants.map { it.channel } }
    }

    internal fun canonicalKeys(channel: Channel): Set<String> {
        val keys = LinkedHashSet<String>()
        channel.tvgName?.takeIf { it.isNotBlank() }?.let { keys.addAll(EpgNormalize.keys(it)) }
        channel.tvgId?.takeIf { it.isNotBlank() }?.let { id ->
            val base = id.substringBefore('@').trim()
            if (base.isNotBlank()) keys.addAll(EpgNormalize.keys(base))
            keys.addAll(EpgNormalize.keys(id))
        }
        keys.addAll(EpgNormalize.keys(channel.title))
        return keys
    }

    internal fun mergeKeys(channel: Channel): Set<String> {
        if (channel.entryIndex >= 0) {
            return setOf("entry:${channel.entryIndex}")
        }
        val keys = LinkedHashSet<String>()

        val label = EpgNormalize.displayName(
            channel.tvgName?.takeIf { it.isNotBlank() } ?: channel.title
        )
        keys.add(EpgNormalize.key(label))

        channel.tvgName?.takeIf { it.isNotBlank() }?.let { name ->
            keys.addAll(EpgNormalize.keys(name))
        }

        channel.tvgId?.takeIf { it.isNotBlank() }?.let { id ->
            val base = id.substringBefore('@').trim()
            if (base.isNotBlank()) {
                keys.add(EpgNormalize.key(base))
                keys.addAll(EpgNormalize.keys(base))
            }
        }

        keys.add(EpgNormalize.key(EpgNormalize.displayName(channel.title)))
        EpgNormalize.keys(channel.title)
            .filter { it.matches(cctvKeyRegex) }
            .forEach { keys.add(it) }

        val suffix = editionSuffix(channel)
        return keys
            .filter { it.isNotBlank() && it != "unknown" }
            .map { if (suffix.isEmpty()) it else "$it$suffix" }
            .toSet()
    }

    private fun editionSuffix(channel: Channel): String {
        val text = "${channel.tvgName.orEmpty()} ${channel.title}"
        val match = ultraHdEditionRegex.find(text)?.groupValues?.getOrNull(1)?.lowercase() ?: return ""
        return if (match == "8k") "#8k" else "#4k"
    }

    internal fun primaryGroupKey(channel: Channel): String {
        return pickPrimaryKey(mergeKeys(channel).ifEmpty { canonicalKeys(channel) })
    }

    private fun pickPrimaryKey(keys: Set<String>): String {
        if (keys.isEmpty()) return "unknown"
        keys.firstOrNull { it.matches(cctvPrimaryKeyRegex) }?.let { return it }
        return keys.minByOrNull { it.length } ?: keys.first()
    }

    private fun displayTitleForGroup(members: List<Channel>, defaultChannel: Channel): String {
        val raw = defaultChannel.title.trim().takeIf { it.isNotEmpty() && "://" !in it }
            ?: members.firstNotNullOfOrNull { it.tvgName?.takeIf { name -> name.isNotBlank() } }
            ?: defaultChannel.title
        val cleaned = EpgNormalize.displayName(raw)
        val editionTag = titleEditionSuffixRegex.find(raw)?.groupValues?.getOrNull(1)
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.uppercase()
        if (editionTag != null && cleaned.isNotEmpty()) {
            val baseToken = when {
                editionTag.startsWith("8K") -> "8K"
                editionTag.startsWith("4K") -> "4K"
                else -> editionTag
            }
            if (!cleaned.contains(baseToken, ignoreCase = true) && !cleaned.contains(editionTag, ignoreCase = true)) {
                return "$cleaned $editionTag"
            }
        }
        return cleaned
    }

    private fun pickGroupTitles(members: List<Channel>): List<String> {
        val out = LinkedHashSet<String>()
        for (m in members) {
            val rawList = if (m.groups.isNotEmpty()) m.groups else {
                m.group?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            }
            if (rawList.isNotEmpty()) {
                for (raw in rawList) {
                    normalizeGroupAlias(raw, m)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                }
            } else {
                inferGroupFromMetadata(m)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    private fun pickGroupTitle(members: List<Channel>): String? {
        val groups = members.mapNotNull { resolveGroup(it) }
        if (groups.isEmpty()) return null
        return groups
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { groupSortOrder.indexOf(it.key).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                    .thenBy { it.key }
            )
            .firstOrNull()
            ?.key
    }

    private fun normalizeGroupAlias(raw: String, channel: Channel): String? {
        if (raw.equals("General", ignoreCase = true)) {
            return inferGroupFromMetadata(channel)
        }
        if (raw == "央视台") return "央视频道"
        if (raw == "体育频道" && isCctvChannel(channel)) return "央视频道"
        return raw
    }

    private fun inferGroupFromMetadata(channel: Channel): String? {
        val texts = listOfNotNull(
            channel.tvgId?.substringBefore('@')?.trim()?.takeIf { it.isNotBlank() },
            channel.tvgName?.trim()?.takeIf { it.isNotBlank() },
            channel.title.trim().takeIf { it.isNotBlank() },
        )
        for (text in texts) {
            if (cartoonNameRegex.containsMatchIn(text) || cctvCartoonNumber(text) != null) {
                return "卡通频道"
            }
            if (otherChannelRegex.containsMatchIn(text)) {
                return "其他频道"
            }
            if (isCctvText(text)) {
                return "央视频道"
            }
            val display = EpgNormalize.displayName(text)
            if (satelliteRegex.containsMatchIn(display)) {
                return "卫视频道"
            }
        }
        return null
    }

    private fun isCctvChannel(channel: Channel): Boolean {
        return listOfNotNull(channel.tvgId, channel.tvgName, channel.title).any { isCctvText(it) }
    }

    private fun isCctvText(text: String): Boolean {
        if (cctvTvgIdRegex.containsMatchIn(text.substringBefore('.'))) return true
        return cctvInferRegex.containsMatchIn(text)
    }

    private fun cctvCartoonNumber(text: String): String? {
        val number = cctvTvgIdRegex.find(text.substringBefore('.'))?.groupValues?.getOrNull(1)
            ?: cctvInferRegex.find(text)?.groupValues?.getOrNull(1)
        return number?.removeSuffix("+")?.takeIf { it in cctvCartoonNumbers }
    }

    private fun sourceLabel(channel: Channel, index: Int): String {
        val quality = qualityHint(channel)
        return if (quality != null) {
            "源${index + 1} · $quality"
        } else {
            "源${index + 1}"
        }
    }

    private fun qualityHint(channel: Channel): String? {
        channel.tvgId?.let { id ->
            val suffix = id.substringAfterLast('@', "")
            if (suffix.isNotBlank() && suffix != id) {
                return suffix.uppercase()
            }
        }

        bracketUhdrRegex.find(channel.title)?.groupValues?.getOrNull(1)?.let {
            return it.replace(Regex("""\s+"""), " ").trim().uppercase()
        }

        bracketResolutionRegex.find(channel.title)?.let { m ->
            val height = m.groupValues[1]
            val scan = m.groupValues[2].lowercase().ifEmpty { "p" }
            return "$height$scan"
        }

        Regex("""\((\d+[pP]|4[Kk]|HD|SD|FHD|UHD|蓝光|超清|高清|标清)\)""")
            .find(channel.title)?.groupValues?.getOrNull(1)?.let { return it }

        val pathAndQuery = channel.url.substringAfter("://", channel.url).substringAfter('/', "").lowercase()
        when {
            url1080Regex.containsMatchIn(pathAndQuery) -> return "1080p"
            url720Regex.containsMatchIn(pathAndQuery) -> return "720p"
            url480Regex.containsMatchIn(pathAndQuery) -> return "480p"
            pathAndQuery.contains("hd.m3u8") || pathAndQuery.contains("/hd/") -> return "HD"
            pathAndQuery.contains("sd.m3u8") || pathAndQuery.contains("/sd/") -> return "SD"
        }

        when {
            channel.title.contains("超清") || channel.title.contains("蓝光") -> return "超清"
            channel.title.contains("高清") -> return "高清"
            channel.title.contains("标清") -> return "标清"
        }

        return null
    }
}
