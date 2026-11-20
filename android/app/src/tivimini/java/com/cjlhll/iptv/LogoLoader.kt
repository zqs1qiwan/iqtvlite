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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    // t126: cache management API for the settings entry + prefetch refresh.
    data class LogoStats(val memoryEntries: Int, val diskFiles: Int, val diskBytes: Long)

    fun stats(context: Context): LogoStats {
        val mem = synchronized(cache) { cache.size }
        val dir = cacheDir(context)
        val files = try {
            dir.listFiles()?.filter { it.isFile && it.extension == "webp" } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return LogoStats(mem, files.size, files.sumOf { it.length() })
    }

    fun clearMemory() {
        synchronized(cache) { cache.clear() }
    }

    fun clearDisk(context: Context) {
        try {
            cacheDir(context).listFiles()?.forEach { runCatching { it.delete() } }
        } catch (_: Exception) {
        }
    }

    fun clearAll(context: Context) {
        clearMemory()
        clearDisk(context)
    }

    /** Drop both memory + disk entries for one logo url (all target sizes). */
    fun invalidate(context: Context, url: String) {
        if (url.isBlank()) return
        synchronized(cache) {
            cache.keys.filter { it.endsWith("|$url") }.toList().forEach { cache.remove(it) }
        }
        val prefix = sha256Hex(url) + "_"
        try {
            cacheDir(context).listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { runCatching { it.delete() } }
        } catch (_: Exception) {
        }
    }

    /**
     * t126: force-refresh the given group's logos — invalidate each channel logo url then prefetch them
     * in the background (bounded by the same 3-way semaphore as normal loads).
     */
    suspend fun refreshGroup(context: Context, channels: List<Channel>, targetWidthPx: Int, targetHeightPx: Int) {
        val urls = channels.mapNotNull { it.logoUrl?.trim()?.takeIf { u -> u.isNotBlank() } }.distinct()
        urls.forEach { invalidate(context, it) }
        prefetch(context, urls, targetWidthPx, targetHeightPx)
    }

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
            if (bitmap != null) {
                val imageBitmap = bitmap.asImageBitmap()
                putCached(memoryKey, imageBitmap)
                return imageBitmap
            }
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
                if (bitmap != null) {
                    val imageBitmap = bitmap.asImageBitmap()
                    putCached(memoryKey, imageBitmap)
                    return@withPermit imageBitmap
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
                    } catch (e: Exception) {
                        null
                    }
                } ?: return@withPermit null

                withContext(Dispatchers.IO) {
                    writeBitmapToDisk(diskFile, bitmap)
                }

                val imageBitmap = bitmap.asImageBitmap()
                putCached(memoryKey, imageBitmap)
                imageBitmap
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun load(context: Context, url: String): ImageBitmap? {
        return load(context, url, 160, 100)
    }

    suspend fun prefetch(context: Context, urls: List<String>, targetWidthPx: Int, targetHeightPx: Int) {
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return
        if (urls.isEmpty()) return

        withContext(Dispatchers.Default) {
            for (raw in urls) {
                val url = raw.trim()
                if (url.isBlank()) continue
                val memoryKey = memoryKey(url, targetWidthPx, targetHeightPx)
                if (getCached(memoryKey) != null) continue
                val diskKey = diskKey(url, targetWidthPx, targetHeightPx)
                val diskExists = withContext(Dispatchers.IO) {
                    val dir = cacheDir(context)
                    if (!dir.exists()) dir.mkdirs()
                    cacheFile(dir, diskKey).exists()
                }
                if (diskExists) continue
                // load() now performs its own in-flight de-dup + semaphore.
                try {
                    load(context, url, targetWidthPx, targetHeightPx)
                } catch (_: Exception) {
                }
            }
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
