package tv.iqtvlite.app

/**
 * 播放诊断报告（R2）。本地生成，可复制；不含任何遥测上传。
 * 数据来源全部是本机可回收的实时状态。
 */
object DiagnosticsReport {
    data class Input(
        val appVersion: String,
        val channelName: String,
        val channelUrl: String,
        val userAgent: String,
        val engine: String,
        val resolution: String,
        val codec: String,
        val bufferedMs: Long,
        val positionMs: Long,
        val lastZapMs: Long,
        val avgZapMs: Long,
        val zapCount: Int,
        val coldStartMs: Long,
        val epgMatched: Int,
        val epgRequested: Int,
        val channelCount: Int,
        val sourceName: String,
    )

    fun build(i: Input): String = buildString {
        appendLine("═══ iqtvlite 播放诊断 ═══")
        appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        appendLine("版本: ${i.appVersion}")
        appendLine("")
        appendLine("【当前频道】")
        appendLine("频道: ${i.channelName}")
        appendLine("源: ${i.sourceName}")
        appendLine("地址: ${i.channelUrl}")
        appendLine("UA: ${i.userAgent}")
        appendLine("")
        appendLine("【播放引擎】")
        appendLine("引擎: ${i.engine}")
        appendLine("分辨率: ${i.resolution}")
        appendLine("编码: ${i.codec}")
        appendLine("缓冲: ${i.bufferedMs} ms / 位置: ${i.positionMs} ms")
        appendLine("")
        appendLine("【性能】")
        if (i.lastZapMs > 0) {
            appendLine("上次换台首帧: ${i.lastZapMs} ms")
            appendLine("平均换台: ${i.avgZapMs} ms (${i.zapCount} 次)")
        }
        appendLine("冷启动可交互: ${i.coldStartMs} ms")
        appendLine("")
        appendLine("【数据】")
        appendLine("频道数: ${i.channelCount}")
        appendLine("EPG 匹配: ${i.epgMatched}/${i.epgRequested}")
    }
}
