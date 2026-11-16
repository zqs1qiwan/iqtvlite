package tv.iqtvlite.app

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

        private fun buildPlayer(context: android.content.Context): ExoPlayer {
            // DataSource 工厂：每次创建连接时读取当前 UA
            val okHttpFactory = OkHttpDataSource.Factory(httpClient)
                .setUserAgent(currentUserAgent.get())

            // 包装成 DefaultDataSource（支持本地文件 + content://）
            val dataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)

            val mediaSourceFactory = DefaultMediaSourceFactory(context)
                .setDataSourceFactory(dataSourceFactory)

            return ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
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
