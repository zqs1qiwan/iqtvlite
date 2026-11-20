package com.cjlhll.iptv

import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * t156: the on-disk half of the logo cache, as a plain-JVM component so the atomicity rules can be
 * unit-tested with a real temp directory (no Android, no mirror implementation).
 *
 * Rules (LOCK-01 — documented lock order):
 *  1. the in-memory `cache` lock is only ever held for map operations; no disk I/O happens inside it;
 *  2. disk writes take exactly one [lockFor] keyed by the target file name — never two;
 *  3. therefore cache-lock → disk-lock nesting cannot happen, and there is no lock cycle.
 *
 * ATOMIC-02: writes go to a **unique** temp name and are renamed over the target in one step, so two
 * writers for the same key can never corrupt each other's bytes, and a reader either sees the old or
 * the new file — never a partial one. Nothing here deletes a file that a *different* load produced;
 * removal is the job of `clearDisk` / `invalidate` / [prune].
 */
object LogoDiskStore {
    /** Bounded lock table (LOCK-01): 64 stripes, so zapping many channels cannot grow it. */
    private const val LOCK_STRIPES = 64
    private val locks = Array(LOCK_STRIPES) { Any() }
    private val tmpSeq = AtomicLong(0)

    data class Usage(val count: Int, val bytes: Long)

    fun lockFor(name: String): Any = locks[(name.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]

    /** Unique temp sibling for [target] — never the fixed `name.tmp` that two writers would share. */
    fun tempFileFor(target: File): File =
        File(target.parentFile, "${target.name}.${tmpSeq.incrementAndGet()}.${UUID.randomUUID()}.tmp")

    /**
     * java.nio atomic replace, used only when the platform offers it (API 26+; also on the JVM used
     * by unit tests). Reflective so minSdk 24 stays valid and no API-26 symbol is referenced.
     */
    private fun moveReplacing(from: File, to: File): Boolean = runCatching {
        val files = Class.forName("java.nio.file.Files")
        val pathClass = Class.forName("java.nio.file.Path")
        val copyOptionClass = Class.forName("java.nio.file.CopyOption")
        val replaceExisting = Class.forName("java.nio.file.StandardCopyOption")
            .getField("REPLACE_EXISTING").get(null)
        val atomicMove = Class.forName("java.nio.file.StandardCopyOption")
            .getField("ATOMIC_MOVE").get(null)
        val options = java.lang.reflect.Array.newInstance(copyOptionClass, 2)
        java.lang.reflect.Array.set(options, 0, replaceExisting)
        java.lang.reflect.Array.set(options, 1, atomicMove)
        val toPath = File::class.java.getMethod("toPath").invoke(from) as Any
        val targetPath = File::class.java.getMethod("toPath").invoke(to) as Any
        files.getMethod("move", pathClass, pathClass, options.javaClass)
            .invoke(null, toPath, targetPath, options)
        true
    }.getOrDefault(false)

    /**
     * Serialize one write per key and publish it atomically. Returns false when the target could not
     * be produced (the temp file is always cleaned up).
     */
    fun writeAtomically(target: File, write: (OutputStream) -> Unit): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs() && !parent.exists()) return false
        synchronized(lockFor(target.name)) {
            val tmp = tempFileFor(target)
            return try {
                tmp.outputStream().use { out ->
                    write(out)
                    out.flush()
                }
                // POSIX rename replaces the destination in one step (same directory → same fs).
                if (tmp.renameTo(target)) return true
                // JVM/host fallback: Windows renameTo refuses to replace an existing file, so try an
                // atomic move first and only then fall back to delete+rename. Android takes the
                // renameTo branch above, so this path never runs on device.
                if (moveReplacing(tmp, target)) return true
                runCatching { target.delete() }
                if (tmp.renameTo(target)) {
                    true
                } else {
                    runCatching { tmp.delete() }
                    false
                }
            } catch (_: Exception) {
                runCatching { tmp.delete() }
                false
            }
        }
    }

    /** Count/size of the cached entries — the `stats()` primitive (TEST-01: real, not a mirror). */
    fun scan(dir: File, extension: String = "webp"): Usage {
        val files = try {
            dir.listFiles()?.filter { it.isFile && it.extension == extension } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return Usage(files.size, files.sumOf { it.length() })
    }

    /** Drop the oldest entries until the directory fits in [maxBytes]; returns bytes reclaimed. */
    fun prune(dir: File, maxBytes: Long, extension: String = "webp"): Long {
        val files = try {
            dir.listFiles()?.filter { it.isFile && it.extension == extension } ?: return 0L
        } catch (_: Exception) {
            return 0L
        }
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return 0L
        val before = total
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= maxBytes) return@forEach
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) total -= len
        }
        return before - total
    }

    /** Remove every file (used by clearDisk / invalidate) — the only whole-directory deleter. */
    fun clear(dir: File, extension: String? = null): Int {
        val files = try {
            dir.listFiles() ?: return 0
        } catch (_: Exception) {
            return 0
        }
        var removed = 0
        files.forEach { f ->
            if (extension == null || f.extension == extension) {
                if (runCatching { f.delete() }.getOrDefault(false)) removed++
            }
        }
        return removed
    }

    /** Leftover temp files from a killed process (never published, safe to delete). */
    fun clearTemps(dir: File): Int {
        val files = try {
            dir.listFiles()?.filter { it.name.endsWith(".tmp") } ?: return 0
        } catch (_: Exception) {
            return 0
        }
        var removed = 0
        files.forEach { f -> if (runCatching { f.delete() }.getOrDefault(false)) removed++ }
        return removed
    }
}
