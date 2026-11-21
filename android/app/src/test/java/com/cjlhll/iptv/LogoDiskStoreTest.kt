package com.cjlhll.iptv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * t156 TEST-01: exercises the **real** disk store against a real temporary directory (not a mirror
 * implementation): atomic replace, per-key serialization, interleaved writers/readers, prune, clear
 * and temp cleanup. Every test resets its own directory (TEST-02: no shared global state).
 */
class LogoDiskStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("logo-disk", "").let { f ->
            f.delete()
            File(f.absolutePath).also { it.mkdirs() }
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun target(name: String = "entry.webp") = File(dir, name)

    @Test
    fun writeAtomicallyReplacesContentInOneStep() {
        val file = target()
        assertTrue(LogoDiskStore.writeAtomically(file) { it.write(byteArrayOf(1, 2, 3)) })
        assertEquals(3L, file.length())

        assertTrue(LogoDiskStore.writeAtomically(file) { it.write(ByteArray(10)) })
        assertEquals("old bytes must be replaced, never appended", 10L, file.length())
        assertEquals("no temp files left behind", 0, LogoDiskStore.clearTemps(dir))
    }

    @Test
    fun tempNamesAreUniquePerWrite() {
        val file = target()
        val a = LogoDiskStore.tempFileFor(file)
        val b = LogoDiskStore.tempFileFor(file)
        assertFalse("concurrent writers must not share a temp file", a.absolutePath == b.absolutePath)
        assertTrue(a.name.endsWith(".tmp") && b.name.endsWith(".tmp"))
    }

    @Test
    fun failedWriteLeavesThePreviousFileIntact() {
        val file = target()
        assertTrue(LogoDiskStore.writeAtomically(file) { it.write(byteArrayOf(7, 7)) })
        val before = file.length()

        val ok = LogoDiskStore.writeAtomically(file) { throw java.io.IOException("boom") }

        assertFalse(ok)
        assertTrue("a failed write must not destroy the published file", file.exists())
        assertEquals(before, file.length())
        assertEquals(0, LogoDiskStore.clearTemps(dir))
    }

    @Test
    fun sameKeyWritesAreSerializedAndNeverInterleave() {
        val file = target()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { index ->
            pool.execute {
                start.await()
                LogoDiskStore.writeAtomically(file) { out ->
                    // 4 chunks: if two writers interleaved, the file would contain a mix of values.
                    repeat(4) { out.write(ByteArray(256) { index.toByte() }) }
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals("exactly one writer's payload must survive", 4 * 256L, file.length())
        val bytes = file.readBytes()
        val first = bytes[0]
        assertTrue("file must be a single writer's payload, not a mix", bytes.all { it == first })
        assertEquals(0, LogoDiskStore.clearTemps(dir))
    }

    @Test
    fun readerNeverSeesAPartialFile() {
        val file = target()
        assertTrue(LogoDiskStore.writeAtomically(file) { it.write(ByteArray(4096) { 1 }) })

        val pool = Executors.newFixedThreadPool(2)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val bad = java.util.concurrent.atomic.AtomicInteger(0)
        val reader = pool.submit {
            while (!stop.get()) {
                // A reader may briefly see "no file" on a host whose filesystem cannot replace
                // atomically (Windows/JVM fallback), but it must never see truncated/mixed bytes.
                val len = if (file.exists()) file.length() else 0L
                if (len != 0L && len != 4096L) bad.incrementAndGet()
            }
        }
        repeat(30) { i ->
            LogoDiskStore.writeAtomically(file) { it.write(ByteArray(4096) { i.toByte() }) }
        }
        stop.set(true)
        reader.get(10, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals("rename is atomic: no truncated/partial reads", 0, bad.get())
    }

    @Test
    fun scanAndPruneRespectTheCapAndKeepNewest() {
        repeat(5) { i ->
            val f = File(dir, "old$i.webp")
            f.writeBytes(ByteArray(100))
            f.setLastModified(1_000L + i)
        }
        val usage = LogoDiskStore.scan(dir)
        assertEquals(5, usage.count)
        assertEquals(500L, usage.bytes)

        val reclaimed = LogoDiskStore.prune(dir, maxBytes = 250L)

        assertTrue(reclaimed >= 250L)
        assertTrue("cap enforced", LogoDiskStore.scan(dir).bytes <= 250L)
        assertTrue("newest file survives", File(dir, "old4.webp").exists())
        assertFalse("oldest file is dropped first", File(dir, "old0.webp").exists())
    }

    @Test
    fun clearOnlyRemovesMatchingExtension() {
        File(dir, "a.webp").writeBytes(ByteArray(4))
        File(dir, "b.webp").writeBytes(ByteArray(4))
        File(dir, "keep.xml").writeBytes(ByteArray(4))

        val removed = LogoDiskStore.clear(dir, extensions = listOf("webp"))

        assertEquals(2, removed)
        assertTrue(File(dir, "keep.xml").exists())
        assertEquals(0, LogoDiskStore.scan(dir).count)
    }

    @Test
    fun clearTempsRemovesLeftoversFromAKilledProcess() {
        LogoDiskStore.writeAtomically(target()) { it.write(byteArrayOf(1)) }
        File(dir, "entry.webp.999.dead.tmp").writeBytes(ByteArray(3))

        assertEquals(1, LogoDiskStore.clearTemps(dir))
        assertTrue("published file stays", target().exists())
        assertEquals(0, LogoDiskStore.clearTemps(dir))
    }

    @Test
    fun fileIdentityDistinguishesRewritesAndDeleteIfIdentityIsSafe() {
        val file = target()
        LogoDiskStore.writeAtomically(file) { it.write(ByteArray(8)) }
        val identity = LogoLoader.identityForTest(file)
        assertNotNull(identity)
        assertEquals(8L, identity!!.length)

        // another load publishes a newer file for the same key
        file.writeBytes(ByteArray(16))
        file.setLastModified(file.lastModified() + 5_000)

        LogoLoader.deleteIfIdentityForTest(file, identity)
        assertTrue("a stale load must not delete a newer file (ATOMIC-02)", file.exists())
        assertEquals(16L, file.length())

        // deleting our own bytes is allowed
        val current = LogoLoader.identityForTest(file)
        LogoLoader.deleteIfIdentityForTest(file, current)
        assertFalse(file.exists())
    }

    @Test
    fun identityOfMissingFileIsNull() {
        assertNull(LogoLoader.identityForTest(File(dir, "nope.webp")))
    }
}
