package com.cjlhll.iptv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

import androidx.compose.ui.unit.Dp

object LogoLoader {
    // t42: cap the in-memory bitmap cache (96 entries * 160x100 ARGB_8888 ≈ 6MB max).
    private const val MAX_ENTRIES = 96
    /** 统一标准台标解码尺寸：抽屉 / 信息卡 / 最近观看 100% 共享同一缓存，由 GPU 硬件缩放，省 60%+ 内存与磁盘 IO。 */
    const val CANONICAL_WIDTH_PX = 160
    const val CANONICAL_HEIGHT_PX = 100
    private val _logoLoadEnabled = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    val logoLoadEnabledFlow: kotlinx.coroutines.flow.StateFlow<Boolean?> = _logoLoadEnabled
    fun setLogoLoadEnabledRuntime(enabled: Boolean) { _logoLoadEnabled.value = enabled }
    private var client: OkHttpClient? = null
    private val semaphore = kotlinx.coroutines.sync.Semaphore(3) // 限制并发数，防止UI卡顿
    private const val MAX_DISK_BYTES = 32L * 1024 * 1024
    private const val PRUNE_INTERVAL_MS = 60_000L
    private const val PRUNE_EVERY_WRITES = 24
    private const val LOG_TAG = "IQTV-LOGO"

    /** t11 (P0): 唯一允许的写盘编码。禁止回归到 WEBP —— 本机 Skia WEBP 编码器会 SIGSEGV。 */
    internal const val WRITE_FORMAT_NAME = "PNG"

    /** Bumped by clearMemory/clearDisk/clearAll only (whole-cache invalidation). */
    private val cacheGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    /**
     * Per-url invalidation stamps (CACHE-02b) so refreshGroup/invalidate do not nuke unrelated
     * in-flight loads. Bounded LRU (URLGEN-01) so zapping many channels cannot grow it forever.
     */
    private val urlGeneration = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 512
    }
    private val writeCount = java.util.concurrent.atomic.AtomicInteger(0)
    // PRUNE-01: the first write must not trigger a full directory scan.
    @Volatile private var lastPruneAt = System.currentTimeMillis()

    private val inFlight = HashSet<String>()

    private val hexChars = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )

    @Volatile
    private var diskCacheDir: File? = null

    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean {
            return size > MAX_ENTRIES
        }
    }

    private fun getClient(): OkHttpClient {
        if (client == null) {
            synchronized(this) {
                if (client == null) {
                    client = NetUtil.ipv4OkHttpClient
                }
            }
        }
        return client!!
    }

    private fun getCached(key: String): ImageBitmap? = synchronized(cache) { cache[key] }

    fun getInMemory(url: String, targetWidthPx: Int, targetHeightPx: Int): ImageBitmap? {
        if (url.isBlank()) return null
        return getCached(memoryKey(url, targetWidthPx, targetHeightPx))
    }

    /** 原生 RecyclerView 抽屉直接复用同一份标准台标 LRU 内存缓存（零拷贝、零双份内存）。 */
    fun getInMemoryBitmap(url: String): Bitmap? {
        if (url.isBlank()) return null
        return getCached(memoryKey(url, CANONICAL_WIDTH_PX, CANONICAL_HEIGHT_PX))?.asAndroidBitmap()
    }

    /** 原生视图异步加载标准尺寸台标 Bitmap（与 Compose InfoCard/弹窗 100% 共享磁盘与内存缓存）。 */
    suspend fun loadBitmap(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        if (_logoLoadEnabled.value == false) return null
        return load(context, url, CANONICAL_WIDTH_PX, CANONICAL_HEIGHT_PX)?.asAndroidBitmap()
    }

    /**
     * ATOMIC-01: publish only if the (global, per-url) generations captured by the loader are still
     * current — the check and the put happen under the same cache lock, so a clear/invalidate can
     * never interleave between "stale()" and "put".
     */
    private fun putCachedIfFresh(key: String, bitmap: ImageBitmap, gen: Int, urlGen: Int, url: String): Boolean =
        synchronized(cache) {
            if (gen != cacheGeneration.get()) return false
            if (urlGen != synchronized(urlGeneration) { urlGeneration[url] ?: 0 }) return false
            cache[key] = bitmap
            true
        }

    private fun cacheDir(context: Context): File {
        val cached = diskCacheDir
        if (cached != null) return cached
        return synchronized(this) {
            diskCacheDir ?: File(context.cacheDir, "logo_cache_v1").also { diskCacheDir = it }
        }
    }

    private fun memoryKey(url: String, targetWidthPx: Int, targetHeightPx: Int): String {
        return "${targetWidthPx}x${targetHeightPx}|$url"
    }

    private fun diskKey(url: String, targetWidthPx: Int, targetHeightPx: Int): String {
        return "${sha256Hex(url)}_${targetWidthPx}x${targetHeightPx}"
    }

    /**
     * t11 (P0)：新缓存一律写 `.png`（WEBP 编码在本机 Skia 上 SIGSEGV）。旧 `.webp` 仍作为读取
     * 候选，见 [legacyCacheFile] / [readableCacheFile]，所以升级不会丢已有台标缓存。
     */
    private fun cacheFile(dir: File, diskKey: String): File {
        return File(dir, "$diskKey.${LogoDiskStore.EXTENSION}")
    }

    /** t11：旧版本的 `.webp` 缓存（只读兼容，不再写入）。 */
    private fun legacyCacheFile(dir: File, diskKey: String): File {
        return File(dir, "$diskKey.webp")
    }

    /** 读盘候选顺序：新 `.png` → 旧 `.webp`。返回 null 表示这个 key 还没有磁盘缓存。 */
    private fun readableCacheFile(dir: File, diskKey: String): File? {
        val png = cacheFile(dir, diskKey)
        if (png.isFile) return png
        val legacy = legacyCacheFile(dir, diskKey)
        return if (legacy.isFile) legacy else null
    }

    /** (lastModified, length) identity of a cache file — ATOMIC-02 delete guard. */
    internal data class FileIdentity(val lastModified: Long, val length: Long)

    internal fun fileIdentity(file: File): FileIdentity? = try {
        if (file.exists()) FileIdentity(file.lastModified(), file.length()) else null
    } catch (_: Exception) {
        null
    }

    /** Delete only when the file on disk is still exactly the one we read. */
    private fun deleteIfIdentity(file: File, identity: FileIdentity?) {
        if (identity == null) return
        val now = fileIdentity(file)
        if (now != null && now == identity) runCatching { file.delete() }
    }

    private fun tryMarkInFlight(key: String): Boolean {
        return synchronized(inFlight) {
            if (inFlight.contains(key)) false else {
                inFlight.add(key)
                true
            }
        }
    }

    private fun unmarkInFlight(key: String) {
        synchronized(inFlight) { inFlight.remove(key) }
    }

    // t130: cache management API — all disk work on Dispatchers.IO (never the main thread).
    data class LogoStats(val memoryEntries: Int, val diskFiles: Int, val diskBytes: Long)

    suspend fun stats(context: Context): LogoStats = withContext(Dispatchers.IO) {
        val mem = synchronized(cache) { cache.size }
        val dir = cacheDir(context)
        // t11: 统计同时覆盖新 `.png` 与旧 `.webp`，否则升级后旧缓存会“消失”在统计里。
        val usage = LogoDiskStore.scan(dir)
        LogoStats(mem, usage.count, usage.bytes)
    }

    fun clearMemory() {
        // ATOMIC-01: bump and clear under the same lock as putCachedIfFresh.
        synchronized(cache) {
            cacheGeneration.incrementAndGet()
            cache.clear()
        }
    }

    private fun urlGenOf(url: String): Int = synchronized(urlGeneration) { urlGeneration[url] ?: 0 }

    private fun bumpUrlGen(url: String): Int =
        synchronized(urlGeneration) {
            val next = (urlGeneration[url] ?: 0) + 1
            urlGeneration[url] = next
            next
        }

    suspend fun clearDisk(context: Context) = withContext(Dispatchers.IO) {
        cacheGeneration.incrementAndGet()
        // t167: one deleter for the whole directory (also removes leftover .tmp files), then log the
        // resulting counts so acceptance can assert "memory 0 / disk 0".
        val dir = cacheDir(context)
        LogoDiskStore.clear(dir)
        LogoDiskStore.clearTemps(dir)
        val after = LogoDiskStore.scan(dir)
        android.util.Log.i(
            "IQTV-LOGO",
            "cache_cleared=memory:${synchronized(cache) { cache.size }} disk:${after.count}"
        )
    }

    suspend fun clearAll(context: Context) {
        clearMemory()
        clearDisk(context)
    }

    /** Drop both memory + disk entries for one logo url (all target sizes). */
    suspend fun invalidate(context: Context, url: String) {
        if (url.isBlank()) return
        // CACHE-02b: per-url stamp only — other urls' in-flight loads stay valid.
        bumpUrlGen(url)
        synchronized(cache) {
            cache.keys.filter { it.endsWith("|$url") }.toList().forEach { cache.remove(it) }
        }
        val prefix = sha256Hex(url) + "_"
        withContext(Dispatchers.IO) {
            try {
                cacheDir(context).listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { runCatching { it.delete() } }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * t126/t130: force-refresh the given group's logos — invalidate each channel logo url (IO) then
     * prefetch them in the background (bounded by the same 3-way semaphore as normal loads).
     */
    suspend fun refreshGroup(context: Context, channels: List<Channel>, targetWidthPx: Int, targetHeightPx: Int) {
        val urls = channels.mapNotNull { it.logoUrl?.trim()?.takeIf { u -> u.isNotBlank() } }.distinct()
        urls.forEach { invalidate(context, it) }
        prefetch(context, urls, targetWidthPx, targetHeightPx)
    }

    // --- pure helpers exposed for JVM tests (no Android deps) ---
    internal fun memoryKeyForTest(url: String, w: Int, h: Int): String = memoryKey(url, w, h)
    internal fun diskKeyForTest(url: String, w: Int, h: Int): String = diskKey(url, w, h)
    internal fun sha256ForTest(value: String): String = sha256Hex(value)
    internal fun cacheGenerationForTest(): Int = cacheGeneration.get()
    internal fun bumpGenerationForTest() { cacheGeneration.incrementAndGet() }
    internal fun urlGenerationForTest(url: String): Int = urlGenOf(url)
    internal fun bumpUrlGenerationForTest(url: String) { bumpUrlGen(url) }

    /** Mirrors [putCachedIfFresh]'s atomic rule so the publish gate is unit-testable. */
    internal fun shouldPublishForTest(gen: Int, url: String, urlGen: Int): Boolean =
        gen == cacheGeneration.get() && urlGen == urlGenOf(url)

    internal fun urlGenerationSizeForTest(): Int = synchronized(urlGeneration) { urlGeneration.size }
    internal fun identityForTest(file: File): FileIdentity? = fileIdentity(file)
    internal fun deleteIfIdentityForTest(file: File, identity: FileIdentity?) = deleteIfIdentity(file, identity)
    internal fun writeBitmapToDiskForTest(file: File, bitmap: Bitmap): Boolean = writeBitmapToDisk(file, bitmap)
    // t11 (P0): 写盘格式 / 新旧后缀兼容 的 JVM 断言入口。
    internal fun writeFormatNameForTest(): String = WRITE_FORMAT_NAME
    internal fun diskExtensionForTest(): String = LogoDiskStore.EXTENSION
    internal fun legacyDiskExtensionsForTest(): List<String> = LogoDiskStore.LEGACY_EXTENSIONS
    internal fun cacheFileForTest(dir: File, url: String, w: Int, h: Int): File = cacheFile(dir, diskKey(url, w, h))
    internal fun legacyCacheFileForTest(dir: File, url: String, w: Int, h: Int): File =
        legacyCacheFile(dir, diskKey(url, w, h))
    internal fun readableCacheFileForTest(dir: File, url: String, w: Int, h: Int): File? =
        readableCacheFile(dir, diskKey(url, w, h))
    internal fun writeAtomicallyDefensivelyForTest(file: File, encode: (java.io.OutputStream) -> Unit): Boolean =
        writeAtomicallyDefensively(file, encode)
    /** Mirrors loadInternal's staleness rule so the rule itself is unit-testable. */
    internal fun isStaleForTest(globalGen: Int, url: String, urlGen: Int): Boolean =
        !shouldPublishForTest(globalGen, url, urlGen)

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        var i = 0
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out[i++] = hexChars[v ushr 4]
            out[i++] = hexChars[v and 0x0F]
        }
        return String(out)
    }

    private fun computeSampleSize(outWidth: Int, outHeight: Int, targetWidthPx: Int, targetHeightPx: Int): Int {
        if (outWidth <= 0 || outHeight <= 0) return 1
        var sampleSize = 1
        while ((outWidth / (sampleSize * 2)) >= targetWidthPx && (outHeight / (sampleSize * 2)) >= targetHeightPx) {
            sampleSize *= 2
        }
        return max(1, sampleSize)
    }

    private fun scaleToBoxCenter(src: Bitmap, targetWidthPx: Int, targetHeightPx: Int): Bitmap {
        val scale = min(targetWidthPx.toFloat() / src.width.toFloat(), targetHeightPx.toFloat() / src.height.toFloat())
        val scaledWidth = max(1, (src.width * scale).roundToInt())
        val scaledHeight = max(1, (src.height * scale).roundToInt())
        val scaled = if (scaledWidth == src.width && scaledHeight == src.height) src else Bitmap.createScaledBitmap(src, scaledWidth, scaledHeight, true)

        val output = Bitmap.createBitmap(targetWidthPx, targetHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val left = ((targetWidthPx - scaled.width) / 2f)
        val top = ((targetHeightPx - scaled.height) / 2f)
        canvas.drawBitmap(scaled, left, top, null)

        if (scaled !== src) scaled.recycle()
        if (src.isRecycled.not() && src !== output) src.recycle()
        return output
    }

    /**
     * ATOMIC-02: one unique temp file per write + atomic rename, serialized per key by
     * [LogoDiskStore]. Never deletes the destination first (that opened a window where a reader saw
     * no file at all); `renameTo` replaces it in one step.
     *
     * t11 (P0): **NEVER** use `Bitmap.CompressFormat.WEBP` here. On this device (API28 / TR100-G9)
     * the Skia hardware WEBP encoder dies with SIGSEGV inside `SkWebpEncoder::Encode →
     * ImportYUVAFromRGBA (pc 0x0)` while compressing the first cold-start logo, which took the whole
     * process down (first install / cleared data always reproduced it). A native SIGSEGV cannot be
     * caught, so the fix is the format itself: PNG is lossless and stable on every API level.
     */
    private fun writeBitmapToDisk(file: File, bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) {
            android.util.Log.w(LOG_TAG, "write_failed=png reason=recycled")
            return false
        }
        val ok = writeAtomicallyDefensively(file) { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IllegalStateException("png compress returned false")
            }
        }
        if (!ok) android.util.Log.w(LOG_TAG, "write_failed=png")
        return ok
    }

    /**
     * t11: 写盘防御层 — 编码/IO 异常只返回 false，绝不冒泡到加载协程或主线程。
     * 注意：native SIGSEGV 无法被 JVM try/catch 捕获，真正的修复是换掉 WEBP 编码器（见上）。
     */
    internal fun writeAtomicallyDefensively(file: File, encode: (java.io.OutputStream) -> Unit): Boolean =
        runCatching { LogoDiskStore.writeAtomically(file, encode) }.getOrElse { false }

    /** t126: public load with in-flight de-duplication (one network/decode per url+size at a time). */
    suspend fun load(context: Context, url: String, targetWidthPx: Int, targetHeightPx: Int): ImageBitmap? {
        if (url.isBlank()) return null
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return null
        val memoryKey = memoryKey(url, targetWidthPx, targetHeightPx)
        getCached(memoryKey)?.let { return it }
        val diskKey = withContext(Dispatchers.Default) { diskKey(url, targetWidthPx, targetHeightPx) }
        if (!tryMarkInFlight(diskKey)) {
            // Another coroutine is loading the same logo; wait briefly for it to publish to memory.
            var waited = 0
            while (waited < 1500) {
                getCached(memoryKey)?.let { return it }
                delay(50)
                waited += 50
            }
            return getCached(memoryKey)
        }
        return try {
            loadInternal(context, url, targetWidthPx, targetHeightPx, diskKey)
        } finally {
            unmarkInFlight(diskKey)
        }
    }

    private suspend fun loadInternal(
        context: Context,
        url: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
        precomputedDiskKey: String? = null,
    ): ImageBitmap? {
        if (url.isBlank()) return null
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return null
        val gen = cacheGeneration.get()
        val uGen = urlGenOf(url)
        fun stale() = gen != cacheGeneration.get() || uGen != urlGenOf(url)

        val memoryKey = memoryKey(url, targetWidthPx, targetHeightPx)
        getCached(memoryKey)?.let { LogoPerfCounters.onCacheHit(); return it }

        val diskKey = precomputedDiskKey ?: withContext(Dispatchers.Default) { diskKey(url, targetWidthPx, targetHeightPx) }

        // 单次 IO 解析目录与可读文件，消除冗余的二次 isFile 磁盘检查。
        val (cacheDirectory, diskFile, readableFile) = withContext(Dispatchers.IO) {
            val dir = cacheDir(context)
            if (!dir.exists()) dir.mkdirs()
            val png = cacheFile(dir, diskKey)
            val readable = if (png.isFile) png else legacyCacheFile(dir, diskKey).takeIf { it.isFile }
            Triple(dir, png, readable)
        }

        if (readableFile != null) {
            // ATOMIC-02: capture the file identity *before* decoding, so a stale result can only
            // delete the exact bytes it read — never a newer file another load just published.
            val identity = withContext(Dispatchers.IO) { fileIdentity(readableFile) }
            val bitmap = withContext(Dispatchers.Default) {
                try {
                    BitmapFactory.decodeFile(readableFile.absolutePath)
                } catch (_: Exception) {
                    null
                }
            }
            // CACHE-02a + ATOMIC-01: publish only if still fresh, atomically.
            if (bitmap != null) {
                val imageBitmap = bitmap.asImageBitmap()
                if (putCachedIfFresh(memoryKey, imageBitmap, gen, uGen, url)) {
                    LogoPerfCounters.onCacheHit()
                    return imageBitmap
                }
                // stale: remove only if the file is still the one we decoded (identity match)
                deleteIfIdentity(readableFile, identity)
            }
        }

        return semaphore.withPermit {
            // Double check
            getCached(memoryKey)?.let { return@withPermit it }

            val existing = withContext(Dispatchers.IO) {
                readableCacheFile(cacheDirectory, diskKey)?.let { it to fileIdentity(it) }
            }
            if (existing != null) {
                val existingFile = existing.first
                val identity2 = existing.second
                val bitmap = withContext(Dispatchers.Default) {
                    try {
                        BitmapFactory.decodeFile(existingFile.absolutePath)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bitmap != null) {
                    val imageBitmap = bitmap.asImageBitmap()
                    if (putCachedIfFresh(memoryKey, imageBitmap, gen, uGen, url)) return@withPermit imageBitmap
                    deleteIfIdentity(existingFile, identity2)
                }
            }

            try {
                val bytes = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).build()
                    try {
                        getClient().newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return@use null
                            response.body?.bytes()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                } ?: return@withPermit null

                val bitmap = withContext(Dispatchers.Default) {
                    try {
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = true
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        val decodeOptions = BitmapFactory.Options()
                        decodeOptions.inSampleSize = computeSampleSize(options.outWidth, options.outHeight, targetWidthPx, targetHeightPx)
                        decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888

                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                        if (decoded == null) null else scaleToBoxCenter(decoded, targetWidthPx, targetHeightPx)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                } ?: return@withPermit null
                LogoPerfCounters.onDecode()

                // CACHE-02a: if a clear/invalidate happened while downloading, drop this result.
                if (stale()) return@withPermit null

                val written = withContext(Dispatchers.IO) {
                    val ok = writeBitmapToDisk(diskFile, bitmap)
                    maybePruneDisk(cacheDir(context))
                    ok
                }

                // ATOMIC-01/ATOMIC-02: check + publish under the cache lock. A stale result leaves
                // the file alone: another load may own the same key, and clearDisk/prune remove it.
                val imageBitmap = bitmap.asImageBitmap()
                if (!putCachedIfFresh(memoryKey, imageBitmap, gen, uGen, url)) {
                    if (!written) runCatching { diskFile.delete() }
                    return@withPermit null
                }
                imageBitmap
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    /** t154: one log line per drawer session so a delta can be attributed to prefetch/decode/hits. */
    fun logPerfCounters(drawerOpen: Boolean) {
        val sample = LogoPerfCounters.sampleAndReset()
        if (drawerOpen) return // only log when the browsing session ended
        android.util.Log.i("IQTV-LOGO", LogoPerfCounters.format(sample))
    }

    suspend fun load(context: Context, url: String): ImageBitmap? {
        return load(context, url, 160, 100)
    }

    /**
     * t137: urgent prefetch — the visible window + the selected neighbourhood. No truncation of the
     * caller's list (PREFETCH-02), concurrency 2 (t136 CPU budget).
     */
    suspend fun prefetch(context: Context, urls: List<String>, targetWidthPx: Int, targetHeightPx: Int) {
        prefetchChunked(context, urls, targetWidthPx, targetHeightPx, concurrency = 2, limit = Int.MAX_VALUE)
    }

    /**
     * t137: idle/low-priority prefetch — the rest of the group, in small batches with a pause between
     * them so the browsing window keeps its CPU budget.
     */
    suspend fun prefetchBackground(
        context: Context,
        urls: List<String>,
        targetWidthPx: Int,
        targetHeightPx: Int,
        batchSize: Int = 4,
        batchGapMs: Long = 220,
    ) {
        // PREFETCH-02b: the caller already prioritised the list; truncating at 60 dropped a
        // selection past index 60. Keep the batch pacing, drop the cap.
        prefetchChunked(context, urls, targetWidthPx, targetHeightPx, concurrency = 2, limit = Int.MAX_VALUE, batchSize = batchSize, batchGapMs = batchGapMs)
    }

    private val prefetchGate = kotlinx.coroutines.sync.Semaphore(2)

    private suspend fun prefetchChunked(
        context: Context,
        urls: List<String>,
        targetWidthPx: Int,
        targetHeightPx: Int,
        concurrency: Int,
        limit: Int,
        batchSize: Int = concurrency,
        batchGapMs: Long = 0L,
    ) {
        // t154: the single guard for every prefetch entry point (drawer urgent + filler + startup).
        if (!Prefs.isLogoPrefetchEnabled(context)) return
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return
        if (urls.isEmpty()) return
        val unique = urls.asSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().take(limit).toList()
        if (unique.isEmpty()) return
        LogoPerfCounters.onPrefetchRequest(unique.size)

        coroutineScope {
            unique.chunked(batchSize).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                chunk.map { url ->
                    async {
                        // CANCEL-01: never swallow cancellation.
                        try {
                            prefetchGate.withPermit { load(context, url, targetWidthPx, targetHeightPx) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
                if (batchGapMs > 0) delay(batchGapMs)
            }
        }
    }

    /**
     * CACHE-02/PERF-02a: keep the disk cache under MAX_DISK_BYTES, but only scan at most once per
     * [PRUNE_INTERVAL_MS] or every [PRUNE_EVERY_WRITES] writes — never a full directory scan per write.
     */
    private fun maybePruneDisk(dir: File) {
        val now = System.currentTimeMillis()
        val writes = writeCount.incrementAndGet()
        if (writes % PRUNE_EVERY_WRITES != 0 && now - lastPruneAt < PRUNE_INTERVAL_MS) return
        lastPruneAt = now
        pruneDiskIfNeeded(dir)
    }

    private fun pruneDiskIfNeeded(dir: File) {
        LogoDiskStore.prune(dir, MAX_DISK_BYTES)
        LogoDiskStore.clearTemps(dir)
    }
}

@Composable
fun ChannelLogo(
    logoUrl: String?,
    fallbackTitle: String,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp? = null
) {
    val context = LocalContext.current

    // 统一使用标准尺寸缓存（160×100px），抽屉/信息卡/最近条 100% 共享同一份内存+磁盘缓存。
    val targetW = LogoLoader.CANONICAL_WIDTH_PX
    val targetH = LogoLoader.CANONICAL_HEIGHT_PX

    val url = logoUrl?.trim().orEmpty()
    val runtimeLoadEnabled by LogoLoader.logoLoadEnabledFlow.collectAsState()
    val initialLoadEnabled = remember { Prefs.isLogoLoadEnabled(context) }
    val loadEnabled = runtimeLoadEnabled ?: initialLoadEnabled
    val memoryCacheHit = remember(url, loadEnabled) {
        if (!loadEnabled || url.isBlank()) null
        else LogoLoader.getInMemory(url, targetW, targetH)
    }

    var loaded by remember(url) { mutableStateOf(memoryCacheHit) }
    var showFallbackText by remember(url) { mutableStateOf(url.isBlank() || !loadEnabled) }

    LaunchedEffect(url, loadEnabled) {
        if (!loadEnabled || url.isBlank()) {
            loaded = null
            showFallbackText = true
            return@LaunchedEffect
        }
        val hit = LogoLoader.getInMemory(url, targetW, targetH)
        if (hit != null) {
            loaded = hit
            showFallbackText = false
            return@LaunchedEffect
        }
        showFallbackText = false
        val fallbackJob = launch {
            delay(500)
            if (loaded == null) showFallbackText = true
        }
        val result = LogoLoader.load(context, url, targetW, targetH)
        loaded = result
        if (result != null) {
            fallbackJob.cancel()
            showFallbackText = false
        }
    }

    val shape = TvDesignTokens.ShapeFlat
    val bg = TvDesignTokens.SurfaceElevated

    if (loaded != null) {
        Image(
            bitmap = loaded!!,
            contentDescription = fallbackTitle,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = modifier.clip(shape)
        )
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            if (showFallbackText) {
                Text(
                    text = fallbackTitle.take(1),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = TvDesignTokens.TextSecondary
                )
            }
        }
    }
}
