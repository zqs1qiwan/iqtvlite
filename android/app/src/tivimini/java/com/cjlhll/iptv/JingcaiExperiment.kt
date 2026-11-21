package com.cjlhll.iptv

/**
 * t231: 睛彩 app-side diagnosis — **one experiment at a time, all default OFF**.
 *
 * Evidence from the device side already rules out the obvious causes: the same URL returns HTTP 200 at
 * ~8.3 Mbps for 6 s, the stream is H.264 Main@4.0 8-bit 4:2:0 progressive 1920×1080, UA does not matter,
 * and CCTV channels play fine on the same box. So the problem is in **this app's playback pipeline** for
 * that kind of stream, and the only way to find it is a single-variable switch plus a diagnostic line.
 *
 * This object holds the pure policy (mode parsing/cycling, extractor settings per mode, diagnostic text).
 * The Android wiring lives in `StreamPlayback`.
 */
object JingcaiExperiment {

    const val TAG = "IQTV-JINGCAI"

    /** Default TS timestamp search window (bytes) — the current production value. */
    const val TS_SEARCH_BYTES_DEFAULT = 1_000_000

    /** E1: the 睛彩 stream carries many null packets, so the first PTS may sit beyond 1 MB. */
    const val TS_SEARCH_BYTES_E1 = 4_000_000

    enum class Mode(val id: String, val label: String) {
        OFF("off", "关闭"),
        E1("e1", "E1 TS搜索4MB"),
        E2("e2", "E2 系统HTTP源"),
        E3("e3", "E3 禁用字幕注入"),
        // t15：PCR/PTS 时间轴错位实验（默认关，单变量）。
        A("a", "A 丢PCR-only包"),
        B("b", "B PMT PCR→0x1FFF"),
        AUTO("auto", "自动时间轴修复(>60s)"),
        C("c", "C 时基归零(设计)"),
    }

    fun parse(id: String?): Mode = Mode.entries.firstOrNull { it.id == id } ?: Mode.OFF

    fun next(current: Mode): Mode = Mode.entries[(current.ordinal + 1) % Mode.entries.size]

    /** t15：时间轴实验（A/B/AUTO）需要包级改写，其余模式（含 C 设计占位）一律零改动。 */
    fun touchesPacketBytes(mode: Mode): Boolean =
        mode == Mode.A || mode == Mode.B || mode == Mode.AUTO

    /** t15：实验 C 只是设计占位，不实现字节改写（见 docs §6）。 */
    fun timebaseRebase(mode: Mode): Boolean = mode == Mode.C

    /** E1 only changes the extractor's timestamp search window. */
    fun tsSearchBytes(mode: Mode): Int =
        if (mode == Mode.E1) TS_SEARCH_BYTES_E1 else TS_SEARCH_BYTES_DEFAULT

    /** E2 bypasses OkHttpDataSource (custom UA / request properties) for the plain platform one. */
    fun usePlatformHttpDataSource(mode: Mode): Boolean = mode == Mode.E2

    /** E3 removes the CEA608 caption tracks and the in-extraction subtitle parsing. */
    fun injectCaptions(mode: Mode): Boolean = mode != Mode.E3

    /**
     * t231/t15 诊断行，打印一次（无首帧时）。
     *
     * t15（PERF/DIAG-01）：**bytesRead 必须来自 DataSource 实读计数**——直播流永远不会有
     * `onLoadCompleted`，旧实现只累加 `loadEventInfo.bytesLoaded` 导致 bytes=0 的误判。
     */
    fun diagnosticLine(
        mode: Mode,
        bytesRead: Long,
        firstByteMs: Long,
        downstreamFormat: String?,
        firstFrame: Boolean,
        playbackState: Int,
        code: String = "-",
    ): String {
        val fmt = downstreamFormat ?: "none"
        return "mode=${mode.id} bytesRead=$bytesRead firstByteMs=$firstByteMs format=$fmt " +
            "firstFrame=$firstFrame state=$playbackState code=$code"
    }
}
