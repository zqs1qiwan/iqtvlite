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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * t172 (F3): executes the playlist / EPG auto-update policy from [AutoUpdateConfig].
 *
 * Why a process-scoped coroutine instead of WorkManager:
 *  - the app has no other WorkManager dependency and the refreshes are short HTTP+parse jobs that
 *    must NOT delay cold start (they run on IO, 0 ms delay, exactly like the existing
 *    [BackgroundSourceUpdater] path);
 *  - the policy is persisted in [Prefs], so a process restart re-evaluates it immediately and the
 *    device reboot case is covered by the startup pass — no periodic worker needed;
 *  - adding WorkManager would ship a new dependency + JobScheduler surface for a ≤30 MB TV app whose
 *    refreshes only matter while the app runs.
 */
object AutoUpdateScheduler {
    private const val TAG = "AutoUpdate"
    private const val TICK_MS = 60_000L
    /** t176 (PERF-01): let the first video frame land before the startup network pass. */
    private const val STARTUP_DELAY_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var started = false
    @Volatile private var startupDone = false

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Idempotent: starts the policy loop once per process (never on the main thread). */
    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        scope.launch {
            // t176 (PERF-01): the startup pass now waits for the first frame window instead of
            // competing with cold start for CPU/network. Still off the main thread.
            delay(STARTUP_DELAY_MS)
            // Startup pass: honour "关闭" (no automatic pull at all) and the failure backoff.
            runDue(appContext, startup = true)
            while (isActive) {
                delay(TICK_MS)
                runDue(appContext, startup = false)
            }
        }
    }

    /**
     * t182 (EPG-SOURCE-SAVE-01 / STARTUP-RESET-02): the ONLY thing a saved source must do.
     *
     * Called after the user actually saved a new playlist/EPG source (not when opening the config
     * screen, so a cancel changes nothing). It re-opens the startup gate and refreshes both sources
     * through this scheduler, so the EPG never takes a second, unsynchronised path
     * (`BackgroundSourceUpdater.refreshAll`) and the last-success/failure bookkeeping stays in one
     * place.
     */
    fun onSourceChanged(context: Context) {
        val appContext = context.applicationContext
        startupDone = false
        scope.launch {
            mutex.withLock {
                val live = Prefs.getLiveSource(appContext)
                if (live.isNotBlank()) {
                    val ok = runCatching { BackgroundSourceUpdater.refreshPlaylistUrgent(appContext) }
                        .getOrDefault(false)
                    onPlaylistResult(appContext, ok)
                }
                val epg = Prefs.getEpgSource(appContext)
                if (epg.isNotBlank()) {
                    val data = runCatching { BackgroundSourceUpdater.refreshEpg(appContext, epg) }.getOrNull()
                    onEpgResult(appContext, data != null)
                }
            }
            startupDone = true
            _events.tryEmit("源已更新")
        }
    }

    /** Manual refresh — bypasses the interval AND the failure backoff (user asked for it). */
    fun refreshNow(context: Context, playlist: Boolean, epg: Boolean) {
        val appContext = context.applicationContext
        scope.launch {
            mutex.withLock {
                if (playlist) {
                    val ok = BackgroundSourceUpdater.refreshPlaylistUrgent(appContext)
                    onPlaylistResult(appContext, ok)
                    _events.tryEmit(if (ok) "播放列表已更新" else "播放列表更新失败")
                }
                if (epg) {
                    val source = Prefs.getEpgSource(appContext)
                    val data = if (source.isBlank()) null else BackgroundSourceUpdater.refreshEpg(appContext, source)
                    val ok = data != null
                    onEpgResult(appContext, ok)
                    _events.tryEmit(if (ok) "EPG已更新" else "EPG更新失败")
                }
            }
        }
    }

    /**
     * t176 (STARTUP-RESET-01): a source change or OFF→periodic switch must allow a new startup pass.
     * Runs off the main thread, so the caller never blocks the UI.
     */
    fun resetStartupFlag(context: Context) {
        startupDone = false
        val appContext = context.applicationContext
        scope.launch {
            delay(STARTUP_DELAY_MS)
            runDue(appContext, startup = true)
        }
    }

    /** Test/diagnostic view of the startup gate. */
    fun isStartupDone(): Boolean = startupDone

    private suspend fun runDue(context: Context, startup: Boolean) {
        val playlistInterval = Prefs.getAutoUpdatePlaylist(context)
        val epgInterval = Prefs.getAutoUpdateEpg(context)
        val now = System.currentTimeMillis()
        val firstPass = startup && !startupDone

        val playlistDue = AutoUpdateConfig.shouldRefresh(
            interval = playlistInterval,
            lastSuccessAt = Prefs.getPlaylistLastSuccess(context),
            lastFailureAt = Prefs.getPlaylistLastFailure(context),
            failureCount = Prefs.getPlaylistFailCount(context),
            startupRefreshDone = !firstPass,
            now = now,
        )
        val epgDue = AutoUpdateConfig.shouldRefresh(
            interval = epgInterval,
            lastSuccessAt = Prefs.getLastEpgUpdateTime(context),
            lastFailureAt = Prefs.getEpgLastFailure(context),
            failureCount = Prefs.getEpgFailCount(context),
            startupRefreshDone = !firstPass,
            now = now,
        )
        if (!playlistDue && !epgDue) {
            if (startup) startupDone = true
            return
        }

        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (playlistDue) {
                    Log.i(TAG, "playlist auto refresh (startup=$startup)")
                    val ok = BackgroundSourceUpdater.refreshPlaylistUrgent(context)
                    onPlaylistResult(context, ok)
                }
                if (epgDue) {
                    val source = Prefs.getEpgSource(context)
                    if (source.isNotBlank()) {
                        Log.i(TAG, "epg auto refresh (startup=$startup)")
                        val data = BackgroundSourceUpdater.refreshEpg(context, source)
                        onEpgResult(context, data != null)
                    }
                }
            }
        }
        if (startup) startupDone = true
    }

    private fun onPlaylistResult(context: Context, ok: Boolean) {
        val now = System.currentTimeMillis()
        if (ok) Prefs.setPlaylistLastSuccess(context, now) else Prefs.recordPlaylistFailure(context, now)
    }

    private fun onEpgResult(context: Context, ok: Boolean) {
        val now = System.currentTimeMillis()
        if (ok) {
            Prefs.setLastEpgUpdateTime(context, now)
            Prefs.clearEpgFailure(context)
        } else {
            Prefs.recordEpgFailure(context, now)
        }
    }
}
