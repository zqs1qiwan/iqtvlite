package tv.iqtvlite.app

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * MultiView 2x2 —— 四路直播同屏（TiviMate Premium 功能）。
 *
 * - 4 个独立 ExoPlayer + 4 个 PlayerView（SurfaceView 硬解）
 * - 每窗口独立音频：只有一个窗口出声，点哪个窗口就切过去
 * - 某路失败只显示错误，不影响其他窗口
 */
class MultiViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "iqtv-multiview"
        private const val WINDOW_COUNT = 4

        /** 通过静态引用传递频道列表（data class 无需 Serializable） */
        var pendingChannels: List<Channel> = emptyList()

        fun open(channels: List<Channel>) {
            pendingChannels = channels.take(WINDOW_COUNT)
        }
    }

    private val players = arrayOfNulls<ExoPlayer>(WINDOW_COUNT)
    private val views = arrayOfNulls<PlayerView>(WINDOW_COUNT)
    private val labels = arrayOfNulls<TextView>(WINDOW_COUNT)
    private val hints = arrayOfNulls<TextView>(WINDOW_COUNT)
    private val containers = arrayOfNulls<FrameLayout>(WINDOW_COUNT)
    private val viewIds = intArrayOf(R.id.pv0, R.id.pv1, R.id.pv2, R.id.pv3)
    private val labelIds = intArrayOf(R.id.label0, R.id.label1, R.id.label2, R.id.label3)
    private val hintIds = intArrayOf(R.id.hint0, R.id.hint1, R.id.hint2, R.id.hint3)
    private val containerIds = intArrayOf(R.id.window0, R.id.window1, R.id.window2, R.id.window3)

    private var channels: List<Channel> = emptyList()
    private var audioWindow = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multiview)
        channels = pendingChannels

        for (i in 0 until WINDOW_COUNT) {
            views[i] = findViewById(viewIds[i])
            labels[i] = findViewById(labelIds[i])
            hints[i] = findViewById(hintIds[i])
            containers[i] = findViewById(containerIds[i])
            hints[i]?.visibility = View.GONE

            val ch = channels.getOrNull(i)
            labels[i]?.text = ch?.name ?: "空窗口 $i"
            containers[i]?.setOnClickListener { setAudioWindow(i) }
        }

        channels.indices.forEach { i -> startWindow(i, channels[i]) }
        setAudioWindow(0)
        findViewById<View>(R.id.btn_close).setOnClickListener { finish() }
        Toast.makeText(this, "点任意窗口切换声音", Toast.LENGTH_SHORT).show()
    }

    private fun startWindow(index: Int, ch: Channel) {
        try {
            val parts = ch.url.split("|", limit = 2)
            val url = parts[0]
            val rawUa = ch.userAgent ?: parts.getOrNull(1)?.removePrefix("User-Agent=")
            val ua = when {
                rawUa == null -> "TiviMate/5.1.6 (Android 12)"
                rawUa.equals("VLC", ignoreCase = true) || rawUa.startsWith("VLC") -> "VLC/3.0.20 LibVLC/3.0.20"
                else -> rawUa
            }

            // 每个窗口独立 DataSource（每路要求不同 UA）
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", ua).build())
                }
                .build()
            val dsFactory = DefaultDataSource.Factory(
                this,
                OkHttpDataSource.Factory(client).setUserAgent(ua),
            )
            val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dsFactory)
            val player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
            players[index] = player
            views[index]?.player = player
            player.volume = 0f

            val item = MediaItem.Builder()
                .setUri(url)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(3000)
                        .setMinPlaybackSpeed(0.97f)
                        .setMaxPlaybackSpeed(1.03f)
                        .build()
                )
                .build()
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = true
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e(TAG, "window $index error: ${error.message}")
                    hints[index]?.text = "无法播放: ${error.message?.take(40)}"
                    hints[index]?.visibility = View.VISIBLE
                }
                override fun onRenderedFirstFrame() {
                    hints[index]?.visibility = View.GONE
                    Log.i(TAG, "window $index first frame: ${ch.name}")
                }
            })
            Log.i(TAG, "window $index start: ${ch.name} ua=$ua")
        } catch (e: Exception) {
            Log.e(TAG, "window $index player creation failed", e)
            hints[index]?.text = "解码器不足"
            hints[index]?.visibility = View.VISIBLE
        }
    }

    /** 只有一个窗口出声；被选中窗口边框高亮。 */
    private fun setAudioWindow(index: Int) {
        audioWindow = index
        players.indices.forEach { i -> players[i]?.volume = if (i == audioWindow) 1f else 0f }
        containers.indices.forEach { i ->
            containers[i]?.setBackgroundColor(
                if (i == audioWindow) 0xFF2E7CF6.toInt() else 0xFF000000.toInt()
            )
        }
        channels.getOrNull(index)?.let {
            Toast.makeText(this, "声音: ${it.name}", Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "audio window -> $index")
    }

    override fun onDestroy() {
        players.indices.forEach { i ->
            views[i]?.player = null
            players[i]?.release()
            players[i] = null
        }
        pendingChannels = emptyList()
        super.onDestroy()
    }
}
