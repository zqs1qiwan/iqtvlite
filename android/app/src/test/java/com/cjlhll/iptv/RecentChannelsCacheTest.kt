package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t159: the production clear/flush rules, exercised through the real [RecentChannelsCache] with an
 * in-memory storage seam (no Android, no mirror logic).
 */
class RecentChannelsCacheTest {

    private class FakeStorage(var encoded: String? = null, var cleared: Boolean = false) :
        RecentChannelsCache.Storage {
        var writes = 0
        var clears = 0
        override fun readEncoded(): String? = encoded
        override fun writeEncoded(encoded: String) {
            this.encoded = encoded
            cleared = false
            writes++
        }
        override fun clearEncoded() {
            encoded = null
            cleared = true
            clears++
        }
        override fun isCleared(): Boolean = cleared
    }

    private fun ch(id: Long, title: String, url: String = "http://x/$id") =
        Channel(title = title, url = url, id = id)

    private fun group(key: String, vararg variants: Channel) = ChannelGroup(
        key = key,
        displayTitle = key,
        logoUrl = null,
        group = null,
        variants = variants.map { ChannelVariant(it, "src") },
        defaultVariantIndex = 0,
    )

    private val a = group("a", ch(1, "A"))
    private val b = group("b", ch(2, "B"))

    @Test
    fun recordThenFlushPersistsAndClearsTheMarker() {
        val storage = FakeStorage()
        val cache = RecentChannelsCache(storage)

        cache.record(a, 1, 0)
        assertEquals(1, cache.entries.size)
        assertNull("nothing written until flush", storage.encoded)

        assertTrue(cache.flush(cache.generationSnapshot()))
        assertEquals(1, storage.writes)
        assertEquals(listOf("a"), RecentChannels.decode(storage.encoded).map { it.groupKey })
        assertFalse(storage.cleared)
    }

    @Test
    fun clearDropsListPendingAndStorage() {
        val storage = FakeStorage()
        val cache = RecentChannelsCache(storage)
        cache.record(a, 1, 0)
        cache.flush(cache.generationSnapshot())

        val afterClear = cache.clearAll()

        assertTrue(afterClear.isEmpty())
        assertTrue(cache.entries.isEmpty())
        assertNull(storage.encoded)
        assertTrue("marker set so a later flush cannot resurrect the list", storage.cleared)
        assertTrue(cache.cleared)
        assertEquals(1, storage.clears)
    }

    @Test
    fun flushAfterClearWritesNothing() {
        // The blocker: ON_STOP/ON_PAUSE used to flush a stale pending value back.
        val storage = FakeStorage()
        val cache = RecentChannelsCache(storage)
        cache.record(a, 1, 0)          // pending exists but has not been written yet
        val staleGen = cache.generationSnapshot()

        cache.clearAll()

        assertFalse("a clear must not be undone by a flush", cache.flush(staleGen))
        assertFalse(cache.flush())      // and not by a generation-less flush either
        assertNull(storage.encoded)
        assertEquals(0, storage.writes)
    }

    @Test
    fun reloadAfterClearIsEmpty() {
        val storage = FakeStorage()
        val first = RecentChannelsCache(storage)
        first.record(a, 1, 0)
        first.record(b, 2, 0)
        first.flush(first.generationSnapshot())
        first.clearAll()

        // a fresh process reads the same storage (force-stop + restart)
        val reloaded = RecentChannelsCache(storage)

        assertTrue("cleared list must stay empty after restart", reloaded.entries.isEmpty())
        assertTrue(reloaded.cleared)
        assertFalse(reloaded.flush())
        assertNull(storage.encoded)
    }

    @Test
    fun staleGenerationFlushIsDropped() {
        val storage = FakeStorage()
        val cache = RecentChannelsCache(storage)
        cache.record(a, 1, 0)
        val genA = cache.generationSnapshot()
        cache.record(b, 2, 0)          // supersedes the first debounced write
        val genB = cache.generationSnapshot()

        assertFalse("an older generation must not overwrite a newer list", cache.flush(genA))
        assertTrue(cache.flush(genB))
        assertEquals(listOf("b", "a"), RecentChannels.decode(storage.encoded).map { it.groupKey })
    }

    @Test
    fun recordingAfterClearBuildsANewListAndLiftsTheMarker() {
        val storage = FakeStorage()
        val cache = RecentChannelsCache(storage)
        cache.clearAll()

        cache.record(b, 2, 0)

        assertFalse(cache.cleared)
        assertTrue(cache.flush(cache.generationSnapshot()))
        assertEquals(listOf("b"), RecentChannels.decode(storage.encoded).map { it.groupKey })
        assertFalse(storage.cleared)
    }

    @Test
    fun emptyListIsNeverWrittenBackAfterClear() {
        val storage = FakeStorage(encoded = RecentChannels.encode(emptyList()), cleared = true)
        val cache = RecentChannelsCache(storage)

        assertTrue(cache.entries.isEmpty())
        assertTrue(cache.cleared)
        assertFalse(cache.flush())
        assertEquals(0, storage.writes)
    }

    @Test
    fun rePreparingTheClearedChannelDoesNotRebuildTheList() {
        val storage = FakeStorage()
        val cache = RecentChannelsCache(storage)
        cache.record(a, 1, 0)
        cache.flush(cache.generationSnapshot())

        cache.clearAll(currentGroupKey = "a")
        // a playlist commit / recovery re-prepares the same channel
        cache.record(a, 1, 0)

        assertTrue("same channel must not come back after clear", cache.entries.isEmpty())
        assertTrue(cache.cleared)
        assertFalse(cache.flush())
        assertNull(storage.encoded)
    }

    @Test
    fun switchingToADifferentChannelAfterClearStartsANewList() {
        val storage = FakeStorage()
        val cache = RecentChannelsCache(storage)
        cache.clearAll(currentGroupKey = "a")

        cache.record(b, 2, 0)

        assertEquals(listOf("b"), cache.entries.map { it.groupKey })
        assertFalse(cache.cleared)
        assertTrue(cache.flush(cache.generationSnapshot()))
        assertEquals(listOf("b"), RecentChannels.decode(storage.encoded).map { it.groupKey })
    }

    @Test
    fun clearIsIdempotentAndKeepsStorageEmpty() {
        val storage = FakeStorage()
        val cache = RecentChannelsCache(storage)
        cache.record(a, 1, 0)
        cache.clearAll()
        cache.clearAll()

        assertTrue(cache.entries.isEmpty())
        assertNull(storage.encoded)
        assertEquals(2, storage.clears)
        assertFalse(cache.flush())
    }
}
