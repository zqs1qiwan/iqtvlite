package com.cjlhll.iptv

import android.content.Context
import android.provider.Settings

/**
 * t27/t28：**冷启 rebuffer 三实验的运行时开关**（不需要重新出包）。
 *
 * t28 要在一次安装里 A/B 三个实验，因此把数值放到 `Settings.Global`（`adb shell settings put global …`），
 * **不写偏好、不改默认**：没设置时一律返回生产默认值（与旧行为逐字节相同）。
 *
 * ```bash
 * # 实验③：首帧前多等 500ms 再播放
 * adb shell settings put global iqtv_prebuffer_ms 500
 * # 实验②：afterRebuffer 2000→3000（必须同时把 minBuffer 抬到 >=3000，否则会破坏
 * #        "minBuffer >= afterRebuffer" 不变量并在启动时崩：CI10077）
 * adb shell settings put global iqtv_min_buffer_ms 3500
 * adb shell settings put global iqtv_after_rebuffer_ms 3000
 * # 实验①：放宽 loader 的最大缓冲（6000→8000，让它更早更多地预取）
 * adb shell settings put global iqtv_max_buffer_ms 8000
 * # 还原
 * adb shell settings delete global iqtv_min_buffer_ms
 * adb shell settings delete global iqtv_prebuffer_ms
 * adb shell settings delete global iqtv_after_rebuffer_ms
 * adb shell settings delete global iqtv_max_buffer_ms
 * ```
 *
 * 不变量（USAB2-01 / CI10077）：`minBufferMs(2500) >= afterRebuffer`，所以 afterRebuffer 被
 * 钳到 1000..2400；非法/越界值一律回落默认（永不把非法参数丢给 DefaultLoadControl）。
 */
object PerfTuning {

    const val KEY_PREBUFFER = "iqtv_prebuffer_ms"
    const val KEY_AFTER_REBUFFER = "iqtv_after_rebuffer_ms"
    const val KEY_MAX_BUFFER = "iqtv_max_buffer_ms"
    const val KEY_MIN_BUFFER = "iqtv_min_buffer_ms"

    /** prebuffer 允许范围（ms）；0 = 不等待（默认）。 */
    val PREBUFFER_RANGE = 0..2_000

    /** afterRebuffer 允许范围；最终还会被 [minBufferMs] 钳住（保证 LoadControl 不变量）。 */
    val AFTER_REBUFFER_RANGE = 1_000..4_000

    /** minBuffer 允许范围（>= 生产默认 2500；抬它才能把 afterRebuffer 抬到 3000）。 */
    val MIN_BUFFER_RANGE = 2_500..8_000

    /** maxBuffer 允许范围（必须 > minBufferMs，且不超过 16s）。 */
    val MAX_BUFFER_RANGE = 6_000..16_000

    /** 纯解析：空/非法/越界 → [default]（不打日志、不抛异常）。 */
    fun parse(value: String?, default: Int, range: IntRange): Int {
        val v = value?.trim()?.toIntOrNull() ?: return default
        return if (v in range) v else default
    }

    fun prebufferMs(context: Context): Int = parse(
        read(context, KEY_PREBUFFER),
        ColdStartPolicy.PREBUFFER_BEFORE_PLAY_MS.toInt(),
        PREBUFFER_RANGE,
    )

    /** minBuffer（loader 补流的最低水位）；默认生产值 2500。 */
    fun minBufferMs(context: Context): Int = parse(
        read(context, KEY_MIN_BUFFER),
        LoadControlTuning.MIN_BUFFER_MS,
        MIN_BUFFER_RANGE,
    )

    /** afterRebuffer：恒被 minBuffer 钳住，**永不破坏** "minBuffer >= afterRebuffer"（CI10077）。 */
    fun afterRebufferMs(context: Context): Int {
        val after = parse(
            read(context, KEY_AFTER_REBUFFER),
            LoadControlTuning.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            AFTER_REBUFFER_RANGE,
        )
        return minOf(after, minBufferMs(context))
    }

    fun maxBufferMs(context: Context): Int = parse(
        read(context, KEY_MAX_BUFFER),
        LoadControlTuning.MAX_BUFFER_MS,
        MAX_BUFFER_RANGE,
    )

    private fun read(context: Context, key: String): String? = runCatching {
        Settings.Global.getString(context.contentResolver, key)
    }.getOrNull()
}
