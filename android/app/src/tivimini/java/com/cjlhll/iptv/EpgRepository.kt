package com.cjlhll.iptv

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Source-scoped, single-flight loads. Failed refreshes never replace good parsed data. */
object EpgRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val inFlight = HashMap<String, Deferred<EpgLoadResult>>()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, EpgData>()

    suspend fun load(context: Context, epgSourceUrl: String, forceRefresh: Boolean = false): EpgData? =
        loadStructured(context, epgSourceUrl, forceRefresh).dataOrNull

    suspend fun loadStructured(context: Context, source: String, forceRefresh: Boolean = false): EpgLoadResult {
        if (source.isBlank()) return EpgLoadResult.Failure(source, "empty source")
        if (!forceRefresh) cache[source]?.let { return EpgLoadResult.CacheFallback(source, it) }
        val work = mutex.withLock {
            inFlight[source] ?: scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try { loadCore(context.applicationContext, source, forceRefresh) }
                finally { mutex.withLock { inFlight.remove(source) } }
            }.also { inFlight[source] = it; it.start() }
        }
        // A cancelled screen stops awaiting; a shared bounded refresh can still populate its source cache.
        return work.await()
    }

    private suspend fun loadCore(context: Context, source: String, force: Boolean): EpgLoadResult = withContext(Dispatchers.IO) {
        val name = EpgCache.fileNameForSource(source)
        // Schema version prevents a previous Java-serialization layout becoming a false hit.
        val parsed = File(context.filesDir, "$name.rust-v1.ser")
        val raw = File(context.filesDir, name)
        val cached = cache[source] ?: runCatching {
            if (parsed.exists()) java.io.ObjectInputStream(parsed.inputStream()).use { it.readObject() as EpgData }
            else if (raw.exists()) XmlTvParser.parseFile(raw) else null
        }.getOrNull()
        if (cached != null) cache[source] = cached
        if (!force && cached != null) return@withContext EpgLoadResult.CacheFallback(source, cached)
        // A unique candidate keeps an interrupted/invalid response away from both good caches.
        val candidate = File.createTempFile("epg-", ".xmltv", context.cacheDir)
        try {
            val client = NetUtil.ipv4OkHttpClient.newBuilder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(source).build()).execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code}" }
                val body = requireNotNull(response.body)
                require(body.contentLength() <= 32L*1024*1024) { "EPG response too large" }
                body.byteStream().use { input -> candidate.outputStream().use { output ->
                    val buffer = ByteArray(16384)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 32L*1024*1024) { "EPG response too large" }
                        output.write(buffer, 0, count)
                    }
                } }
            }
            val data = XmlTvParser.parseFile(candidate)
            val atomic = AtomicFile(parsed)
            val output = atomic.startWrite()
            try {
                java.io.ObjectOutputStream(output).apply { writeObject(data); flush() }
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (e: Exception) { atomic.failWrite(output); throw e }
            // Only one active source is retained in memory; historical files remain for rollback.
            cache.clear(); cache[source] = data
            EpgLoadResult.Success(source, data)
        } catch (e: Exception) {
            Log.w("EPG", "refresh failed type=${e.javaClass.simpleName}")
            if (cached != null) EpgLoadResult.CacheFallback(source, cached) else EpgLoadResult.Failure(source, "download/parse failed")
        } finally { candidate.delete() }
    }
}
