package com.cjlhll.iptv

/** Owner and address are one value: a catch-up address can never inherit another live row's headers. */
data class MediaPlaybackRequest(val channel: Channel, val streamUrl: String = channel.url, val isCatchup: Boolean = false) {
    fun httpHeaders(): Map<String, String> = buildMap {
        put("User-Agent", channel.userAgent ?: "VLC/3.0.20 LibVLC/3.0.20")
        channel.referrer?.let { put("Referer", it) }
    }
}
data class PreparedPlayback(val revision: Long, val mediaId: String, val request: MediaPlaybackRequest)
data class PlaybackFailure(val playback: PreparedPlayback)
data class PlaylistSelection(val groups: List<ChannelGroup>, val groupIndex: Int, val channel: Channel)
data class PlaylistApplication(val groups: List<ChannelGroup>, val groupIndex: Int, val variantIndex: Int, val channel: Channel, val keepUnlistedOwner: Boolean = false)
data class LiveOwnerView(val channel: Channel?, val groupIndex: Int, val variantIndex: Int, val retainedOutsideList: Boolean) {
    val number get() = if (groupIndex >= 0) groupIndex+1 else 0
}
data class ReturnToLive(val request: MediaPlaybackRequest, val groupIndex: Int?)
data class PlaylistCommit(val groupIndex: Int?, val variantIndex: Int?, val prepare: MediaPlaybackRequest?)

/** Main-thread intent/session gate, not a second decoder/navigation state machine. */
class PlaybackIntent {
    private var revision = 0L
    private var selecting = false
    private var unlistedLive: MediaPlaybackRequest? = null
    val retainsUnlistedLive get() = unlistedLive != null
    var request: MediaPlaybackRequest? = null
        private set
    var active: PreparedPlayback? = null
        private set
    fun ticket(): Long = revision
    fun beginSelection(): Long { unlistedLive=null; selecting = true; return ++revision }
    fun matches(ticket: Long) = ticket == revision
    fun canApply(ticket: Long) = matches(ticket) && !selecting
    fun isCurrent(playback: PreparedPlayback) = canApply(playback.revision) && active == playback
    fun retain(value: MediaPlaybackRequest) { revision++; selecting=false; request=value; active=null }
    fun prepared(value: MediaPlaybackRequest, mediaId: String): PreparedPlayback {
        revision++; selecting=false; request=value
        return PreparedPlayback(revision, mediaId, value).also { active=it }
    }
    fun suspendPlayer() { revision++; selecting=false; active=null }

    /** Actual owner wins over leftover browse indexes only for the explicit missing-row return. */
    fun liveOwner(groups: List<ChannelGroup>, groupIndex: Int, variantIndex: Int): LiveOwnerView {
        unlistedLive?.let { held ->
            val position = positionOf(groups, held.channel)
            return LiveOwnerView(held.channel, position?.first ?: -1, position?.second ?: -1, position == null)
        }
        val group = groups.getOrNull(groupIndex)
        val variant = if (group?.variants?.getOrNull(variantIndex) != null) variantIndex else group?.defaultVariantIndex ?: -1
        val channel = group?.variants?.getOrNull(variant)?.channel ?: request?.takeIf { !it.isCatchup }?.channel
        return LiveOwnerView(channel, if (group != null) groupIndex else -1, variant, false)
    }

    fun returnToLive(catchup: CatchupPlayRequest, groups: List<ChannelGroup>): ReturnToLive? {
        val owner = request?.channel?.takeIf { it.url == catchup.liveUrl && (catchup.owner == null || it.id == catchup.owner.id) }
            ?: catchup.owner ?: ChannelGrouper.allChannels(groups).firstOrNull { it.url == catchup.liveUrl } ?: return null
        val position = positionOf(groups, owner)
        val channel = position?.let { groups[it.first].variants[it.second].channel } ?: owner
        val live = MediaPlaybackRequest(channel)
        beginSelection()
        if (position == null) unlistedLive = live
        return ReturnToLive(live, position?.first)
    }

    /** Same commit policy is used by Activity and regression fixtures. Null indexes preserve browsing context. */
    fun commitPlaylist(update: PlaylistApplication, canPrepare: Boolean, initial: Boolean, recovery: Boolean): PlaylistCommit {
        if (update.keepUnlistedOwner) {
            return PlaylistCommit(null, null, if (canPrepare && !initial && recovery) unlistedLive else null)
        }
        val rejoined = unlistedLive != null
        unlistedLive = null
        val next = MediaPlaybackRequest(update.channel)
        val prepare = canPrepare && (rejoined || (!initial && (recovery || request?.channel?.url != next.streamUrl)))
        // A rejoin with no player must also invalidate older, still parsing missing-row updates.
        if (rejoined && !canPrepare) { revision++; active=null }
        return PlaylistCommit(update.groupIndex, update.variantIndex, if (prepare) next else null)
    }

    private fun positionOf(groups: List<ChannelGroup>, owner: Channel): Pair<Int,Int>? {
        var byId: Pair<Int,Int>? = null
        groups.forEachIndexed { g, group -> group.variants.forEachIndexed { v, row ->
            if (owner.id != 0L && row.channel.id == owner.id) {
                if (row.channel.url == owner.url) return g to v
                if (byId == null) byId=g to v
            } else if (owner.id == 0L && row.channel.url == owner.url) return g to v
        } }
        return byId
    }

    companion object {
        fun catchup(channels: List<Channel>, catchup: CatchupPlayRequest, retained: MediaPlaybackRequest?): MediaPlaybackRequest? {
            val declared = catchup.owner
            if (declared != null && declared.url != catchup.liveUrl) return null
            if (retained?.isCatchup == true && retained.channel.url == catchup.liveUrl && retained.streamUrl == catchup.catchupUrl &&
                (declared == null || declared.id == retained.channel.id)) return retained
            val owner = declared ?: channels.firstOrNull { it.url == catchup.liveUrl } ?: return null
            return MediaPlaybackRequest(owner, catchup.catchupUrl, true)
        }
    }

    /** Production pipeline used by both cache updates and failed-session refreshes.
     * No suspend point exists between the last guard and the synchronous commit callback.
     */
    suspend fun updatePlaylist(
        ticket: Long,
        selection: () -> PlaylistSelection,
        load: suspend () -> String?,
        parse: suspend (String) -> List<ChannelGroup>,
        recovery: Boolean,
        stillValid: () -> Boolean = { true },
        apply: (PlaylistApplication) -> Unit,
    ): Boolean {
        fun valid() = canApply(ticket) && stillValid()
        if (!valid()) return false
        val content = load() ?: return false
        if (!valid()) return false
        val before = selection()
        val parsed = parse(content)
        if (!valid() || parsed.isEmpty()) return false
        // Read again at commit time, never a composition-time val captured by a Unit effect.
        val latest = selection()
        if (latest.channel.url != before.channel.url || latest.channel.id != before.channel.id) return false
        val held = unlistedLive
        if (held != null && positionOf(parsed, held.channel) == null) {
            if (!valid()) return false
            apply(PlaylistApplication(parsed, latest.groupIndex, 0, held.channel, keepUnlistedOwner=true))
            return true
        }
        val previousUrl = latest.channel.url
        var exact: Pair<Int,Int>? = null
        var identified: Pair<Int,Int>? = null
        var legacyUrl: Pair<Int,Int>? = null
        var reusedUrl = false
        parsed.forEachIndexed { group, row -> row.variants.forEachIndexed { variant, item ->
            val sameUrl = item.channel.url == previousUrl
            val sameId = latest.channel.id != 0L && item.channel.id == latest.channel.id
            if (sameId && identified == null) identified = group to variant
            if (sameId && sameUrl && exact == null) exact = group to variant
            if (sameUrl && (latest.channel.id == 0L || item.channel.id == 0L) && legacyUrl == null) legacyUrl = group to variant
            if (sameUrl && latest.channel.id != 0L && item.channel.id != 0L && !sameId) reusedUrl = true
        } }
        val match = exact ?: identified ?: legacyUrl
        // A reused URL belonging to another known identity is not B. Fall back by B's title/group.
        val safePreviousUrl = previousUrl.takeUnless { reusedUrl }
        var target = match
            ?: ChannelGrouper.findBestGroupVariant(parsed, null, latest.channel.title, latest.groups, latest.groupIndex)
        if (match == null && recovery) {
            ChannelGrouper.findRecoveryVariant(parsed, safePreviousUrl, latest.channel.title, latest.groups, latest.groupIndex)?.let { target=it }
        }
        val group = target.first.coerceIn(0, parsed.lastIndex)
        val variant = target.second.coerceIn(0, parsed[group].variants.lastIndex)
        if (!valid()) return false
        apply(PlaylistApplication(parsed, group, variant, parsed[group].variants[variant].channel))
        return true
    }

    suspend fun recover(
        failure: PlaybackFailure,
        selection: () -> PlaylistSelection,
        load: suspend () -> String?,
        parse: suspend (String) -> List<ChannelGroup>,
        stillValid: () -> Boolean = { true },
        apply: (PlaylistApplication) -> Unit,
    ): Boolean {
        val failed = failure.playback
        if (failed.request.isCatchup || !isCurrent(failed)) return false
        return updatePlaylist(failed.revision, selection, load, parse, true,
            { isCurrent(failed) && stillValid() }, apply)
    }
}
