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
    // t50: revert the t42/t46 8s/12s defers — t45 showed it moved the CPU spike into the user-browsing
    // window. Refresh immediately on IO (independent coroutines); the small EPG stagger only stops the
    // two parses colliding at t=0.
    private const val PLAYLIST_STARTUP_DELAY_MS = 0L
    private const val EPG_STARTUP_DELAY_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = NetUtil.ipv4OkHttpClient
    private val playlistRefreshMutex = Mutex()

    private val _playlistUpdated = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playlistUpdated: SharedFlow<String> = _playlistUpdated.asSharedFlow()

    private val _epgUpdated = MutableSharedFlow<EpgData>(extraBufferCapacity = 1)
    val epgUpdated: SharedFlow<EpgData> = _epgUpdated.asSharedFlow()

    suspend fun refreshPlaylistUrgent(context: Context): Boolean {
        val liveSource = Prefs.getLiveSource(context)
        if (liveSource.isBlank()) return false
        return refreshPlaylist(context, liveSource)
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
                Log.i(TAG, "epg refreshed via network")
                data
            } else {
                // 修复 P1-7：网络刷新失败回退缓存时返回 null，避免调度器误报「EPG已更新」并清零退避计数。
                Log.w(TAG, "epg network refresh did not succeed (fallback=${data != null})")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "epg refresh failed: ${e.message}")
            null
        }
    }
}
