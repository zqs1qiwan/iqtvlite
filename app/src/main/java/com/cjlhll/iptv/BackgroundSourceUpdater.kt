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

/**
 * 统一刷新调度入口（D02）与数据完整性保护（D10）。
 *
 * - 读取 [AppSettings] 的列表/EPG 刷新策略，MANUAL 不发启动/周期请求；DAILY 按当天首次启动；
 *   STARTUP_6H 兼顾启动与 6 小时周期；失败按最近尝试做 5 分钟退避。
 * - 下载内容先解析校验至少一个可合并频道，再原子替换缓存；旧源结果不得覆盖新源指针。
 */
object BackgroundSourceUpdater {
    private const val TAG = "SourceRefresh"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()
    private val playlistRefreshMutex = Mutex()

    @Volatile private var schedulerStarted = false

    // 每次进程启动后，是否已对当前源尝试过启动刷新（用于“仅启动时”/DAILY 语义）
    @Volatile private var playlistLaunchAttempted = false
    @Volatile private var epgLaunchAttempted = false

    private val _playlistUpdated = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playlistUpdated: SharedFlow<String> = _playlistUpdated.asSharedFlow()

    private val _epgUpdated = MutableSharedFlow<EpgData>(extraBufferCapacity = 1)
    val epgUpdated: SharedFlow<EpgData> = _epgUpdated.asSharedFlow()

    private val _playlistRefreshResult = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val playlistRefreshResult: SharedFlow<Boolean> = _playlistRefreshResult.asSharedFlow()

    /**
     * 应用启动时调用：按策略触发一次启动刷新，并启动周期性调度循环。
     */
    fun scheduleStartupRefresh(context: Context) {
        val appContext = context.applicationContext
        scope.launch { runStartupPass(appContext) }
        ensureSchedulerLoop(appContext)
    }

    /** 手动保存新源后立即在后台确认一次（用户动作，不受策略限制）。 */
    fun scheduleRefresh(context: Context, delayMs: Long = 2_500L) {
        val appContext = context.applicationContext
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            refreshAll(appContext, manual = true)
        }
    }

    private fun ensureSchedulerLoop(context: Context) {
        if (schedulerStarted) return
        schedulerStarted = true
        scope.launch {
            while (true) {
                delay(60_000L)
                runPeriodicPass(context)
            }
        }
    }

    private suspend fun runStartupPass(context: Context) {
        val liveSource = Prefs.getLiveSource(context)
        val now = System.currentTimeMillis()
        if (liveSource.isNotBlank()) {
            val policy = AppSettings.getPlaylistRefreshPolicy(context)
            if (policy == PlaylistRefreshPolicy.MANUAL) {
                playlistLaunchAttempted = true
            } else {
                val due = RefreshSchedule.playlistDue(
                    nowMillis = now,
                    lastSuccess = AppSettings.getPlaylistLastSuccess(context, liveSource),
                    lastAttempt = AppSettings.getPlaylistLastAttempt(context, liveSource),
                    policy = policy,
                    launchAttempted = playlistLaunchAttempted,
                )
                // 仅在实际发起尝试时才标记“已尝试”；被退避暂缓的启动请求留待周期补做（R09）
                if (due) {
                    playlistLaunchAttempted = true
                    refreshPlaylist(context, liveSource, manual = false)
                }
            }
        }
        // EPG 的启动/周期刷新由播放器按策略驱动（需要准确的 Loading/Ready/Failure 状态）。
    }

    private suspend fun runPeriodicPass(context: Context) {
        val liveSource = Prefs.getLiveSource(context)
        val now = System.currentTimeMillis()
        if (liveSource.isNotBlank()) {
            val policy = AppSettings.getPlaylistRefreshPolicy(context)
            if (policy != PlaylistRefreshPolicy.MANUAL) {
                val due = RefreshSchedule.playlistDue(
                    nowMillis = now,
                    lastSuccess = AppSettings.getPlaylistLastSuccess(context, liveSource),
                    lastAttempt = AppSettings.getPlaylistLastAttempt(context, liveSource),
                    policy = policy,
                    launchAttempted = playlistLaunchAttempted,
                )
                if (due) {
                    playlistLaunchAttempted = true
                    refreshPlaylist(context, liveSource, manual = false)
                }
            }
        }
    }

    suspend fun refreshPlaylistUrgent(context: Context): Boolean {
        val liveSource = Prefs.getLiveSource(context)
        if (liveSource.isBlank()) return false
        return refreshPlaylist(context, liveSource, manual = true)
    }

    suspend fun refreshAll(context: Context, manual: Boolean = false) {
        val liveSource = Prefs.getLiveSource(context)
        val epgSource = Prefs.getEpgSource(context)
        if (liveSource.isNotBlank()) refreshPlaylist(context, liveSource, manual)
        if (epgSource.isNotBlank()) refreshEpg(context, epgSource, manual)
    }

    /**
     * 下载并校验后再替换缓存。返回是否真正更新成功（R03：通过 SourceStore 单一事务提交）。
     */
    suspend fun refreshPlaylist(context: Context, liveSource: String, manual: Boolean = false): Boolean {
        return playlistRefreshMutex.withLock {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                if (!manual) {
                    val lastAttempt = AppSettings.getPlaylistLastAttempt(context, liveSource)
                    if (lastAttempt > 0 && now - lastAttempt < RefreshSchedule.RETRY_BACKOFF_MS) {
                        Log.i(TAG, "playlist refresh skipped by backoff")
                        return@withContext false
                    }
                }
                AppSettings.setPlaylistLastAttempt(context, liveSource, now)
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
                        if (!PlaylistValidation.isValid(content)) {
                            Log.w(TAG, "playlist content has no valid channels; keep old cache")
                            return@withContext false
                        }
                        // R03：单一事务校验版本 + 原子写缓存 + 提交指针；旧结果不会覆盖新源
                        val committed = SourceStore.commitBackground(context, expectedVersion, liveSource, content)
                        if (!committed) {
                            Log.w(TAG, "playlist commit rejected (version/source changed or IO failed)")
                            return@withContext false
                        }
                        AppSettings.setPlaylistLastSuccess(context, liveSource, System.currentTimeMillis())
                        val fileName = Prefs.getPlaylistFileName(context)
                        if (fileName != null) _playlistUpdated.tryEmit(fileName)
                        _playlistRefreshResult.tryEmit(true)
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

    suspend fun refreshEpg(context: Context, epgSource: String, manual: Boolean = false): EpgLoadResult {
        // S06：与播放器启动/周期/手动共享每源 inFlight，配置后刷新也走同一协调入口
        if (!EpgRefreshCoordinator.tryBegin(epgSource)) {
            val cached = EpgRepository.load(context, epgSource, forceRefresh = false, allowNetwork = false)
            return if (cached != null) EpgLoadResult.CacheFallback(epgSource, cached) else EpgLoadResult.Failure(epgSource, "in-flight")
        }
        try {
            val now = System.currentTimeMillis()
            if (!manual) {
                val lastAttempt = AppSettings.getEpgLastAttempt(context, epgSource)
                if (lastAttempt > 0 && now - lastAttempt < RefreshSchedule.RETRY_BACKOFF_MS) {
                    Log.i(TAG, "epg refresh skipped by backoff")
                    val cached = EpgRepository.load(context, epgSource, forceRefresh = false, allowNetwork = false)
                    return if (cached != null) EpgLoadResult.CacheFallback(epgSource, cached) else EpgLoadResult.Failure(epgSource, "backoff")
                }
            }
            AppSettings.setEpgLastAttempt(context, epgSource, now)
            val result = try {
                EpgRepository.loadStructured(context, epgSource, forceRefresh = true, allowNetwork = true)
            } catch (e: Exception) {
                Log.w(TAG, "epg refresh failed: ${e.message}")
                EpgLoadResult.Failure(epgSource, e.message)
            }
            if (result.isNetworkSuccess) {
                AppSettings.setEpgLastSuccess(context, epgSource, System.currentTimeMillis())
                val data = result.dataOrNull
                if (data != null && Prefs.getEpgSource(context) == epgSource) _epgUpdated.tryEmit(data)
                Log.i(TAG, "epg refreshed. programs=${data?.programsByChannelId?.size}")
            } else {
                Log.w(TAG, "epg refresh fallback/failure: $result")
            }
            return result
        } finally {
            EpgRefreshCoordinator.end(epgSource)
        }
    }

}
