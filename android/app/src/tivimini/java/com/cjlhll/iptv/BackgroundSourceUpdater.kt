package com.cjlhll.iptv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object BackgroundSourceUpdater {
    private const val TAG = "SourceRefresh"
    private const val PLAYLIST_STARTUP_DELAY_MS = 8_000L
    private const val EPG_STARTUP_DELAY_MS = 12_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = NetUtil.ipv4OkHttpClient
    private val playlistRefreshMutex = Mutex()

    @Volatile
    private var startupRefreshScheduled = false

    private val _playlistUpdated = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playlistUpdated: SharedFlow<String> = _playlistUpdated.asSharedFlow()

    private val _epgUpdated = MutableSharedFlow<EpgData>(extraBufferCapacity = 1)
    val epgUpdated: SharedFlow<EpgData> = _epgUpdated.asSharedFlow()

    fun scheduleStartupRefresh(context: Context) {
        if (startupRefreshScheduled) return
        startupRefreshScheduled = true

        val appContext = context.applicationContext
        // t46: run the two startup refreshes in INDEPENDENT coroutines. t42 used a single coroutine,
        // so the EPG refresh was serialized behind the (possibly slow) playlist download+parse and
        // its "12s" delay was effectively >=20s. Independent delays fire EPG ~12s after start.
        scope.launch {
            val liveSource = Prefs.getLiveSource(appContext)
            if (liveSource.isNotBlank()) {
                // t42: defer the automatic startup network playlist refresh so its download+parse does
                // not contend with cold start / the first video frame (startup CPU spike 86-270%).
                // Foreground already reads the cached playlist immediately.
                delay(PLAYLIST_STARTUP_DELAY_MS)
                Log.i(TAG, "startup playlist refresh (delayed)")
                refreshPlaylist(appContext, liveSource)
            }
        }
        scope.launch {
            val epgSource = Prefs.getEpgSource(appContext)
            if (epgSource.isNotBlank()) {
                delay(EPG_STARTUP_DELAY_MS)
                Log.i(TAG, "startup epg refresh (delayed)")
                refreshEpg(appContext, epgSource)
            }
        }
    }

    fun scheduleRefresh(context: Context, delayMs: Long = EPG_STARTUP_DELAY_MS) {
        val appContext = context.applicationContext
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            refreshAll(appContext)
        }
    }

    suspend fun refreshPlaylistUrgent(context: Context): Boolean {
        val liveSource = Prefs.getLiveSource(context)
        if (liveSource.isBlank()) return false
        return refreshPlaylist(context, liveSource)
    }

    suspend fun refreshAll(context: Context) {
        val liveSource = Prefs.getLiveSource(context)
        val epgSource = Prefs.getEpgSource(context)
        if (liveSource.isBlank() && epgSource.isBlank()) return

        Log.i(TAG, "refresh all begin. live=${liveSource.isNotBlank()} epg=${epgSource.isNotBlank()}")

        if (liveSource.isNotBlank()) {
            refreshPlaylist(context, liveSource)
        }
        if (epgSource.isNotBlank()) {
            refreshEpg(context, epgSource)
        }

        Log.i(TAG, "refresh all finished")
    }

    suspend fun refreshPlaylist(context: Context, liveSource: String): Boolean {
        return playlistRefreshMutex.withLock {
            withContext(Dispatchers.IO) {
                val expectedVersion = SourceStore.currentVersion(context)
                try {
                    val request = Request.Builder().url(liveSource).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "playlist download failed code=${response.code}")
                            return@withContext false
                        }
                        val content = response.body?.string()
                        if (content.isNullOrBlank()) {
                            Log.w(TAG, "playlist download empty body")
                            return@withContext false
                        }
                        val parsed = M3uParser.parse(content, liveSource)
                        if (parsed.isEmpty()) return@withContext false
                        val fileName = PlaylistCache.fileNameForSource(liveSource)
                        if (!SourceStore.commitBackground(context, expectedVersion, liveSource, content)) return@withContext false
                        _playlistUpdated.tryEmit(fileName)
                        Log.i(TAG, "playlist refreshed. bytes=${content.length}")
                        true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "playlist refresh failed: ${e.message}")
                    false
                }
            }
        }
    }

    suspend fun refreshEpg(context: Context, epgSource: String): EpgData? {
        return try {
            val result = EpgRepository.loadStructured(context, epgSource, forceRefresh = true)
            val data = result.dataOrNull
            if (result.isNetworkSuccess && data != null && Prefs.getEpgSource(context) == epgSource) {
                Prefs.setLastEpgUpdateTime(context, System.currentTimeMillis())
                _epgUpdated.tryEmit(data)
                Log.i(TAG, "epg refreshed. programs=${data.programsByChannelId.size}")
            } else {
                Log.w(TAG, "epg refresh returned null")
            }
            data
        } catch (e: Exception) {
            Log.w(TAG, "epg refresh failed: ${e.message}")
            null
        }
    }
}
