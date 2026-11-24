package com.cjlhll.iptv

/**
 * t159: the authoritative in-memory state of the recent-channels list, with the write rules that
 * make "clear" durable.
 *
 * Pure Kotlin with a [Storage] seam so the exact production logic can be unit-tested (no Android,
 * no mirror implementation).
 *
 * Rules:
 *  1. `record()` pushes onto the in-memory list and bumps [generation]; only the newest generation
 *     may be flushed, so a debounced write can never resurrect a superseded list.
 *  2. `clearAll()` bumps [generation], drops the pending value, empties the list, persists an empty
 *     value **and** a `cleared` marker in one storage call.
 *  3. `flush()` refuses to write when the list was cleared and is still empty — this is what stops
 *     ON_STOP/ON_PAUSE/onDispose from writing the old list back after the user cleared it.
 */
class RecentChannelsCache(private val storage: Storage) {

    interface Storage {
        fun readEncoded(): String?
        /** Persist the list and clear the `cleared` marker, atomically. */
        fun writeEncoded(encoded: String)
        /** Remove the list and set the `cleared` marker, atomically. */
        fun clearEncoded()
        fun isCleared(): Boolean
    }

    var entries: List<RecentChannels.Entry> = RecentChannels.decode(storage.readEncoded())
        private set

    /** True when the user cleared the list and nothing has been recorded since. */
    var cleared: Boolean = storage.isCleared() && entries.isEmpty()
        private set

    /**
     * t159: the group that was playing when the user cleared. While [cleared] is true, a re-prepare
     * of *that same* group (playlist commit / recovery / catch-up restart) must not rebuild a row —
     * only an actual channel switch does.
     */
    var clearedGroupKey: String? = null
        private set

    private var pending: String? = null
    private var generation: Int = 0

    fun generationSnapshot(): Int = generation

    fun pendingForTest(): String? = pending

    /**
     * Record a channel start. Returns the new list (newest first, de-duplicated by group).
     * After a clear, re-preparing the group that was playing at clear time is ignored, so the list
     * does not silently come back; any other group lifts the marker and starts a new list.
     */
    fun record(group: ChannelGroup, number: Int, variantIndex: Int): List<RecentChannels.Entry> {
        if (cleared && group.key == clearedGroupKey) return entries
        generation++
        entries = RecentChannels.push(entries, group, number, variantIndex)
        cleared = false
        clearedGroupKey = null
        pending = RecentChannels.encode(entries)
        return entries
    }

    /**
     * Persist the pending list. [gen] is the generation the caller scheduled for; when it no longer
     * matches (a newer record or a clear happened), nothing is written.
     * @return true when a write happened.
     */
    fun flush(gen: Int? = null): Boolean {
        val toWrite = pending ?: return false
        if (gen != null && gen != generation) return false
        if (cleared && entries.isEmpty()) {
            // t159: never write the list back after the user cleared it.
            pending = null
            return false
        }
        pending = null
        storage.writeEncoded(toWrite)
        return true
    }

    /**
     * Clear list + pending + storage. Returns the (empty) list for the UI state.
     * [currentGroupKey] is remembered so a re-prepare of the same channel cannot recreate a row.
     */
    fun clearAll(currentGroupKey: String? = null): List<RecentChannels.Entry> {
        generation++
        pending = null
        entries = emptyList()
        cleared = true
        clearedGroupKey = currentGroupKey
        storage.clearEncoded()
        return entries
    }
}
