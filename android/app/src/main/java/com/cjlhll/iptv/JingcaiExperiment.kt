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

    enum class Mode(val id: String, val label: String, val labelEn: String) {
        OFF("off", "关闭", "Off"),
        E1("e1", "E1 TS搜索4MB", "E1 TS search 4MB"),
        E2("e2", "E2 系统HTTP源", "E2 System HTTP source"),
        E3("e3", "E3 禁用字幕注入", "E3 Disable caption injection"),
        // t15：PCR/PTS 时间轴错位实验（默认关，单变量）。
        A("a", "A 丢PCR-only包", "A Drop PCR-only packets"),
        B("b", "B PMT PCR→0x1FFF", "B PMT PCR->0x1FFF"),
        AUTO("auto", "自动时间轴修复(>60s)", "Auto timeline fix (>60s)"),
        C("c", "C 时基归零(设计)", "C Timebase rebase"),
        // t54：第 5 轮实验（默认关）。
        D("d", "D 彻底去PCR(剥离adaptation)", "D Strip PCR adaptation"),
        E("e", "E PTS/DTS归零(重写PES头)", "E Zero PTS/DTS PES header"),
        F("f", "F 延迟1.2s起播", "F Delay start 1.2s"),
        G("g", "G 丢首个PAT前的前导字节", "G Drop pre-PAT bytes"),
        H("h", "H 失败后新DataSource重建", "H Rebuild DataSource on error");

        fun localizedLabel(lang: AppLang): String = if (lang == AppLang.EN) labelEn else label
    }

    fun parse(id: String?): Mode = Mode.entries.firstOrNull { it.id == id } ?: Mode.OFF

    fun next(current: Mode): Mode = Mode.entries[(current.ordinal + 1) % Mode.entries.size]

    /** t15：时间轴实验（A/B/AUTO）需要包级改写，其余模式（含 C 设计占位）一律零改动。 */
    fun touchesPacketBytes(mode: Mode): Boolean =
        mode == Mode.A || mode == Mode.B || mode == Mode.AUTO || mode == Mode.D ||
            mode == Mode.E || mode == Mode.G

    /** t54 实验 F：连接后延迟 [DELAYED_START_MS] 再读/播（验证「立刻读就崩」假设）。 */
    fun delayedStart(mode: Mode): Boolean = mode == Mode.F

    /** t54 实验 G：丢掉首个 PAT/PMT 之前的前导字节。 */
    fun dropLeadingBytes(mode: Mode): Boolean = mode == Mode.G

    const val DELAYED_START_MS = 1_200L

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
