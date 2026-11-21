package com.cjlhll.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二轮修复的生产入口级测试（R01/R02/R03/R04）。
 * 通过可注入的 Prefs/缓存、可控 probe 延迟、事务锁闩验证真实行为；
 * 这些用例在缺陷实现（无代次校验/无事务/事件盖章）下会失败。
 */
class Round2LogicTest {

    // ---------- 测试替身 ----------
    private class FakePrefs(var live: String = "", var fileName: String? = null) : SourcePrefsStore {
        var storedVersion: Long = 0L
        override fun getLiveSource(): String = live
        override fun getPlaylistFileName(): String? = fileName
        override fun commitSource(live: String, epg: String, fileName: String) {
            this.live = live; this.fileName = fileName
        }
        override fun setPlaylistFileName(fileName: String) { this.fileName = fileName }
        override fun getVersion(): Long = storedVersion
        override fun setVersion(version: Long) { storedVersion = version }
    }

    private class FakeCache : PlaylistCacheStore {
        val files = HashMap<String, String>()
        var failWrite = false
        override fun fileNameFor(sourceUrl: String): String = "file_${sourceUrl.hashCode()}"
        override fun writeAtomic(fileName: String, content: String): Boolean {
            if (failWrite) return false
            files[fileName] = content
            return true
        }
        override fun read(fileName: String): String? = files[fileName]
    }

    // ---------- R03：源事务 ----------
    @Test
    fun lateBackgroundCommitCannotOverrideNewerManualSource() = runBlocking {
        val prefs = FakePrefs()
        val cache = FakeCache()
        val repo = SourceRepository(prefs, cache)
        val staleVersion = repo.currentVersion() // 后台请求发起时版本
        // 用户手动切到 B（版本已推进）
        assertTrue(repo.commitSource("B", "", "contentB"))
        // 旧 A 后台结果返回，携带旧版本 → 必须被拒
        assertFalse(repo.commitBackground(staleVersion, "A", "contentA"))
        assertEquals("B", prefs.live)
        assertEquals("contentB", cache.files["file_${"B".hashCode()}"])
        // 旧源 A 的缓存也不应覆盖指针
        assertFalse(prefs.fileName == "file_${"A".hashCode()}")
    }

    @Test
    fun aToBToADetectedByVersionEvenWhenUrlReturnsToSameSource() = runBlocking {
        val prefs = FakePrefs()
        val cache = FakeCache()
        val repo = SourceRepository(prefs, cache)
        val staleVersion = repo.currentVersion()
        assertTrue(repo.commitSource("A", "", "contentA1"))
        assertTrue(repo.commitSource("B", "", "contentB"))
        assertTrue(repo.commitSource("A", "", "contentA2"))
        // 源虽是 A，但期间发生了多次提交，旧后台结果必须丢弃
        assertFalse(repo.commitBackground(staleVersion, "A", "contentOld"))
    }

    @Test
    fun cacheWriteFailureKeepsOldPointer() = runBlocking {
        val prefs = FakePrefs(live = "OLD", fileName = "old_file")
        val cache = FakeCache()
        cache.files["old_file"] = "oldContent"
        val repo = SourceRepository(prefs, cache)
        cache.failWrite = true
        assertFalse(repo.commitSource("NEW", "", "newContent"))
        assertEquals("OLD", prefs.live)
        assertEquals("old_file", prefs.fileName)
        assertEquals("oldContent", cache.files["old_file"])
    }

    @Test
    fun backgroundCommitRejectedWhenSourcePointerChanged() = runBlocking {
        val prefs = FakePrefs(live = "B")
        val cache = FakeCache()
        val repo = SourceRepository(prefs, cache)
        // 版本号相同但源指针已不是 A（例如外部改源）也不能写
        assertFalse(repo.commitBackground(repo.currentVersion(), "A", "contentA"))
    }

    // ---------- R01：探测代次 ----------
    @Test
    fun newerManualIntentAbortsInFlightProbe() = runBlocking {
        val gate = PlaybackIntentGate()
        val coordinator = ProbeCoordinator(gate)
        val oldIntent = gate.newIntent()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var result: Int? = -1
        val job = launch {
            result = coordinator.selectFirstReachable(listOf("a"), oldIntent) { _ ->
                started.complete(Unit)
                release.await()
                100
            }
        }
        started.await()
        gate.newIntent() // 用户手动选择 → 提升意图
        release.complete(Unit)
        job.join()
        assertNull(result) // 旧探测不得覆盖新选择
    }

    @Test
    fun currentIntentStillSelectsReachable() = runBlocking {
        val gate = PlaybackIntentGate()
        val coordinator = ProbeCoordinator(gate)
        val intent = gate.newIntent()
        val idx = coordinator.selectFirstReachable(listOf("dead", "ok", "ok2"), intent) { url ->
            if (url == "ok" || url == "ok2") 30 else null
        }
        assertEquals(1, idx)
    }

    // ---------- R02：媒体 token ----------
    @Test
    fun mediaTokenRoundTripAndRejectsForeignIds() {
        val token = MediaToken(7, 2, 11)
        val parsed = MediaToken.parse(token.encode())
        assertEquals(token, parsed)
        assertNull(MediaToken.parse(null))
        assertNull(MediaToken.parse(""))
        assertNull(MediaToken.parse("garbage"))
    }

    // ---------- R04：EPG 结果分类与按源隔离 ----------
    @Test
    fun classifyPrefersNetworkSuccessThenCacheThenFailure() {
        val data = EpgData(emptyMap(), emptyMap())
        assertTrue(EpgOutcome.classify(data, null, "A") is EpgLoadResult.Success)
        assertTrue(EpgOutcome.classify(null, data, "A") is EpgLoadResult.CacheFallback)
        assertTrue(EpgOutcome.classify(null, null, "A", "io") is EpgLoadResult.Failure)
    }

    @Test
    fun epgSourceCacheIsolatedPerSource() {
        val cache = EpgSourceCache()
        val a = EpgData(mapOf("a1" to emptyList()), emptyMap())
        val b = EpgData(mapOf("b1" to emptyList()), emptyMap())
        cache.put("A", a)
        cache.put("B", b)
        assertEquals(a, cache.get("A"))
        assertEquals(b, cache.get("B"))
        cache.remove("A")
        assertNull(cache.get("A"))
        assertEquals(b, cache.get("B"))
    }

    @Test
    fun cacheFallbackIsNotNetworkSuccess() {
        val data = EpgData(emptyMap(), emptyMap())
        val result = EpgOutcome.classify(null, data, "A", "网络失败")
        assertFalse(result.isNetworkSuccess)
        assertEquals(data, result.dataOrNull)
    }
}
