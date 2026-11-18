package com.cjlhll.iptv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceIntegrityTest {
    private class Store : SourcePrefsStore, PlaylistCacheStore {
        var source = "A"
        var revision = 1L
        var file = "A.m3u"
        var writable = true
        val data = mutableMapOf("A.m3u" to "old")
        override fun getLiveSource() = source
        override fun getPlaylistFileName() = file
        override fun commitSource(live: String, epg: String, fileName: String) { source = live; file = fileName }
        override fun setPlaylistFileName(fileName: String) { file = fileName }
        override fun getVersion() = revision
        override fun setVersion(revision: Long) { this.revision = revision }
        override fun fileNameFor(sourceUrl: String) = "$sourceUrl.m3u"
        override fun writeAtomic(fileName: String, content: String): Boolean { if (!writable) return false; data[fileName] = content; return true }
        override fun read(fileName: String) = data[fileName]
    }
    @Test fun staleBackgroundCannotOverwriteNewSourceOrAba() = runBlocking {
        val store = Store()
        val repo = SourceRepository(store, store)
        val oldVersion = repo.currentVersion()
        assertTrue(repo.commitSource("B", "EPG-B", "B-content"))
        assertTrue(repo.commitSource("A", "EPG-A", "A-new"))
        assertFalse(repo.commitBackground(oldVersion, "A", "stale"))
        assertEquals("A-new", store.read("A.m3u"))
    }
    @Test fun failedCacheWritePreservesPointerAndVersion() = runBlocking {
        val store = Store().apply { writable = false }
        val repo = SourceRepository(store, store)
        assertFalse(repo.commitSource("B", "EPG-B", "bad"))
        assertEquals("A", store.source)
        assertEquals("A.m3u", store.file)
        assertEquals(1L, store.revision)
        assertEquals("old", store.read("A.m3u"))
    }
    @Test fun newerManualRequestInvalidatesOlderDownloadBeforeCommit() = runBlocking {
        val store = Store()
        val repo = SourceRepository(store, store)
        val first = repo.beginChange()
        val latest = repo.beginChange()
        assertFalse(repo.commitSource("old", "epg-old", "old", first))
        assertTrue(repo.commitSource("latest", "epg-latest", "new", latest))
        assertEquals("latest", store.source)
        assertFalse(repo.commitSource("old", "epg-old", "old", first))
    }
    @Test fun streamHeaderSuffixDoesNotReachRequestPathOrPolluteNextChannel() {
        val suffix = HttpStreamAddress.resolve("http://stream/live.ts|User-Agent=VLC&Referer=https%3A%2F%2Fref.example%2F", null, null)
        assertEquals("http://stream/live.ts", suffix.url)
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", suffix.userAgent)
        assertEquals("https://ref.example/", suffix.referrer)
        assertNull(HttpStreamAddress.resolve("http://other/live.ts", null, null).userAgent)
        assertEquals("Custom/2", HttpStreamAddress.resolve("http://stream/live.ts|User-Agent=VLC", "Custom/2", null).userAgent)
    }
    @Test fun globalNumberSurvivesGroupFiltering() {
        val groups = ChannelGrouper.group(listOf(Channel("央视频道A", "http://a", group="央视频道"), Channel("卫视频道B", "http://b", group="卫视频道")))
        val all = ChannelGrouper.numberedDisplayChannels(groups)
        val filtered = ChannelGrouper.numberedDisplayChannelsForGroup(groups, "卫视频道")
        assertEquals(2, filtered.single().number)
        assertEquals(all[1].channel.url, filtered.single().channel.url)
    }
}
