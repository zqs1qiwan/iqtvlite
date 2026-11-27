package com.cjlhll.iptv

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.SurfaceView
import kotlin.math.abs

/**
 * Pure Kotlin display-mode descriptor used by [FrameRateMatcher] so matching logic
 * stays unit-testable without an Android Display instance.
 */
data class DisplayModeSpec(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRateHz: Float,
)

/**
 * Selects the lowest-jitter display refresh rate matching the stream frame rate
 * (exact 1x first, then integer multiple 2x..5x, keeping current resolution).
 */
object FrameRateMatcher {
    private const val MIN_VALID_FPS = 10f
    private const val MAX_VALID_FPS = 120f
    private const val EXACT_TOLERANCE_HZ = 0.12f

    fun selectBestDisplayMode(
        videoFps: Float,
        currentMode: DisplayModeSpec,
        supportedModes: List<DisplayModeSpec>,
    ): DisplayModeSpec? {
        if (!videoFps.isFinite() || videoFps < MIN_VALID_FPS || videoFps > MAX_VALID_FPS) return null
        if (supportedModes.isEmpty()) return null

        val sameResModes = supportedModes.filter {
            it.width == currentMode.width && it.height == currentMode.height && it.refreshRateHz > 0f
        }
        val pool = sameResModes.ifEmpty {
            supportedModes.filter {
                it.width >= currentMode.width && it.height >= currentMode.height && it.refreshRateHz > 0f
            }
        }
        if (pool.isEmpty()) return null

        var best: DisplayModeSpec? = null
        var bestScore = Double.MAX_VALUE

        for (candidate in pool) {
            val hz = candidate.refreshRateHz
            var candidateScore = Double.MAX_VALUE
            for (mult in 1..5) {
                val perFrameDiff = abs((hz / mult.toFloat()) - videoFps).toDouble()
                if (perFrameDiff <= EXACT_TOLERANCE_HZ.toDouble()) {
                    // Prefer 1x exact refresh rate first, then 2x (e.g. 25fps -> 50Hz, 29.97fps -> 59.94Hz),
                    // with a tiny bonus for staying on the already-active mode to avoid unnecessary HDMI blanking.
                    val multPenalty = (mult - 1) * 0.05
                    val currentBonus = if (candidate.modeId == currentMode.modeId) -0.02 else 0.0
                    val score = perFrameDiff + multPenalty + currentBonus
                    if (score < candidateScore) {
                        candidateScore = score
                    }
                }
            }
            if (candidateScore < bestScore) {
                bestScore = candidateScore
                best = candidate
            }
        }

        val chosen = best ?: return null
        return if (chosen.modeId == currentMode.modeId) null else chosen
    }
}

/**
 * Debounced (1,200 ms) Auto Frame Rate controller that only commits a display mode switch
 * after stream FPS stabilizes, preventing HDMI blackouts while rapidly channel-surfing.
 */
class FrameRateController(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private var pendingRunnable: Runnable? = null
    @Volatile
    private var lastAppliedFps: Float = 0f
    @Volatile
    private var lastAppliedHz: Float = 0f
    @Volatile
    private var lastModeId: Int? = null

    fun onVideoFormatChanged(
        activity: Activity?,
        surfaceView: SurfaceView?,
        enabled: Boolean,
        videoFps: Float,
    ) {
        cancelPending()
        if (!enabled) {
            if (lastModeId != null) {
                restoreDefault(activity)
            }
            return
        }
        if (activity == null || !videoFps.isFinite() || videoFps < 10f || videoFps > 120f) return
        if (lastModeId != null && abs(lastAppliedFps - videoFps) <= 0.1f) return

        val task = Runnable {
            pendingRunnable = null
            applyNow(activity, surfaceView, videoFps)
        }
        pendingRunnable = task
        handler.postDelayed(task, DEBOUNCE_MS)
    }

    fun restoreDefault(activity: Activity?, surfaceView: SurfaceView? = null) {
        cancelPending()
        if (lastModeId == null && surfaceView == null) return
        val hadMode = lastModeId != null
        lastAppliedFps = 0f
        lastAppliedHz = 0f
        lastModeId = null
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            runCatching {
                val lp = activity.window.attributes
                if (lp.preferredDisplayModeId != 0) {
                    lp.preferredDisplayModeId = 0
                    activity.window.attributes = lp
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    val surface = surfaceView?.holder?.surface
                    if (surface != null && surface.isValid) {
                        surface.setFrameRate(
                            0f,
                            Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                        )
                    }
                } else if (Build.VERSION.SDK_INT >= 30) {
                    val surface = surfaceView?.holder?.surface
                    if (surface != null && surface.isValid) {
                        surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    }
                }
            }
            if (hadMode) {
                PlaybackDiagCenter.recordEvent("AFR", "Restored default display mode")
            }
        }
    }

    fun cancelPending() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
    }

    fun activeRefreshHz(): Float = lastAppliedHz

    private fun applyNow(activity: Activity, surfaceView: SurfaceView?, videoFps: Float) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (Build.VERSION.SDK_INT >= 31) {
            val surface = surfaceView?.holder?.surface
            if (surface != null && surface.isValid) {
                runCatching {
                    surface.setFrameRate(
                        videoFps,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                    )
                }
            }
        } else if (Build.VERSION.SDK_INT >= 30) {
            val surface = surfaceView?.holder?.surface
            if (surface != null && surface.isValid) {
                runCatching {
                    surface.setFrameRate(
                        videoFps,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    )
                }
            }
        }

        val display = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                activity.display ?: @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
            } else {
                @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
            }
        }.getOrNull() ?: return

        val activeMode = display.mode ?: return
        val currentSpec = DisplayModeSpec(
            modeId = activeMode.modeId,
            width = activeMode.physicalWidth,
            height = activeMode.physicalHeight,
            refreshRateHz = activeMode.refreshRate,
        )
        val supportedSpecs = display.supportedModes.orEmpty().map { m ->
            DisplayModeSpec(
                modeId = m.modeId,
                width = m.physicalWidth,
                height = m.physicalHeight,
                refreshRateHz = m.refreshRate,
            )
        }
        val chosen = FrameRateMatcher.selectBestDisplayMode(videoFps, currentSpec, supportedSpecs)
        if (chosen == null) {
            lastAppliedFps = videoFps
            lastAppliedHz = currentSpec.refreshRateHz
            PlaybackDiagCenter.updateAfr(enabled = true, activeHz = currentSpec.refreshRateHz, matchedFps = videoFps)
            return
        }

        val lp = activity.window.attributes
        if (lp.preferredDisplayModeId != chosen.modeId) {
            lp.preferredDisplayModeId = chosen.modeId
            activity.window.attributes = lp
        }
        lastAppliedFps = videoFps
        lastAppliedHz = chosen.refreshRateHz
        lastModeId = chosen.modeId
        PlaybackDiagCenter.updateAfr(enabled = true, activeHz = chosen.refreshRateHz, matchedFps = videoFps)
        PlaybackDiagCenter.recordEvent(
            "AFR",
            "fps=${String.format(java.util.Locale.US, "%.2f", videoFps)} -> mode=${chosen.modeId} (${chosen.width}x${chosen.height}@${String.format(java.util.Locale.US, "%.2f", chosen.refreshRateHz)}Hz)"
        )
    }

    companion object {
        const val DEBOUNCE_MS = 1_200L
    }
}
