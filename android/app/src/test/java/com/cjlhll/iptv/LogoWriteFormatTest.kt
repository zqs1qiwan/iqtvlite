package com.cjlhll.iptv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * t11（P0 热修，docs/LOGO_WEBP_CRASH_FIX.md）：台标写盘**不再使用 WEBP**。
 *
 * 真机崩溃：`LogoLoader.writeBitmapToDisk → Bitmap.compress(WEBP) → libhwui
 * SkWebpEncoder::Encode → ImportYUVAFromRGBA (pc 0x0)` → SIGSEGV，首次安装/清 data 冷启动必现。
 * native 崩溃无法被 try/catch 捕获，所以这里锁死“写入格式不是 WEBP”与“旧 .webp 仍可读”两件事，
 * 并验证写盘失败只返回 false、不抛异常。
 */
class LogoWriteFormatTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("logo-format", "").let { f ->
            f.delete()
            File(f.absolutePath).also { it.mkdirs() }
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun writeFormatIsPngAndNeverWebp() {
        assertEquals("PNG", LogoLoader.writeFormatNameForTest())
        assertNotEquals("WEBP", LogoLoader.writeFormatNameForTest())
        assertNotEquals("WEBP_LOSSY", LogoLoader.writeFormatNameForTest())
    }

    @Test
    fun newCacheFilesUsePngExtension() {
        assertEquals("png", LogoLoader.diskExtensionForTest())
        val file = LogoLoader.cacheFileForTest(dir, "https://example.test/logo.png", 160, 100)
        assertTrue("new writes must land in .png", file.name.endsWith(".png"))
        assertFalse("new writes must never land in .webp", file.name.endsWith(".webp"))
        assertNotNull(LogoLoader.legacyCacheFileForTest(dir, "https://example.test/logo.png", 160, 100))
    }

    @Test
    fun legacyWebpIsStillReadable() {
        assertTrue(LogoLoader.legacyDiskExtensionsForTest().contains("webp"))

        val url = "https://example.test/legacy.png"
        val legacy = LogoLoader.legacyCacheFileForTest(dir, url, 160, 100)
        legacy.writeBytes(ByteArray(8) { 1 })

        assertEquals("old .webp cache must stay readable after the upgrade", legacy.absolutePath,
            LogoLoader.readableCacheFileForTest(dir, url, 160, 100)?.absolutePath)

        // 新缓存出现后优先读新格式，但旧文件不被删除（保留给回滚）。
        val png = LogoLoader.cacheFileForTest(dir, url, 160, 100)
        png.writeBytes(ByteArray(4) { 2 })
        assertEquals("new .png wins when both exist", png.absolutePath,
            LogoLoader.readableCacheFileForTest(dir, url, 160, 100)?.absolutePath)
        assertTrue("legacy file must not be deleted by a read", legacy.exists())
    }

    @Test
    fun noCacheFileMeansNull() {
        assertNull(LogoLoader.readableCacheFileForTest(dir, "https://example.test/none.png", 160, 100))
    }

    @Test
    fun writeFailureReturnsFalseInsteadOfThrowing() {
        val file = File(dir, "entry.png")
        val ok = LogoLoader.writeAtomicallyDefensivelyForTest(file) { throw java.io.IOException("boom") }
        assertFalse("a failing encoder must only return false", ok)
        assertFalse(file.exists())
        assertEquals("no temp files must leak", 0, LogoDiskStore.clearTemps(dir))
    }

    @Test
    fun defensiveWriteSucceedsAndPublishesBytes() {
        val file = File(dir, "entry.png")
        assertTrue(LogoLoader.writeAtomicallyDefensivelyForTest(file) { it.write(byteArrayOf(9, 8, 7)) })
        assertEquals(3L, file.length())
        assertEquals(0, LogoDiskStore.clearTemps(dir))
    }

    @Test
    fun statsAndCleanupCoverBothNewAndLegacyExtensions() {
        File(dir, "a.png").writeBytes(ByteArray(10))
        File(dir, "b.webp").writeBytes(ByteArray(20))
        File(dir, "keep.xml").writeBytes(ByteArray(30))

        val usage = LogoDiskStore.scan(dir)
        assertEquals("png + legacy webp are both counted", 2, usage.count)
        assertEquals(30L, usage.bytes)

        // prune must also consider the legacy files, otherwise they become untracked orphans.
        val reclaimed = LogoDiskStore.prune(dir, maxBytes = 5L)
        assertTrue(reclaimed >= 30L)
        assertTrue(LogoDiskStore.scan(dir).bytes <= 5L)

        File(dir, "c.png").writeBytes(ByteArray(4))
        File(dir, "d.webp").writeBytes(ByteArray(4))
        assertEquals(2, LogoDiskStore.clear(dir, extensions = LogoDiskStore.DISK_EXTENSIONS))
        assertTrue("non-logo files stay", File(dir, "keep.xml").exists())
    }
}
