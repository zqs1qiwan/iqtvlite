package com.cjlhll.iptv

/**
 * t169: the **only** place a background coroutine may learn which media session is live.
 *
 * ExoPlayer enforces `applicationLooper` on every accessor (`currentMediaItem`, `playbackState`, …),
 * so touching the player from `Dispatchers.Default` throws
 * `IllegalStateException: Player is accessed on the wrong thread` and kills the process.
 * [StreamPlayback] therefore snapshots the media id **on the main thread** at `prepare()` and every
 * later check (including the 60 s debug summary job) reads this snapshot instead of the player.
 *
 * Pure Kotlin → unit-testable.
 */
object PlaybackSession {
    @Volatile private var currentId: String? = null

    /** Called from the main thread right after a media source is prepared. */
    fun begin(mediaId: String) {
        currentId = mediaId
    }

    /** True when [mediaId] is still the session that owns the player. Safe from any thread. */
    fun isCurrent(mediaId: String): Boolean = currentId != null && currentId == mediaId

    /** Snapshot of the current id (null before the first prepare / after release). */
    fun current(): String? = currentId

    /** Called on release; a stale background job then logs nothing. */
    fun clear(mediaId: String? = null) {
        if (mediaId == null || currentId == mediaId) currentId = null
    }
}
