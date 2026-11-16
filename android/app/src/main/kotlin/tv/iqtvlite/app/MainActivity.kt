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
    private lateinit var groupScroll: HorizontalScrollView
    private lateinit var epgScroll: HorizontalScrollView
    private lateinit var epgRecycler: RecyclerView
    private lateinit var epgAdapter: EpgGridAdapter
    private var allChannels: List<Channel> = emptyList()
    private var currentGroup: String? = null
    private var currentSource = 0
    private var currentPos = -1
    private var epgUrls: List<String> = emptyList()
    private var epgCache: String? = null
    private var showingEpg = false

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
        groupScroll = findViewById(R.id.group_scroll)
        epgScroll = findViewById(R.id.epg_scroll)
        epgRecycler = findViewById(R.id.epg_recycler)

        // ── EPG 网格 ────────────────────────────────────────────────
        epgAdapter = EpgGridAdapter(epgTimeFormat()) { row, programme ->
            // 点击节目 → 找到对应频道并播放
            allChannels.find { normalizeTvg(it.tvgId) == normalizeTvg(row.tvgId) }?.let { play(it) }
            status.text = "▶ ${programme.title} · ${row.name}"
        }
        epgRecycler.layoutManager = LinearLayoutManager(this)
        epgRecycler.adapter = epgAdapter

        findViewById<Button>(R.id.tab_channels).setOnClickListener { showTab(false) }
        findViewById<Button>(R.id.tab_epg).setOnClickListener {
            showTab(true)
            loadEpg()
        }

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

    private fun normalizeTvg(id: String?): String =
        (id ?: "").trim().lowercase(java.util.Locale.ROOT).replace(' ', '_')

    private fun showTab(epg: Boolean) {
        showingEpg = epg
        swipe.visibility = if (epg) View.GONE else View.VISIBLE
        groupScroll.visibility = if (epg) View.GONE else View.VISIBLE
        epgScroll.visibility = if (epg) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.tab_channels).setBackgroundColor(
            if (epg) 0xFF1A222C.toInt() else 0xFF2E7CF6.toInt()
        )
        findViewById<Button>(R.id.tab_epg).setBackgroundColor(
            if (epg) 0xFF2E7CF6.toInt() else 0xFF1A222C.toInt()
        )
    }

    /** 加载 EPG 并生成网格（复用 Rust core 的流式 XMLTV 解析 + 网格算法）。 */
    private fun loadEpg() {
        if (epgUrls.isEmpty()) {
            status.text = "当前播放列表没有 EPG 源"
            return
        }
        val url = epgUrls.first()
        status.text = "正在加载节目单…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val xml = epgCache ?: http.newCall(Request.Builder().url(url).build())
                        .execute().use { resp ->
                            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                            resp.body!!.string()
                        }.also { epgCache = it }
                    val now = System.currentTimeMillis() / 1000
                    val windowStart = now - 1800 // 向前 30 分钟
                    val windowEnd = now + 6 * 3600 // 向后 6 小时
                    val tvgIds = org.json.JSONArray().apply {
                        allChannels.mapNotNull { it.tvgId }.distinct().take(300).forEach { put(it) }
                    }.toString()
                    val json = RustBridge.parseEpgGrid(xml, tvgIds, now, windowStart, windowEnd, 30)
                    EpgGridData.fromJson(json)
                } catch (e: Exception) {
                    Log.e(TAG, "EPG load failed", e)
                    null
                }
            }
            if (result != null) {
                epgAdapter.submit(result)
                status.text = "EPG: ${result.matched}/${result.requested} 频道匹配 · ${result.guideChannels} 个指南频道"
            } else {
                status.text = "节目单加载失败（可重试）"
            }
        }
    }

    private fun loadSource(index: Int) {
        val src = SOURCES[index]
        epgCache = null
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
                epgUrls = result.epgUrls
                adapter.submit(result.channels)
                buildGroupChips(result.groups)
                status.text = "已加载 ${result.channels.size} 个频道 · ${result.groups.size} 个分组 · EPG 源 ${result.epgUrls.size}"
                if (showingEpg) loadEpg()
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
        val rawUa = ch.userAgent ?: parts.getOrNull(1)?.removePrefix("User-Agent=")
        val ua = resolveUserAgent(rawUa)
        android.util.Log.i(TAG, "playing ${ch.name} url=${url.take(60)} ua=$ua")
        // 换台前更新流的 UA（OkHttp interceptor 每个请求读取）
        PlaybackService.setStreamUserAgent(ua)

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
        status.text = "▶ ${ch.name} (${if (rawUa != null) rawUa else "默认"} UA)"
        playerView.controllerShowTimeoutMs = 3000
    }

    /**
     * 播放列表里用的是短名（如 `http-user-agent="VLC"` / `|User-Agent=VLC`），
     * 必须映射成完整的 UA 字符串 —— 实测老白的 CCTV1/CCTV2 源用 TiviMate UA 返回 403，
     * 只有 VLC UA 才能播放。
     */
    private fun resolveUserAgent(raw: String?): String = when {
        raw == null -> "TiviMate/5.1.6 (Android 12)"
        raw.equals("VLC", ignoreCase = true) || raw.startsWith("VLC/") -> "VLC/3.0.20 LibVLC/3.0.20"
        raw.equals("TiviMate", ignoreCase = true) || raw.startsWith("TiviMate/") -> "TiviMate/5.1.6 (Android 12)"
        raw.startsWith("Mozilla/") -> raw
        else -> raw // 自定义完整 UA
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
