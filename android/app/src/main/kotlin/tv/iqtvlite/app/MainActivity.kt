package tv.iqtvlite.app

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * iqtvlite 第一版测试 APK。
 *
 * 架构：Kotlin 原生 Views（UI + Media3 执行） + Rust core（解析/EPG/决策）
 * 播放列表：老白直播的两个源，启动时并行拉取。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "iqtvlite"
        // 用户指定的两个播放列表
        private val SOURCES = listOf(
            Source("https://lbzs.laobaitv.net", "老白直播 · 主线路"),
            Source("http://iptv.laobaitv.net", "老白直播 · 备用线路"),
        )
    }

    data class Source(val url: String, val name: String)

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChannelAdapter
    private lateinit var status: TextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var searchEdit: EditText
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var groupRow: LinearLayout
    private var allChannels: List<Channel> = emptyList()
    private var currentGroup: String? = null
    private var currentSource = 0
    private var currentPos = -1

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // TiviMate UA —— 老白直播源里部分频道要求 VLC/TiviMate UA
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "TiviMate/5.1.6 (Android 12)")
                .build()
            chain.proceed(req)
        }
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player_view)
        recycler = findViewById(R.id.channel_list)
        status = findViewById(R.id.status)
        sourceSpinner = findViewById(R.id.source_spinner)
        searchEdit = findViewById(R.id.search_edit)
        swipe = findViewById(R.id.swipe_refresh)
        groupRow = findViewById(R.id.group_row)

        // ── 播放器（Media3，直播主引擎） ──────────────────────────────
        player = PlaybackService.ensurePlayer(this)
        playerView.player = player
        // 控制条可见性（用于 D-pad 换台逻辑：控制条显示时方向键交给 UI）
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                controllerVisible = visibility == android.view.View.VISIBLE
            }
        )
        // 硬解 + 低延迟缓冲（见 docs/05/06）
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true, // handleAudioFocus
        )
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) status.text = " buffering…"
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "playback error", error)
                status.text = "播放失败: ${error.message?.take(80)}"
                // 自动尝试备用线路（Rust core 决策的简化版：换 altUrl）
                tryNextUrl()
            }
            override fun onRenderedFirstFrame() {
                val ch = allChannels.getOrNull(currentPos)
                status.text = "▶ ${ch?.name ?: ""}"
            }
        })

        // ── 频道列表 ────────────────────────────────────────────────
        adapter = ChannelAdapter { ch -> play(ch) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // ── 源选择 ─────────────────────────────────────────────────
        val names = SOURCES.map { it.name }
        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentSource = pos
                loadSource(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                adapter.filter(s.toString(), currentGroup)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        swipe.setOnRefreshListener { loadSource(currentSource) }

        status.text = "Rust core: ${RustBridge.version()}"
        loadSource(0)
    }

    private fun loadSource(index: Int) {
        val src = SOURCES[index]
        swipe.isRefreshing = true
        status.text = "正在加载 ${src.name}…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(src.url).build()
                    val body = http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        // 老白直播是 UTF-8（已验证）
                        resp.body!!.string()
                    }
                    // 第二个 URL 是 HTML 落地页 → 提取里面的真实播放列表链接
                    val actual = if (body.trimStart().startsWith("<")) extractPlaylistUrl(body, src.url) else src.url
                    val content = if (actual == src.url) body else {
                        http.newCall(Request.Builder().url(actual).build()).execute().use { it.body!!.string() }
                    }
                    RustBridge.parsePlaylist(content, (index + 1).toLong())
                } catch (e: Exception) {
                    Log.e(TAG, "load failed", e)
                    null
                }
            }
            swipe.isRefreshing = false
            if (result != null && result.channels.isNotEmpty()) {
                allChannels = result.channels
                adapter.submit(result.channels)
                buildGroupChips(result.groups)
                status.text = "已加载 ${result.channels.size} 个频道 · ${result.groups.size} 个分组 · EPG 源 ${result.epgUrls.size}"
            } else {
                status.text = "加载失败，请检查网络或下拉重试"
                Toast.makeText(this@MainActivity, "加载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 从 laobaitv.net 的 HTML 落地页里找真实 M3U 地址。 */
    private fun extractPlaylistUrl(html: String, base: String): String {
        val regex = Regex("""https?://[^"''<>\s]+\.m3u8?[^"''<>\s]*""")
        regex.find(html)?.value?.let { return it }
        // 备选：找 /api/ 或 m3u 生成接口
        val api = Regex("""(https?://[^"''<>\s]*(?:m3u|playlist|api)[^"''<>\s]*)""", RegexOption.IGNORE_CASE)
        api.find(html)?.value?.let { return it }
        return base
    }

    private fun buildGroupChips(groups: List<String>) {
        groupRow.removeAllViews()
        val all = Button(this).apply {
            text = "全部"
            setOnClickListener { currentGroup = null; adapter.filter(searchEdit.text.toString(), null) }
        }
        groupRow.addView(all)
        groups.take(50).forEach { g ->
            Button(this).apply {
                text = g
                isAllCaps = false
                setOnClickListener { currentGroup = g; adapter.filter(searchEdit.text.toString(), g) }
                groupRow.addView(this)
            }
        }
    }

    private var urlIndex = 0
    private fun play(ch: Channel) {
        currentPos = allChannels.indexOfFirst { it.id == ch.id }.let { if (it >= 0) it else currentPos }
        urlIndex = 0
        playCurrentUrl(ch)
    }

    private fun tryNextUrl() {
        val ch = allChannels.getOrNull(currentPos) ?: return
        val all = listOf(ch.url) + ch.altUrls
        if (urlIndex + 1 < all.size) {
            urlIndex++
            playCurrentUrl(ch)
            status.text = "切换备用线路 ${urlIndex + 1}/${all.size}"
        } else {
            status.text = "所有线路都失败了: ${ch.name}"
        }
    }

    private fun playCurrentUrl(ch: Channel) {
        val all = listOf(ch.url) + ch.altUrls
        var url = all.getOrNull(urlIndex) ?: ch.url
        // 处理 Kodi 风格的 |User-Agent=VLC 后缀
        val parts = url.split("|", limit = 2)
        url = parts[0]
        val ua = ch.userAgent ?: parts.getOrNull(1)?.removePrefix("User-Agent=")
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(3000)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
        status.text = "▶ ${ch.name}"
        playerView.controllerShowTimeoutMs = 3000
    }

    /** 播放器的控制条是否可见（用两种 listener 接口都兼容的方式） */
    private var controllerVisible = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                if (controllerVisible) return super.onKeyDown(keyCode, event)
                zap(1); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (controllerVisible) return super.onKeyDown(keyCode, event)
                zap(-1); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun zap(delta: Int) {
        if (allChannels.isEmpty()) return
        val next = (currentPos + delta).coerceIn(0, allChannels.size - 1)
        currentPos = next
        adapter.setSelected(next)
        recycler.scrollToPosition(next)
        play(allChannels[next])
    }

    override fun onStop() {
        super.onStop()
        // 退到后台不停止播放（服务继续），但释放 UI 层
    }

    override fun onDestroy() {
        playerView.player = null
        super.onDestroy()
    }
}
