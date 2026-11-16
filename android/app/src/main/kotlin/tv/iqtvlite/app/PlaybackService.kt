package tv.iqtvlite.app

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 后台播放服务 —— MediaSession 让系统媒体键（播放/暂停）与音频焦点正确工作。
 *
 * UA 处理：老白直播的部分频道要求 VLC UA（用 TiviMate UA 会 403），
 * 而播放列表用短名 `http-user-agent="VLC"` 标记。这里用可变的 UA holder，
 * 每次换台前由 MainActivity 设置，DataSource 创建时读取。
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    companion object {
        private const val TAG = "iqtv-playback"

        /** 当前流请求使用的 User-Agent。换台前由 UI 更新。 */
        val currentUserAgent = AtomicReference("TiviMate/5.1.6 (Android 12)")

        /** OkHttp 客户端 —— 保温连接池（换台速度关键），UA 由 interceptor 每请求动态注入。 */
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // 每请求读取当前 UA —— 不同频道要求不同 UA（实测 CCTV1 要 VLC）
                val req = chain.request().newBuilder()
                    .header("User-Agent", currentUserAgent.get())
                    .build()
                chain.proceed(req)
            }
            .build()

        @Volatile
        var sharedPlayer: ExoPlayer? = null
            private set

        fun ensurePlayer(context: android.content.Context): ExoPlayer {
            return sharedPlayer ?: synchronized(this) {
                sharedPlayer ?: buildPlayer(context).also { sharedPlayer = it }
            }
        }

        /** 当前字幕 preference（选中的 CEA-608 channel，0 = 关闭） */
        val captionChannel = AtomicReference(0)

        private fun buildPlayer(context: android.content.Context): ExoPlayer {
            // DataSource 工厂：每次创建连接时读取当前 UA
            val okHttpFactory = OkHttpDataSource.Factory(httpClient)
                .setUserAgent(currentUserAgent.get())
            val dataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)

            // ── 关键：让 TS 提取器读出 CEA-608 内嵌字幕 ───────────────────
            // Red Bull 等 IPTV 源的字幕是 CEA-608，嵌在 TS 的 closed-caption 轨道里。
            // Media3 默认不开 caption reader —— 这是「字幕支持不好」的根因。
            // 未在下层声明的 CEA-608（Red Bull 直播 TS 就是这种）显式注册 CC1
            val ccFormats = listOf(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
                    .setAccessibilityChannel(1) // CC1
                    .setLanguage("en")
                    .build()
            )
            val extractorsFactory = DefaultExtractorsFactory()
                // 注册 CEA-608 CC1 字幕格式 + 放宽 TS 时间戳搜索（很多 IPTV TS 没有 AUD/IDR）
                .setTsSubtitleFormats(ccFormats)
                .setTsExtractorTimestampSearchBytes(1_000_000)

            // ── HLS 里的内嵌 CEA-608 ────────────────────────────────
            // Media3 的 DefaultMediaSourceFactory 内部会创建 HlsMediaSource，
            // 并把上面的 extractorsFactory 传进去；setTsSubtitleFormats 注册的
            // CEA-608 CC1 会让 TS 分片里的 CEA-608 被识别为字幕轨。
            val mediaSourceFactory = DefaultMediaSourceFactory(
                dataSourceFactory,
                extractorsFactory,
                DefaultSubtitleParserFactory(),
            ).experimentalParseSubtitlesDuringExtraction(true)

            // ── 低延迟 LoadControl：换台速度关键 ──────────────────────
            // Media3 默认 minBuffer=50s / maxBuffer=50s，对 IPTV 直播过大。
            // 实测优化值（见 docs/05-playback.md）：
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1_500,   // minBufferMs
                    6_000,   // maxBufferMs
                    500,     // bufferForPlaybackMs
                    1_000,   // bufferForPlaybackAfterRebufferMs
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            return ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true,
                    )
                }
        }

        /** 换台前调用：更新后续 DataSource 的 UA。 */
        fun setStreamUserAgent(ua: String) {
            currentUserAgent.set(ua)
            android.util.Log.i(TAG, "stream UA -> $ua")
        }

        /**
         * 应用播放偏好（AFR / 音频直通）。
         * Media3 的 setAudioAttributes 支持 C.AUDIO_CAPABILITY_AUDIO_PASSTHROUGH（API 33+）。
         * 自动帧率在 TV 上通过视频格式切换实现；Media3 不直接暴露，
         * 这里记录用户偏好并交给设备/显示层处理（在支持的设备上生效）。
         */
        fun applyPlaybackPrefs(context: android.content.Context, prefs: TiviMatePrefs.PlaybackPrefs) {
            val player = sharedPlayer ?: return
            val builder = androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            player.setAudioAttributes(builder.build(), true)
            // 自动帧率：仅在无缝切换时启用（Android TV 标准行为）
            player.setVideoChangeFrameRateStrategy(
                if (prefs.autoFrameRate) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
            )
            android.util.Log.i(TAG, "prefs applied afr=${prefs.autoFrameRate} passthrough=${prefs.audioPassthrough}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ensurePlayer(this)
        mediaSession = MediaSession.Builder(this, player)
            .setId("iqtvlite-session")
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
            sharedPlayer = null
        }
        super.onDestroy()
    }
}
