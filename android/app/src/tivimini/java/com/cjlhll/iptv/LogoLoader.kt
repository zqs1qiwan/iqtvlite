package com.cjlhll.iptv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
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
    // t42: cap the in-memory bitmap cache a bit lower (96) to help PSS; disk cache still serves misses.
    private const val MAX_ENTRIES = 96
    private var client: OkHttpClient? = null
    private val semaphore = kotlinx.coroutines.sync.Semaphore(3) // 限制并发数，防止UI卡顿
    private const val MAX_DISK_BYTES = 32L * 1024 * 1024
    private const val PRUNE_INTERVAL_MS = 60_000L
    private const val PRUNE_EVERY_WRITES = 24

    /** Bumped by clearMemory/clearDisk/clearAll only (whole-cache invalidation). */
    @Volatile private var cacheGeneration = 0
    /** Per-url invalidation stamps, so refreshGroup/invalidate do not nuke unrelated in-flight loads. */
    private val urlGeneration = HashMap<String, Int>()
    private val writeCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastPruneAt = 0L

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

    private fun putCached(key: String, bitmap: ImageBitmap) {
        synchronized(cache) { cache[key] = bitmap }
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

    private fun cacheFile(dir: File, diskKey: String): File {
        return File(dir, "$diskKey.webp")
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
        val files = try {
            dir.listFiles()?.filter { it.isFile && it.extension == "webp" } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        LogoStats(mem, files.size, files.sumOf { it.length() })
    }

    fun clearMemory() {
        cacheGeneration++
        synchronized(cache) { cache.clear() }
    }

    private fun urlGenOf(url: String): Int = synchronized(urlGeneration) { urlGeneration[url] ?: 0 }

    suspend fun clearDisk(context: Context) = withContext(Dispatchers.IO) {
        cacheGeneration++
        try {
            cacheDir(context).listFiles()?.forEach { runCatching { it.delete() } }
        } catch (_: Exception) {
        }
    }

    suspend fun clearAll(context: Context) {
        clearMemory()
        clearDisk(context)
    }

    /** Drop both memory + disk entries for one logo url (all target sizes). */
    suspend fun invalidate(context: Context, url: String) {
        if (url.isBlank()) return
        // CACHE-02b: per-url stamp only — other urls' in-flight loads stay valid.
        synchronized(urlGeneration) { urlGeneration[url] = (urlGeneration[url] ?: 0) + 1 }
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
    internal fun cacheGenerationForTest(): Int = cacheGeneration
    internal fun bumpGenerationForTest() { cacheGeneration++ }
    internal fun urlGenerationForTest(url: String): Int = urlGenOf(url)
    internal fun bumpUrlGenerationForTest(url: String) {
        synchronized(urlGeneration) { urlGeneration[url] = (urlGeneration[url] ?: 0) + 1 }
    }
    /** Mirrors loadInternal's staleness rule so the rule itself is unit-testable. */
    internal fun isStaleForTest(globalGen: Int, url: String, urlGen: Int): Boolean =
        globalGen != cacheGeneration || urlGen != urlGenOf(url)

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

    private fun writeBitmapToDisk(file: File, bitmap: Bitmap): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            tmp.outputStream().use { out ->
                val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                bitmap.compress(format, 80, out)
            }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (_: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            false
        }
    }

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
            loadInternal(context, url, targetWidthPx, targetHeightPx)
        } finally {
            unmarkInFlight(diskKey)
        }
    }

    private suspend fun loadInternal(context: Context, url: String, targetWidthPx: Int, targetHeightPx: Int): ImageBitmap? {
        if (url.isBlank()) return null
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return null
        val gen = cacheGeneration
        val uGen = urlGenOf(url)
        fun stale() = gen != cacheGeneration || uGen != urlGenOf(url)

        val memoryKey = memoryKey(url, targetWidthPx, targetHeightPx)
        getCached(memoryKey)?.let { return it }

        val diskKey = withContext(Dispatchers.Default) { diskKey(url, targetWidthPx, targetHeightPx) }

        val (diskFile, diskExists) = withContext(Dispatchers.IO) {
            val dir = cacheDir(context)
            if (!dir.exists()) dir.mkdirs()
            val file = cacheFile(dir, diskKey)
            file to file.exists()
        }

        if (diskExists) {
            val bitmap = withContext(Dispatchers.Default) {
                try {
                    BitmapFactory.decodeFile(diskFile.absolutePath)
                } catch (_: Exception) {
                    null
                }
            }
            // CACHE-02a: a clear/invalidate may have happened while decoding from disk.
            if (bitmap != null && !stale()) {
                val imageBitmap = bitmap.asImageBitmap()
                putCached(memoryKey, imageBitmap)
                return imageBitmap
            }
            if (bitmap != null) runCatching { diskFile.delete() }
        }

        return semaphore.withPermit {
            // Double check
            getCached(memoryKey)?.let { return@withPermit it }

            val diskExists2 = withContext(Dispatchers.IO) { diskFile.exists() }
            if (diskExists2) {
                val bitmap = withContext(Dispatchers.Default) {
                    try {
                        BitmapFactory.decodeFile(diskFile.absolutePath)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bitmap != null && !stale()) {
                    val imageBitmap = bitmap.asImageBitmap()
                    putCached(memoryKey, imageBitmap)
                    return@withPermit imageBitmap
                }
                if (bitmap != null) runCatching { diskFile.delete() }
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

                // CACHE-02a: if a clear/invalidate happened while downloading, drop this result.
                if (stale()) return@withPermit null

                withContext(Dispatchers.IO) {
                    writeBitmapToDisk(diskFile, bitmap)
                    maybePruneDisk(cacheDir(context))
                }

                // CACHE-02a: TOCTOU between write and publish — re-check, then commit atomically.
                if (stale()) {
                    runCatching { diskFile.delete() }
                    return@withPermit null
                }
                val imageBitmap = bitmap.asImageBitmap()
                putCached(memoryKey, imageBitmap)
                imageBitmap
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
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
        prefetchChunked(context, urls, targetWidthPx, targetHeightPx, concurrency = 2, limit = 60, batchSize = batchSize, batchGapMs = batchGapMs)
    }

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
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return
        if (urls.isEmpty()) return
        val unique = urls.asSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().take(limit).toList()
        if (unique.isEmpty()) return

        coroutineScope {
            unique.chunked(batchSize).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                chunk.map { url ->
                    async {
                        // CANCEL-01: never swallow cancellation.
                        try {
                            load(context, url, targetWidthPx, targetHeightPx)
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
        try {
            val files = dir.listFiles()?.filter { it.isFile && it.extension == "webp" } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_DISK_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= MAX_DISK_BYTES) return
                val len = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) total -= len
            }
        } catch (_: Exception) {
        }
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
    val density = LocalDensity.current

    val targetSizePx = remember(width, height, density) {
        if (width != null && height != null) {
            with(density) { IntSize(width.roundToPx(), height.roundToPx()) }
        } else {
            IntSize.Zero
        }
    }

    var sizePx by remember { mutableStateOf(targetSizePx) }

    val url = logoUrl?.trim().orEmpty()
    val memoryCacheHit = remember(url, sizePx) {
        if (url.isNotBlank() && sizePx.width > 0 && sizePx.height > 0) {
            LogoLoader.getInMemory(url, sizePx.width, sizePx.height)
        } else {
            null
        }
    }

    var loaded by remember(url, sizePx) { mutableStateOf(memoryCacheHit) }

    var showFallbackText by remember(url) { mutableStateOf(url.isBlank()) }

    LaunchedEffect(url, sizePx) {
        if (url.isBlank()) {
            loaded = null
            showFallbackText = true
            return@LaunchedEffect
        }
        if (sizePx.width <= 0 || sizePx.height <= 0) return@LaunchedEffect

        showFallbackText = false
        if (loaded != null) return@LaunchedEffect

        val fallbackJob = launch {
            delay(600)
            if (loaded == null) showFallbackText = true
        }

        loaded = LogoLoader.load(context, url, sizePx.width, sizePx.height)
        if (loaded != null) fallbackJob.cancel()
    }

    val shape = RoundedCornerShape(0.dp)
    val bg = MaterialTheme.colorScheme.surfaceVariant

    if (loaded != null) {
        Image(
            bitmap = loaded!!,
            contentDescription = fallbackTitle,
            modifier = modifier
                .onSizeChanged { if (width == null || height == null) sizePx = it }
                .clip(shape)
        )
    } else {
        Box(
            modifier = modifier
                .onSizeChanged { if (width == null || height == null) sizePx = it }
                .clip(shape)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            if (showFallbackText) {
                Text(
                    text = fallbackTitle.take(1),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .padding(6.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
