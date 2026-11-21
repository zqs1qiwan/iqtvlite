package com.cjlhll.iptv

/**
 * t7：轻量 systrace 埋点。
 *
 * - `Trace.beginSection/endSection` 是 Android 原生 atrace 接口：**未开 trace 时几乎零开销**
 *   （一次字符串常量 + JNI 调用），所以可以常驻在热路径里；Perfetto/systrace 会看到
 *   `IQTV-<label>` 的 section。
 * - 另有 `mark()` 日志：仅在 `setprop log.tag.IQTV-PERF DEBUG` 时输出，供 acceptance 抓
 *   "打开抽屉 → 首帧" 的确定时间线（不依赖 uiautomator 的易碎判据）。
 *
 * 用法（采集）：
 * ```
 * adb shell setprop log.tag.IQTV-PERF DEBUG
 * adb shell am force-stop tv.iqtvlite.app.validation
 * adb shell am start -n tv.iqtvlite.app.validation/com.cjlhll.iptv.SplashActivity
 * # 打开抽屉后用 perfetto/systrace 抓 5s，或在 logcat 里看 IQTV-PERF 行
 * ```
 */
object PerfTrace {
    const val TAG = "IQTV-PERF"

    /** 把 label 包成 atrace section；未开 trace 时是 no-op 级别的开销。 */
    inline fun <T> section(label: String, block: () -> T): T {
        android.os.Trace.beginSection(label)
        try {
            return block()
        } finally {
            android.os.Trace.endSection()
        }
    }

    fun begin(label: String) {
        android.os.Trace.beginSection(label)
    }

    fun end() {
        android.os.Trace.endSection()
    }

    /** DEBUG 门控的一行日志（未开 log.tag.IQTV-PERF 时不产生任何字符串拼接开销）。 */
    inline fun d(message: () -> String) {
        if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
            android.util.Log.d(TAG, message())
        }
    }
}
