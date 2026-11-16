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

    /** 冷启动计时：进程启动 → UI 可交互 */
    private val coldStartTime = System.currentTimeMillis()

    /** 换台计时：发起播放 → 首帧（衡量「丝滑」的核心指标） */
    private var zapStartTime = 0L
    private var zapCount = 0
    private var zapTotalMs = 0L
    private var zapMaxMs = 0L

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 实测：iptv.laobaitv.net 会拒绝非浏览器 UA（TiviMate/VLC/iqtvlite 全部失败），
        // 只有 Mozilla 能拿到内容 → 播放列表/EPG 下载统一用浏览器 UA。
        // 流播放的 UA 单独由 PlaybackService 按频道设置（VLC）。
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
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
        epgAdapter = EpgGridAdapter(
            epgTimeFormat(),
            onProgramme = { row, programme ->
                // 点击节目 → 找到对应频道并播放
                allChannels.find { normalizeTvg(it.tvgId) == normalizeTvg(row.tvgId) }?.let { play(it) }
                status.text = "▶ ${programme.title} · ${row.name}"
            },
            onCatchup = { row, programme -> playCatchup(row, programme) },
        )
        epgRecycler.layoutManager = LinearLayoutManager(this)
        epgRecycler.adapter = epgAdapter

        findViewById<Button>(R.id.tab_channels).setOnClickListener { showTab(false) }
        findViewById<Button>(R.id.tab_epg).setOnClickListener {
            showTab(true)
            loadEpg()
        }
        findViewById<Button>(R.id.btn_multiview).setOnClickListener {
            if (allChannels.isEmpty()) {
                Toast.makeText(this, "请先加载频道", Toast.LENGTH_SHORT).show()
            } else {
                // 优先从当前位置开始取 4 个频道
                val start = currentPos.coerceAtLeast(0)
                val four = (0 until 4).mapNotNull { i -> allChannels.getOrNull(start + i) }
                MultiViewActivity.open(four)
                startActivity(android.content.Intent(this, MultiViewActivity::class.java))
            }
        }
        findViewById<Button>(R.id.btn_record).setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_subtitle).setOnClickListener {
            val label = SubtitleManager.cycle(player, this)
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            status.text = "字幕: $label"
        }

        // ── 播放器（Media3，直播主引擎） ──────────────────────────────
        player = PlaybackService.ensurePlayer(this)
        playerView.player = player
        // ── TV 大屏字幕样式 ─────────────────────────────────────────
        // 10-foot UI：字更大、黑色半透明底、白色字，保证任何画面都清晰
        val captionStyle = androidx.media3.ui.CaptionStyleCompat(
            android.graphics.Color.WHITE,
            android.graphics.Color.parseColor("#C0000000"), // 半透明黑底
            android.graphics.Color.TRANSPARENT,
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            null,
        )
        playerView.subtitleView?.apply {
            setStyle(captionStyle)
            setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 32f) // TV 大屏
            setBottomPaddingFraction(0.08f)
        }
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
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // 流切换后刷新可用字幕轨道（Red Bull 的 CEA-608 在这里出现）
                SubtitleManager.refresh(player)
                val n = SubtitleManager.available.size
                Log.i(TAG, "TRACKS_CHANGED groups=${tracks.groups.size} textTracks=$n")
                tracks.groups.forEachIndexed { gi, g ->
                    if (g.type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                        Log.i(TAG, "  TEXT group $gi len=${g.length} format=${g.getTrackFormat(0).sampleMimeType} lang=${g.getTrackFormat(0).language}")
                    }
                }
                if (n > 0) Log.i(TAG, "SUBTITLES_AVAILABLE count=$n labels=${SubtitleManager.available.map { it.label }}")
            }
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
                // 换台耗时统计（首帧 - 发起播放）
                if (zapStartTime > 0) {
                    val ms = System.currentTimeMillis() - zapStartTime
                    zapCount++
                    zapTotalMs += ms
                    if (ms > zapMaxMs) zapMaxMs = ms
                    Log.i(TAG, "ZAP_MS $ms avg=${zapTotalMs / zapCount} max=$zapMaxMs count=$zapCount")
                    zapStartTime = 0
                }
                status.text = "▶ ${ch?.name ?: ""}"
            }
        })

        // ── 频道列表 ────────────────────────────────────────────────
        adapter = ChannelAdapter(
            onSelect = { ch -> play(ch) },
            onToggleFavorite = { ch ->
                val added = FavoritesStore.toggleFavorite(this, ch.id)
                adapter.setLists(FavoritesStore.favorites(this), FavoritesStore.recents(this))
                adapter.refresh()
                Toast.makeText(this, if (added) "★ 已收藏 ${ch.name}" else "☆ 已取消收藏", Toast.LENGTH_SHORT).show()
            },
            onLongPress = { ch -> showChannelMenu(ch) },
        )
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
        // ① 同步加载缓存 → 频道列表立即可交互（冷启动秒开）
        val cacheHit = loadFromCache(0)
        Log.i(TAG, "COLD_START_CACHE hit=$cacheHit ms=${System.currentTimeMillis() - coldStartTime}")
        // ② 后台拉网络 → 成功后刷新
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
        if (showingEpg) status.text = "正在加载节目单…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val now = System.currentTimeMillis() / 1000
                    val windowStart = now - 1800
                    val windowEnd = now + 6 * 3600

                    // 多个 EPG 源按优先级合并：序号越小优先级越高（第一个源胜出）
                    // 当前播放列表的 x-tvg-url 优先；其余 URL 作为补充（填缺）
                    val sources = org.json.JSONArray()
                    epgUrls.distinct().forEachIndexed { idx, url ->
                        val xml = if (idx == 0 && epgCache != null) epgCache!! else
                            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                                // EPG 端点是裸 gzip（无 Content-Encoding 头），需手动解压
                                RustBridge.readBody(resp.body!!)
                            }
                        if (idx == 0) epgCache = xml
                        sources.put(org.json.JSONObject().apply {
                            put("id", "epg$idx")
                            put("priority", 10 + idx * 10) // 10, 20, 30...
                            put("xml", xml)
                        })
                    }

                    val params = org.json.JSONObject().apply {
                        put("now", now)
                        put("windowStart", windowStart)
                        put("windowEnd", windowEnd)
                        put("slotMinutes", 30)
                        put("tvgIds", org.json.JSONArray().apply {
                            allChannels.mapNotNull { it.tvgId }.distinct().take(300).forEach { put(it) }
                        })
                        put("sources", sources)
                    }.toString()

                    val json = RustBridge.buildMergedEpgGrid(params)
                    val stats = org.json.JSONObject(json).optJSONArray("mergeStats")
                    Log.i(TAG, "EPG_MERGE sources=${epgUrls.size} stats=$stats")
                    EpgGridData.fromJson(json)
                } catch (e: Exception) {
                    Log.e(TAG, "EPG load failed", e)
                    null
                }
            }
            if (result != null) {
                epgAdapter.nowSecs = result.now
                epgAdapter.submit(result)
                if (showingEpg) status.text = "EPG: ${result.matched}/${result.requested} 频道匹配 · ${result.guideChannels} 个指南频道"
                Log.i(TAG, "EPG_RESULT matched=${result.matched} requested=${result.requested} guide=${result.guideChannels} rows=${result.rows.size} programmes=${result.rows.sumOf { it.programmes.size }}")
            } else {
                if (showingEpg) status.text = "节目单加载失败（可重试）"
                Log.w(TAG, "EPG_RESULT failed")
            }
        }
    }

    /** 从本地缓存同步加载频道（冷启动秒开，零网络等待） */
    private fun loadFromCache(index: Int): Boolean {
        val cached = PlaylistCache.load(this, index) ?: return false
        val result = try {
            RustBridge.parsePlaylist(cached, (index + 1).toLong())
        } catch (e: Exception) { return false }
        if (result.channels.isEmpty()) return false
        applyPlaylist(result, cached, true)
        return true
    }

    private fun applyPlaylist(result: PlaylistResult, rawContent: String, fromCache: Boolean) {
        allChannels = result.channels
        epgUrls = result.epgUrls
        adapter.setCustomGroups(TiviMatePrefs.customGroups(this@MainActivity))
        val order = TiviMatePrefs.channelOrder(this@MainActivity)
        if (order.isNotEmpty()) {
            val rank = order.withIndex().associate { it.value to it.index }
            allChannels = result.channels.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        }
        val hidden = TiviMatePrefs.hiddenChannels(this@MainActivity)
        if (hidden.isNotEmpty()) allChannels = allChannels.filterNot { hidden.contains(it.id) }
        adapter.submit(allChannels)
        adapter.setLists(FavoritesStore.favorites(this@MainActivity), FavoritesStore.recents(this@MainActivity))
        adapter.refresh()
        buildGroupChips(result.groups)
        status.text = "已加载 ${allChannels.size} 个频道 · ${result.groups.size} 个分组${if (fromCache) "（缓存）" else ""}"
        if (!fromCache) PlaylistCache.save(this@MainActivity, currentSource, rawContent)
        Log.i(TAG, "PLAYLIST_APPLIED channels=${allChannels.size} fromCache=$fromCache COLD_START_MS=${System.currentTimeMillis() - coldStartTime}")
    }

    private fun loadSource(index: Int) {
        val src = SOURCES[index]
        epgCache = null
        swipe.isRefreshing = true
        status.text = "正在加载 ${src.name}…"
        lifecycleScope.launch {
            val loaded: Pair<PlaylistResult?, String> = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(src.url).build()
                    val body = http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        // 老白直播是 UTF-8（已验证）
                        resp.body!!.string()
                    }
                    // 第二个 URL 是 HTML 配置页 → 用它的 ?m3u=true 参数生成真实播放列表
                    // （实测 http://iptv.laobaitv.net/?m3u=true 返回 30KB 的 #EXTM3U）
                    val isHtml = body.trimStart().startsWith("<", ignoreCase = true)
                    val content = when {
                        !isHtml -> body
                        src.url.contains("laobaitv.net") -> {
                            val sep = if (src.url.contains("?")) "&" else "?"
                            val m3uUrl = src.url + sep + "m3u=true"
                            Log.i(TAG, "M3U_URL $m3uUrl")
                            http.newCall(Request.Builder().url(m3uUrl).build()).execute().use { resp ->
                                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                                resp.body!!.string()
                            }
                        }
                        else -> {
                            val actual = extractPlaylistUrl(body)
                            if (actual != null) http.newCall(Request.Builder().url(actual).build())
                                .execute().use { it.body!!.string() }
                            else body
                        }
                    }
                    val result = RustBridge.parsePlaylist(content, (index + 1).toLong())
                    if (result.channels.isEmpty()) throw Exception("播放列表无频道")
                    result to content
                } catch (e: Exception) {
                    Log.e(TAG, "load failed", e)
                    null to ""
                }
            }
            val result = loaded.first
            val networkContent = loaded.second
            swipe.isRefreshing = false
            if (result != null && result.channels.isNotEmpty()) {
                applyPlaylist(result, networkContent, false)
                Log.i(TAG, "PLAYLIST_RESULT channels=${result.channels.size} epgUrls=${result.epgUrls}")
                if (epgUrls.isNotEmpty()) loadEpg()
            } else {
                // 网络失败时用缓存继续工作
                if (allChannels.isEmpty()) loadFromCache(index)
                status.text = "加载失败，请检查网络或下拉重试"
                Toast.makeText(this@MainActivity, "加载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 从 laobaitv.net 的 HTML 落地页里找真实 M3U 地址。
     *
     * 注意：http://iptv.laobaitv.net 实际上是一个**IPTV 配置查询页**（带 isp/region/city 参数），
     * 页面里没有直接的 .m3u 链接。硬的正则要避免误匹配到 Google Fonts（含 "api"）等资源。
     * 找不到真实播放列表时返回 null，调用方自动回退到第一个源（lbzs.laobaitv.net）。
     */
    private fun extractPlaylistUrl(html: String): String? {
        // ① 明确的 .m3u / .m3u8 链接（排除字体/CDN 资源）
        val direct = Regex("""https?://[^"''<>\s\\]+\.m3u8?(?:\?[^"''<>\s\\]*)?""", RegexOption.IGNORE_CASE)
        direct.find(html)?.value?.let { if (!it.contains("googleapis")) return it }
        // ② 显式标注的播放列表生成接口（data-* 属性 / href）
        val api = Regex("""(?:href|src|data-url|action)=["'](https?://[^"']*(?:get\.php|playlist|\.m3u)[^"']*)["']""", RegexOption.IGNORE_CASE)
        api.find(html)?.groups?.get(1)?.value?.let { return it }
        return null
    }

    /** 频道长按操作菜单（TiviMate 风格） */
    private fun showChannelMenu(ch: Channel) {
        val fav = if (FavoritesStore.isFavorite(this, ch.id)) "☆ 取消收藏" else "★ 收藏"
        val lock = if (TiviMatePrefs.isLocked(this, ch.id)) "🔓 解锁频道" else "🔒 锁定频道"
        val hide = if (TiviMatePrefs.isHidden(this, ch.id)) "👁 显示频道" else "🚫 隐藏频道"
        val actions = arrayOf(fav, lock, hide, "⏰ 设置提醒", "✏️ 移动到分组…")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        FavoritesStore.toggleFavorite(this, ch.id)
                        adapter.setLists(FavoritesStore.favorites(this), FavoritesStore.recents(this))
                        adapter.refresh()
                    }
                    1 -> {
                        TiviMatePrefs.toggleLocked(this, ch.id)
                        Toast.makeText(this, lock, Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        TiviMatePrefs.toggleHidden(this, ch.id)
                        applyHiddenFilter()
                        Toast.makeText(this, hide, Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        TiviMatePrefs.addReminder(this, TiviMatePrefs.Reminder(
                            ch.tvgId ?: ch.providerId, ch.name,
                            System.currentTimeMillis() / 1000 + 300, // 示例：5 分钟后
                        ))
                        Toast.makeText(this, "已设置提醒（设置页可管理）", Toast.LENGTH_SHORT).show()
                    }
                    4 -> showGroupPicker(ch)
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showGroupPicker(ch: Channel) {
        val groups = (allChannels.map { it.group }.distinct().sorted().take(20)).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("移动 ${ch.name} 到分组")
            .setItems(groups) { _, which ->
                TiviMatePrefs.setCustomGroup(this, ch.tvgId ?: ch.providerId, groups[which])
                Toast.makeText(this, "已移动到 ${groups[which]}（刷新后生效）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun applyHiddenFilter() {
        val hidden = TiviMatePrefs.hiddenChannels(this)
        val visible = allChannels.filterNot { hidden.contains(it.id) }
        adapter.submit(visible)
        adapter.setLists(FavoritesStore.favorites(this), FavoritesStore.recents(this))
        adapter.refresh()
    }

    private fun buildGroupChips(groups: List<String>) {
        groupRow.removeAllViews()
        // 虚拟分组：收藏 / 最近观看 / 全部（TiviMate 风格）
        val chips = listOf("★收藏" to "__favorites__", "最近观看" to "__recents__", "全部" to null) +
            groups.take(50).map { it to it }
        chips.forEach { (label, key) ->
            Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener {
                    currentGroup = key
                    adapter.setLists(FavoritesStore.favorites(this@MainActivity), FavoritesStore.recents(this@MainActivity))
                    adapter.filter(searchEdit.text.toString(), key)
                    val count = if (key == null) allChannels.size else adapter.itemCount
                    status.text = "$label: $count 个频道"
                }
                groupRow.addView(this)
            }
        }
        adapter.setLists(FavoritesStore.favorites(this), FavoritesStore.recents(this))
    }

    private var urlIndex = 0
    private fun play(ch: Channel) {
        // 家长控制：锁定频道需 PIN
        if (TiviMatePrefs.isLocked(this, ch.id) && TiviMatePrefs.isParentalEnabled(this)) {
            val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("家长控制")
                .setMessage("频道 ${ch.name} 已锁定，请输入 PIN")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    if (input.text.toString() == TiviMatePrefs.getPin(this)) playUnlocked(ch)
                    else Toast.makeText(this, "PIN 错误", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null).show()
            return
        }
        playUnlocked(ch)
    }

    private fun playUnlocked(ch: Channel) {
        currentPos = allChannels.indexOfFirst { it.id == ch.id }.let { if (it >= 0) it else currentPos }
        urlIndex = 0
        FavoritesStore.markWatched(this, ch.id)
        adapter.setLists(FavoritesStore.favorites(this), FavoritesStore.recents(this))
        adapter.refresh()
        Log.i(TAG, "WATCHED ${ch.id} ${ch.name} recents=${FavoritesStore.recents(this).size}")
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
        zapStartTime = System.currentTimeMillis()
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

    /** 即时录制当前频道：Rust 生成计划 → RecordingService 零转码写文件。 */
    private fun toggleRecording() {
        if (RecordingService.isRecording) {
            RecordingService.stop(this)
            status.text = "录制已停止"
            Toast.makeText(this, "录制已停止", Toast.LENGTH_SHORT).show()
            return
        }
        val ch = allChannels.getOrNull(currentPos)
        if (ch == null) {
            Toast.makeText(this, "请先选择一个频道", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val plan = withContext(Dispatchers.IO) {
                try {
                    val params = org.json.JSONObject().apply {
                        put("title", ch.name)
                        put("id", ch.id)
                        put("channelId", ch.id)
                        put("url", ch.url.substringBefore("|"))
                        val alts = org.json.JSONArray()
                        ch.altUrls.forEach { alts.put(it.substringBefore("|")) }
                        put("altUrls", alts)
                        put("source", "instant")
                        put("userAgent", resolveUserAgent(ch.userAgent))
                        put("outputDir", getExternalFilesDir("recordings")?.absolutePath ?: "/sdcard/Recordings")
                        put("maxBytes", 500L * 1024 * 1024) // 单文件上限 500MB
                    }.toString()
                    val result = org.json.JSONObject(RustBridge.buildRecordingPlan(params))
                    if (result.optBoolean("ok", false)) result.toString() else null
                } catch (e: Exception) {
                    Log.e(TAG, "recording plan failed", e)
                    null
                }
            }
            if (plan != null) {
                RecordingService.start(this@MainActivity, plan)
                val fileName = org.json.JSONObject(plan).optString("fileName")
                status.text = "● 正在录制: $fileName"
                Toast.makeText(this@MainActivity, "开始录制 $fileName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "无法创建录制计划", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Catch-up 回看：长按 EPG 里已播出的节目，由 Rust core 生成归档 URL 并播放。
     * 支持播放列表标记的全部 catchup 模式（Shift/Append/Default/Flussonic）。
     */
    private fun playCatchup(row: EpgRow, programme: EpgProgramme) {
        val ch = allChannels.find { normalizeTvg(it.tvgId) == normalizeTvg(row.tvgId) }
        if (ch == null) {
            Toast.makeText(this, "找不到频道 ${row.name}", Toast.LENGTH_SHORT).show()
            return
        }
        val cfg = ch.catchup
        if (cfg == null || !cfg.hasArchive) {
            Toast.makeText(this, "${ch.name} 不支持回看", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val url = withContext(Dispatchers.IO) {
                try {
                    val params = org.json.JSONObject().apply {
                        put("liveUrl", ch.url.substringBefore("|"))
                        put("type", cfg.type)
                        cfg.source?.let { put("source", it) }
                        put("days", cfg.days)
                        put("providerTz", "Asia/Shanghai") // 老白直播 EPG 用 +0800
                        put("start", programme.start)
                        put("stop", programme.stop)
                        put("now", System.currentTimeMillis() / 1000)
                        programme.catchupId?.let { put("catchupId", it) }
                    }.toString()
                    val result = org.json.JSONObject(RustBridge.buildCatchupUrl(params))
                    if (result.optBoolean("ok", false)) result.getString("url") else null
                } catch (e: Exception) {
                    Log.e(TAG, "catchup failed", e)
                    null
                }
            }
            if (url != null) {
                Log.i(TAG, "CATCHUP_URL $url")
                playUrlDirect(url, "${ch.name} · 回看: ${programme.title}", ch.userAgent)
            } else {
                Toast.makeText(this@MainActivity, "该节目暂无回看源", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 直接播放一个 URL（catch-up 用），不修改当前频道索引。 */
    private fun playUrlDirect(url: String, label: String, rawUa: String?) {
        val ua = resolveUserAgent(rawUa)
        PlaybackService.setStreamUserAgent(ua)
        val mediaItem = MediaItem.Builder().setUri(url).build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
        status.text = "▶ $label"
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
