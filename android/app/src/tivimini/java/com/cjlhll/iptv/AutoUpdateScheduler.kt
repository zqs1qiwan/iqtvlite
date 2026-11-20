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
    /** t189 (SOURCE-SAVE-02): only one network pass at a time, even across tick/save/manual. */
    private val refreshGate = ManualRefreshPolicy.RefreshGate()

    // t189 (SOURCE-SAVE-02): replay = 1 so an Activity created AFTER the refresh (MainActivity
    // finishing → PlayerActivity starting) still receives the "源已更新" result instead of missing it.
    private val _events = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)
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
        scope.launch {
            mutex.withLock {
                if (!refreshGate.tryEnter()) return@withLock
                // t189 (SOURCE-SAVE-02): the startup gate is touched INSIDE the lock, so a concurrent
                // 60 s tick can no longer observe "reset but not yet done" and start a second pass.
                startupDone = false
                var allOk = true
                var attempted = false
                try {
                    val live = Prefs.getLiveSource(appContext)
                    if (live.isNotBlank()) {
                        attempted = true
                        val ok = runCatching { BackgroundSourceUpdater.refreshPlaylistUrgent(appContext) }
                            .getOrDefault(false)
                        onPlaylistResult(appContext, ok)
                        if (!ok) allOk = false
                    }
                    val epg = Prefs.getEpgSource(appContext)
                    if (epg.isNotBlank()) {
                        attempted = true
                        val data = runCatching { BackgroundSourceUpdater.refreshEpg(appContext, epg) }.getOrNull()
                        onEpgResult(appContext, data != null)
                        if (data == null) allOk = false
                    }
                } finally {
                    startupDone = true
                    refreshGate.exit()
                }
                // t189 (SOURCE-EVENT-01): never claim success when something failed.
                _events.tryEmit(
                    when {
                        !attempted -> "源未配置"
                        allOk -> "源已更新"
                        else -> "源更新失败"
                    }
                )
            }
        }
    }

    /** Manual refresh — bypasses the interval AND the failure backoff (user asked for it). */
    fun refreshNow(context: Context, playlist: Boolean, epg: Boolean) {
        val appContext = context.applicationContext
        scope.launch {
            mutex.withLock {
                // t189: a manual request must not stack on top of a running automatic/save pass.
                if (!refreshGate.tryEnter()) return@withLock
                try {
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
                } finally {
                    refreshGate.exit()
                }
            }
        }
    }

    /**
     * t189 (MANUAL-POLICY-01): THE single manual-EPG entry point. Every caller (settings row, EPG
     * settings row, legacy flag) goes through here so one user action can only ever mean one download.
     */
    fun requestManualEpg(context: Context) {
        refreshNow(context, playlist = false, epg = true)
    }

    /** Test/diagnostic view of the startup gate. */
    fun isStartupDone(): Boolean = startupDone

    private suspend fun runDue(context: Context, startup: Boolean) {
        if (refreshGate.isInFlight()) return
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
            if (!refreshGate.tryEnter()) return@withLock
            try {
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
            } finally {
                refreshGate.exit()
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
        // t189 (TEST-01): the transition comes from AutoUpdateConfig, so the scheduler and the tests
        // share one definition of "success clears the backoff".
        val state = if (ok) {
            Prefs.setLastEpgUpdateTime(context, now)
            AutoUpdateConfig.afterSuccess()
        } else {
            AutoUpdateConfig.afterFailure(now, Prefs.getEpgFailCount(context))
        }
        Prefs.setEpgFailureState(context, state.lastFailureAt, state.failCount)
    }
}
