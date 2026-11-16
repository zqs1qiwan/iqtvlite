package tv.iqtvlite.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * DVR 录制服务 —— 零转码字节落盘。
 *
 * 设计（见 docs/ 的 DVR 方案）：
 * - Rust core 生成录制计划（文件名、URL、UA、格式）
 * - 本服务用 OkHttp 拉流，copyTo(FileOutputStream) 直接写文件（不解码、不转码）
 * - 支持用户停止、计划时长、大小上限、断流重试
 */
class RecordingService : Service() {
    companion object {
        private const val TAG = "iqtv-dvr"
        private const val CHANNEL_ID = "iqtvlite-dvr"
        private const val EXTRA_PLAN = "plan"

        @Volatile
        var isRecording = false
            private set

        @Volatile
        var currentFile: String? = null
            private set

        fun start(ctx: Context, planJson: String) {
            val intent = Intent(ctx, RecordingService::class.java).putExtra(EXTRA_PLAN, planJson)
            ctx.startForegroundService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RecordingService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cancelled = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 播放列表/分片请求必须有有限超时，否则服务器不响应时永久挂起（之前 0=无限导致录制卡死）
        .readTimeout(20, TimeUnit.SECONDS)
        // TR100-G9 盒子 IPv6 出口不通，OkHttp 优先 IPv6 会挂起 → 强制 IPv4（见 NetUtil）
        .dns(NetUtil.ipv4OnlyDns)
        .build()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1001, buildNotification("准备录制…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val json = intent?.getStringExtra(EXTRA_PLAN)
        if (json == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        cancelled = false
        scope.launch { record(json) }
        return START_STICKY
    }

    private suspend fun record(planJson: String) {
        val file: File
        var total = 0L
        try {
            val plan = org.json.JSONObject(planJson)
            val url = plan.getString("url")
            val outputPath = plan.getString("outputPath")
            val ua = plan.optString("userAgent", "").ifEmpty { "VLC/3.0.20 LibVLC/3.0.20" }
            val stopTs = plan.optLong("stopTs", 0)
            val maxBytes = plan.optLong("maxBytes", 0)

            // HLS 录制输出 .ts（把分片拼接成可播的 MPEG-TS），而不是存 m3u8 文本
            val tsPath = if (url.contains(".m3u8", ignoreCase = true)) {
                outputPath.removeSuffix(".m3u8") + ".ts"
            } else outputPath
            file = File(tsPath)
            file.parentFile?.mkdirs()
            currentFile = file.absolutePath
            isRecording = true
            Log.i(TAG, "REC_START ${file.absolutePath} url=${url.take(60)} ua=$ua")

            FileOutputStream(file).use { out ->
                if (url.contains(".m3u8", ignoreCase = true)) {
                    total = recordHls(url, ua, out, stopTs, maxBytes)
                } else {
                    total = recordRaw(url, ua, out, stopTs, maxBytes)
                }
                out.flush()
            }
            Log.i(TAG, "REC_DONE bytes=$total file=${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "REC_ERROR", e)
        } finally {
            currentFile = null
            isRecording = false
            stopForeground(true)
            stopSelf()
        }
    }

    /** 裸 TS 流：零转码字节拷贝。 */
    private fun recordRaw(url: String, ua: String, out: FileOutputStream, stopTs: Long, maxBytes: Long): Long {
        val request = Request.Builder().url(url).header("User-Agent", ua).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val source = resp.body?.byteStream() ?: throw java.io.IOException("空响应体")
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var lastLog = 0L
            while (!cancelled) {
                if (stopTs > 0 && System.currentTimeMillis() / 1000 >= stopTs) break
                if (maxBytes > 0 && total >= maxBytes) break
                val n = source.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                if (total - lastLog >= 5 * 1024 * 1024) { lastLog = total; Log.i(TAG, "REC_PROGRESS ${total / 1024 / 1024} MB") }
            }
            return total
        }
    }

    /**
     * HLS 录制：拉媒体播放列表 → 下载 .ts 分片 → 拼接成可播的 MPEG-TS 文件。
     * MPEG-TS 的分片可以直接字节拼接，无需转码。
     */
    private fun recordHls(url: String, ua: String, out: FileOutputStream, stopTs: Long, maxBytes: Long): Long {
        // 注意：Migu 的 `|` 是查询参数的一部分（ct=android|User-Agent=VLC），OkHttp 会原样保留，
        // 不能剥离 —— 剥离会导致 403（实测）。保留原始 URL。
        var playlistUrl = url
        var total = 0L
        var lastLog = 0L
        val downloadedSegments = HashSet<String>()

        while (!cancelled) {
            if (stopTs > 0 && System.currentTimeMillis() / 1000 >= stopTs) break
            if (maxBytes > 0 && total >= maxBytes) break

            val body = fetchText(playlistUrl, ua)
            // master playlist → 选第一个 variant
            if (body.contains("#EXT-X-STREAM-INF")) {
                val variant = body.lines().map { it.trim() }.firstOrNull {
                    it.isNotEmpty() && !it.startsWith("#")
                } ?: throw java.io.IOException("master playlist 无 variant")
                val next = resolveUrl(playlistUrl, variant)
                Log.i(TAG, "HLS master->variant")
                if (next == playlistUrl) throw java.io.IOException("variant 解析失败（与 master 相同）")
                playlistUrl = next
                continue
            }

            // 媒体播放列表 → 下载所有未下载过的 .ts 分片
            val segments = body.lines().map { it.trim() }.filter {
                it.isNotEmpty() && !it.startsWith("#") && (it.contains(".ts") || it.contains(".m4s"))
            }
            Log.d(TAG, "HLS media playlist segments=${segments.size}")
            if (segments.isEmpty()) {
                Log.w(TAG, "HLS 播放列表无 TS 分片，等待重试")
                Thread.sleep(2000)
                continue
            }
            for (seg in segments) {
                if (cancelled) break
                val segUrl = resolveUrl(playlistUrl, seg)
                if (!downloadedSegments.add(segUrl)) continue // 直播滚动列表已下载过的跳过
                // 只录最近的若干分片（避免把整个归档窗口都录下来）
                if (downloadedSegments.size > 30) break
                try {
                    total += downloadTo(segUrl, ua, out)
                    if (total - lastLog >= 5 * 1024 * 1024) { lastLog = total; Log.i(TAG, "REC_PROGRESS ${total / 1024 / 1024} MB") }
                } catch (e: Exception) {
                    Log.w(TAG, "分片下载失败: ${e.message}")
                }
                if (maxBytes > 0 && total >= maxBytes) break
            }
            // 直播：2 秒后重新拉播放列表，拼接新分片
            Thread.sleep(2000)
        }
        return total
    }

    private fun fetchText(url: String, ua: String): String {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw java.io.IOException("空播放列表")
        }
    }

    private fun downloadTo(url: String, ua: String, out: FileOutputStream): Long {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val src = resp.body?.byteStream() ?: throw java.io.IOException("空分片")
            val buf = ByteArray(64 * 1024)
            var n = 0L
            var r: Int
            while (src.read(buf).also { r = it } > 0) { out.write(buf, 0, r); n += r }
            return n
        }
    }

    private fun resolveUrl(base: String, ref: String): String {
        // 注意：Migu 的 `|` 是查询参数的一部分（例如 ct=android|User-Agent=VLC），
        // 必须原样保留 —— 实测剥离或 %7C 编码都会导致 403。
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            java.net.URL(java.net.URL(base), ref).toString()
        } catch (_: Exception) {
            val cut = base.lastIndexOf('/')
            if (cut >= 0) base.substring(0, cut + 1) + ref else ref
        }
    }

    override fun onDestroy() {
        cancelled = true
        scope.cancel()
        isRecording = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "iqtvlite 录制", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("iqtvlite DVR")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
}
