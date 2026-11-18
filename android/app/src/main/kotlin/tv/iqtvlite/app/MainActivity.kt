package tv.iqtvlite.app

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.Date
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
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import coil.load
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
        // 默认播放源（用户指定的两个官方线路）；手机推源的自定义源会在运行时加入
        private val DEFAULT_SOURCES = listOf(
            Source("https://lbzs.laobaitv.net", "老白直播 · 主线路"),
            Source("http://iptv.laobaitv.net", "老白直播 · 备用线路"),
        )
    }

    data class Source(val url: String, val name: String)

    /** 播放源列表：默认两个官方线路，手机推源的自定义地址在运行时加入 */
    private val sources = mutableListOf<Source>()
    private var pushedSource: Source? = null

    /** 重建播放源列表（推源的自定义源优先） */
    private fun rebuildSources() {
        val pushed = readPushedSource().also { pushedSource = it }
        sources.clear()
        if (pushed != null) sources.add(pushed)
        sources.addAll(DEFAULT_SOURCES)
    }

    /** 从 SharedPreferences 读取手机推源保存的地址（SourcePushActivity 写入） */
    private fun readPushedSource(): Source? {
        val prefs = getSharedPreferences("iqtv_prefs", MODE_PRIVATE)
        val live = prefs.getString("push_live_url", null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Source(live, "手机推送 · 自定义源")
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChannelAdapter
    private lateinit var status: TextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var searchEdit: EditText
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var groupRow: LinearLayout
    private lateinit var groupScroll: View
    private lateinit var epgScroll: View
    private lateinit var drawerContainer: View
    private lateinit var epgSearch: EditText
    private lateinit var epgSearchCount: TextView
    private lateinit var epgRecycler: RecyclerView
    private lateinit var epgAdapter: EpgGridAdapter      // 保留：EPG 数据解析结果与 catchup
    private lateinit var programAdapter: ProgramListAdapter
    private var currentEpgData: EpgGridData? = null
    private var currentEpgRow: EpgRow? = null
    private lateinit var posterGrid: RecyclerView
    private lateinit var posterAdapter: PosterAdapter
    private var posterMode = false
    /** 首次启动引导遮罩 */
    private var firstRunGuide: View? = null
    private val prefs by lazy { getSharedPreferences("iqtv_prefs", MODE_PRIVATE) }
    // TiviMate 风格浮层 UI
    private lateinit var uiLayer: View
    private lateinit var channelPanel: View
    private lateinit var infoCard: View
    private lateinit var infoTitle: TextView
    private lateinit var infoChannel: TextView
    private lateinit var infoLogo: android.widget.ImageView
    private lateinit var infoNumber: TextView
    private lateinit var infoTime: TextView
    private lateinit var infoRemaining: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoProgress: ProgressBar
    // PiP 预览（TiviMate 标志性功能）
    private lateinit var pipContainer: View
    private lateinit var pipPlayerView: PlayerView
    private lateinit var pipLabel: TextView
    private var previewPlayer: PreviewPlayer? = null
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var numberJumpBuffer = ""
    private var numberJumpRunnable: Runnable? = null
    private var infoCardHideRunnable: Runnable? = null
    private var rightLongPressRunnable: Runnable? = null
    private var rightLongPressFired = false
    /** tvgId -> (当前节目名, 进度%, 开始, 结束)，EPG 加载后填充，供信息卡 & 列表复用 */
    private var nowPlaying: Map<String, ProgramNow> = emptyMap()
    data class ProgramNow(val title: String, val pct: Int, val start: Long, val stop: Long, val nextTitle: String = "")
    private var allChannels: List<Channel> = emptyList()
    private var epgMatched = 0
    private var epgRequested = 0
    private var currentGroup: String? = null
    private var currentSource = 0
    private var currentPos = -1
    private var previousChannelId: Long = -1L  // 上一台（右键短按切换）
    private var epgUrls: List<String> = emptyList()
    private var epgCache: String? = null
    private var showingEpg = false
    private var drawerState = 0   // 0=全屏 1=频道+节目单 2=分组+频道（真机层级式）

    /** 冷启动计时：进程启动 → UI 可交互 */
    private val coldStartTime = System.currentTimeMillis()
    /** 冷启动分阶段计时 */
    private var tColdMark = System.currentTimeMillis()
    private fun coldPhase(name: String) {
        val now = System.currentTimeMillis()
        Log.i(TAG, "COLD_PHASE $name +${now - tColdMark}ms total=${now - coldStartTime}ms")
        tColdMark = now
    }

    /** 换台计时：发起播放 → 首帧（衡量「丝滑」的核心指标） */
    private var zapStartTime = 0L
    private var lastZapMs = 0L
    private var autoPlayColdMs = 0L
    private var zapCount = 0
    private var zapTotalMs = 0L
    private var zapMaxMs = 0L

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // TR100-G9 盒子 IPv6 出口不通 → 强制 IPv4（见 NetUtil，实测 curl -6 超时 / -4 秒回）
        .dns(NetUtil.ipv4OnlyDns)
        .connectionPool(NetUtil.sharedConnectionPool)
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
        coldPhase("setContentView")
        // 尽早启动缓存解析，与后面的 playerInit/view 初始化并行（省 ~300ms）
        startCachePrefetch(0)

        playerView = findViewById(R.id.player_view)
        recycler = findViewById(R.id.channel_list)
        status = findViewById(R.id.status)
        // TiviMate 风格：浮层默认隐藏，启动即满屏播放
        uiLayer = findViewById(R.id.ui_layer)
        channelPanel = findViewById(R.id.channel_panel)
        infoCard = findViewById(R.id.info_card)
        infoTitle = findViewById(R.id.info_title)
        infoChannel = findViewById(R.id.info_channel)
        infoLogo = findViewById(R.id.info_logo)
        infoNumber = findViewById(R.id.info_number)
        infoTime = findViewById(R.id.info_time)
        infoRemaining = findViewById(R.id.info_remaining)
        infoNext = findViewById(R.id.info_next)
        infoProgress = findViewById(R.id.info_progress)
        pipContainer = findViewById(R.id.pip_container)
        pipPlayerView = findViewById(R.id.pip_player)
        pipLabel = findViewById(R.id.pip_label)
        // TiviMate 风格顶栏时钟，每分钟更新
        val clock = findViewById<TextView>(R.id.top_clock)
        fun tickClock() {
            clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        }
        tickClock()
        clock.postDelayed(object : Runnable {
            override fun run() { tickClock(); clock.postDelayed(this, 30_000) }
        }, 30_000)
        sourceSpinner = findViewById(R.id.source_spinner)
        searchEdit = findViewById(R.id.search_edit)
        swipe = findViewById(R.id.swipe_refresh)
        groupRow = findViewById(R.id.group_row)
        groupScroll = findViewById(R.id.group_scroll)
        epgScroll = findViewById(R.id.epg_scroll)
        drawerContainer = findViewById(R.id.drawer_container)
        epgSearch = findViewById(R.id.epg_search)
        epgSearchCount = findViewById(R.id.epg_search_count)
        // R1 EPG 节目搜索：输入即过滤网格
        epgSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val n = epgAdapter.filter(s?.toString() ?: "")
                epgSearchCount.text = if (s.isNullOrEmpty()) "" else "$n 个频道"
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        epgRecycler = findViewById(R.id.epg_recycler)
        // EPG 时间刻度轴：从当前整点开始，每小时一格（2 slot × 160dp），与节目行对齐
        findViewById<LinearLayout>(R.id.epg_ruler).let { ruler ->
            val density = resources.displayMetrics.density
            val hourPx = (320 * density).toInt()
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            for (i in 0..6) {
                val tv = TextView(this).apply {
                    text = fmt.format(Date(cal.timeInMillis + i * 3600_000L))
                    textSize = 13f
                    setTextColor(android.graphics.Color.parseColor("#9AA4B2"))
                    gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(hourPx, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                ruler.addView(tv)
            }
        }
        posterGrid = findViewById(R.id.poster_grid)

        // ── 竖列节目单（真机 TiviMate Level1 风格：当前频道的节目时间表）──
        programAdapter = ProgramListAdapter { programme ->
            // 点击节目：播放当前频道（过去节目走回看）
            val now = System.currentTimeMillis() / 1000
            val ch = allChannels.getOrNull(currentPos)
            if (ch != null && programme.stop <= now) {
                currentEpgRow?.let { row -> playCatchup(row, programme) }
            } else {
                ch?.let { play(it) }
            }
        }
        epgRecycler.layoutManager = LinearLayoutManager(this)
        epgRecycler.adapter = programAdapter

        // 清除所有工具栏 Button 的默认 tint（避免焦点黄色，保持 tvOS 克制配色）
        for (id in intArrayOf(R.id.tab_channels, R.id.tab_epg, R.id.btn_viewmode,
                R.id.btn_multiview, R.id.btn_subtitle, R.id.btn_settings)) {
            findViewById<Button>(id).backgroundTintList = null
        }

        findViewById<Button>(R.id.tab_channels).setOnClickListener { showTab(false) }
        findViewById<Button>(R.id.btn_viewmode).setOnClickListener {
            posterMode = !posterMode
            posterGrid.visibility = if (posterMode) View.VISIBLE else View.GONE
            swipe.visibility = if (posterMode) View.GONE else View.VISIBLE
            (it as Button).text = if (posterMode) "列表" else "海报"
            if (posterMode) posterGrid.requestFocus()
            status.text = if (posterMode) "海报浏览模式" else "列表模式"
        }
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
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_push).setOnClickListener {
            startActivity(android.content.Intent(this, SourcePushActivity::class.java))
        }
        findViewById<Button>(R.id.btn_subtitle).setOnClickListener {
            val label = SubtitleManager.cycle(player, this)
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            status.text = "字幕: $label"
        }

        // ── 播放器（Media3，直播主引擎） ──────────────────────────────
        coldPhase("views+findViewById")
        player = PlaybackService.ensurePlayer(this)
        PlaybackService.applyPlaybackPrefs(this, TiviMatePrefs.playbackPrefs(this))
        PlaybackService.applyAspectMode(TiviMatePrefs.aspectMode(this))
        playerView.player = player
        coldPhase("playerInit")
        // TiviMate 风格：不用 Media3 控制条（它拦截 OK/方向键），导航完全交给我们的浮层逻辑
        playerView.useController = false
        playerView.isClickable = false
        playerView.isFocusable = false
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
                if (state == Player.STATE_BUFFERING) status.text = "正在缓冲…"
                // Media3 没有 STATE_PAUSED：暂停 = STATE_READY + playWhenReady=false
                if (state == Player.STATE_ENDED ||
                    (state == Player.STATE_READY && !player.playWhenReady)) saveCurrentResume()
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
                adapter.setLists(FavoritesStore.favorites(this), FavoritesStore.recents(this), FavoritesStore.mostWatched(this))
                adapter.refresh()
                Toast.makeText(this, if (added) "★ 已收藏 ${ch.name}" else "☆ 已取消收藏", Toast.LENGTH_SHORT).show()
            },
            onLongPress = { ch -> showChannelMenu(ch) },
            onFocus = { ch ->
                if (TiviMatePrefs.pipEnabled(this)) {
                    if (previewPlayer == null) previewPlayer = PreviewPlayer(this)
                    pipLabel.text = ch.name
                    Log.i(TAG, "PIP_FOCUS ${ch.name}")
                    previewPlayer?.focus(ch, pipPlayerView, uiHandler)
                }
            },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // ── tvOS 海报卡浏览器（4 列网格，焦点视差） ──────────────────
        posterAdapter = PosterAdapter(
            scope = lifecycleScope,
            onSelect = { ch -> play(ch) },
            onFocus = { ch ->
                if (TiviMatePrefs.pipEnabled(this)) {
                    if (previewPlayer == null) previewPlayer = PreviewPlayer(this)
                    pipLabel.text = ch.name
                    previewPlayer?.focus(ch, pipPlayerView, uiHandler)
                }
            },
        )
        val cols = 4
        posterGrid.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, cols)
        posterGrid.adapter = posterAdapter
        // 海报卡尺寸：面板 720dp / 4 列 ≈ 176dp 宽
        val cardW = resources.displayMetrics.density.let { (720f / cols - 16) * it }.toInt()
        val cardH = (cardW * 1.05f).toInt()
        posterAdapter.setItemSize(cardW, cardH)

        // ── 源选择（推源的自定义源优先排第一位） ──────────────────────
        rebuildSources()
        val names = sources.map { it.name }
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

        status.text = "TiviMate 体验 · 按 OK 打开频道列表"
        // 恢复已设置的提醒（设备重启后 AlarmManager 需要重新注册）
        TiviMatePrefs.reminders(this).forEach { r ->
            if (r.startTs * 1000 > System.currentTimeMillis()) {
                ReminderScheduler.schedule(this, r.tvgId, r.title, r.startTs)
            }
        }

        coldPhase("listeners+epgAdapter")
        // ① 同步加载缓存 → 频道列表立即可交互（冷启动秒开）
        val cacheHit = loadFromCache(0)
        coldPhase("loadFromCache")
        Log.i(TAG, "COLD_START_CACHE hit=$cacheHit ms=${System.currentTimeMillis() - coldStartTime}")
        // ② TiviMate 秒播：用缓存里的上次频道立即开播（不等网络刷新）
        if (cacheHit) autoPlayLastChannel()
        coldPhase("autoPlayLastChannel")
        // ③ 后台拉网络 → 成功后刷新（不打断当前播放）
        loadSource(0)
        // ④ 首次启动引导（只在第一次显示，按任意键消失）
        firstRunGuide = findViewById(R.id.first_run_guide)
        if (!prefs.getBoolean("guide_seen", false)) {
            firstRunGuide?.visibility = View.VISIBLE
            firstRunGuide?.setOnClickListener { dismissGuide() }
        }
    }

    /** 关闭首次引导并记住 */
    private fun dismissGuide() {
        if (firstRunGuide?.visibility != View.VISIBLE) return
        firstRunGuide?.visibility = View.GONE
        prefs.edit().putBoolean("guide_seen", true).apply()
    }

    /** 同步列表/海报两种视图的选中态 */
    private fun syncSelection(pos: Int) {
        adapter.setSelected(pos)
        allChannels.getOrNull(pos)?.let { posterAdapter.setSelected(it.id) }
    }

    /** TiviMate 秒播：启动即播放上次的频道（无历史则播第一个） */
    private var autoPlayed = false
    private fun autoPlayLastChannel() {
        if (autoPlayed || allChannels.isEmpty()) return
        val (lastId, _) = TiviMatePrefs.lastChannel(this)
        val ch = if (lastId >= 0) allChannels.find { it.id == lastId } ?: allChannels[0] else allChannels[0]
        autoPlayed = true
        currentPos = allChannels.indexOf(ch).coerceAtLeast(0)
        syncSelection(currentPos)
        playUnlocked(ch)
        autoPlayColdMs = System.currentTimeMillis() - coldStartTime
        Log.i(TAG, "AUTOPLAY ${ch.name} (秒播, COLD_START_MS=$autoPlayColdMs)")
    }

    private fun normalizeTvg(id: String?): String =
        (id ?: "").trim().lowercase(java.util.Locale.ROOT).replace(' ', '_')

    /** 竖列节目单：显示当前焦点频道的节目时间表（真机 TiviMate Level1 风格） */
    private fun updateProgramColumn() {
        val data = currentEpgData ?: return
        val ch = allChannels.getOrNull(currentPos) ?: return
        val row = data.rows.firstOrNull { normalizeTvg(it.tvgId) == normalizeTvg(ch.tvgId) }
            ?: data.rows.firstOrNull { normalizeTvg(it.name) == normalizeTvg(ch.name) }
        currentEpgRow = row
        if (row != null) {
            programAdapter.submit(row.programmes, data.now)
        } else {
            programAdapter.submit(emptyList(), data.now)
        }
    }

    private fun setDrawerWidth(dp: Int) {
        val px = (dp * resources.displayMetrics.density).toInt()
        drawerContainer.layoutParams = (drawerContainer.layoutParams as android.view.ViewGroup.LayoutParams).apply { width = px }
    }

    /** 层级式抽屉：左键循环 0全屏→1频道+节目单→2分组+频道→0（真机 TiviMate 逻辑） */
    private fun applyDrawerState(state: Int) {
        drawerState = state
        when (state) {
            0 -> {
                uiLayer.visibility = View.GONE
                pipContainer.visibility = View.GONE
                previewPlayer?.hide(pipPlayerView)
            }
            1 -> {  // 频道列 + 节目单列 + 视频
                setDrawerWidth(1150)
                uiLayer.visibility = View.VISIBLE
                channelPanel.visibility = View.GONE      // 分组列收起
                findViewById<View>(R.id.epg_scroll).visibility = View.VISIBLE
                showProgramColumnOnly()
                allChannels.getOrNull(currentPos)?.let {
                    recycler.scrollToPosition(currentPos)
                    recycler.post { recycler.findViewHolderForAdapterPosition(currentPos)?.itemView?.requestFocus() ?: recycler.requestFocus() }
                }
            }
            2 -> {  // 分组列 + 频道列 + 视频（节目单收起）
                setDrawerWidth(1100)
                uiLayer.visibility = View.VISIBLE
                channelPanel.visibility = View.VISIBLE
                findViewById<View>(R.id.epg_scroll).visibility = View.GONE
                recycler.post { groupRow.getChildAt(0)?.requestFocus() ?: recycler.requestFocus() }
            }
        }
    }

    /** Level1：隐藏分组栏内的搜索/源控件，只留频道+节目（布局层用 channel_panel 整体控制，此处占位） */
    private fun showProgramColumnOnly() { updateProgramColumn() }

    private fun showTab(epg: Boolean) {
        showingEpg = epg
        if (epg) loadEpg()
    }

    /** 兼容旧调用：显示/隐藏抽屉 */
    private fun setUiVisible(show: Boolean) {
        applyDrawerState(if (show) 1 else 0)
    }

    private fun uiIsVisible() = drawerState != 0

    /** 换台时右上浮出信息卡，几秒后自动隐藏（TiviMate 风格） */
    private fun showInfoCard(ch: Channel) {
        refreshInfoCard(ch)
        infoCard.visibility = View.VISIBLE
        infoCardHideRunnable?.let { infoCard.removeCallbacks(it) }
        val hide = Runnable { infoCard.visibility = View.GONE }
        infoCardHideRunnable = hide
        infoCard.postDelayed(hide, 5000)
    }

    /** R2 网络/播放诊断面板：收集本机实时状态，可一键复制（零遥测） */
    private fun showDiagnostics() {
        val ch = allChannels.getOrNull(currentPos)
        val fmt = player.videoFormat
        val fps = fmt?.frameRate ?: -1f
        val res = if (fmt != null && fmt.width > 0) "${fmt.width}×${fmt.height}" + (if (fps > 0) " @ ${fps.toInt()}fps" else " (直播)") else "未知"
        val codec = fmt?.codecs ?: "未知"
        val buffered = player.bufferedPosition
        val pos = player.currentPosition
        val input = DiagnosticsReport.Input(
            appVersion = RustBridge.version(),
            channelName = ch?.name ?: "无",
            channelUrl = ch?.url ?: "无",
            userAgent = PlaylistUrlResolver.resolveUserAgent(ch?.userAgent),
            engine = "Media3 ExoPlayer 1.11.1 (硬解优先)",
            resolution = res,
            codec = codec,
            bufferedMs = buffered,
            positionMs = pos,
            lastZapMs = lastZapMs,
            avgZapMs = if (zapCount > 0) zapTotalMs / zapCount else 0,
            zapCount = zapCount,
            coldStartMs = autoPlayColdMs,
            epgMatched = epgMatched,
            epgRequested = epgRequested,
            channelCount = allChannels.size,
            sourceName = sources.getOrNull(currentSource)?.name ?: "未知",
        )
        val text = DiagnosticsReport.build(input)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("播放诊断（仅本地）")
            .setMessage(text)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("iqtvlite 诊断", text))
                Toast.makeText(this, "诊断信息已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun refreshInfoCard(ch: Channel) {
        infoChannel.text = ch.name
        infoNumber.text = String.format("%03d", currentPos + 1)
        // 台标：透明PNG直接显示，无边框；加载失败清空
        infoLogo.setImageResource(R.drawable.ic_tv)
        if (!ch.logo.isNullOrBlank()) {
            infoLogo.load(ch.logo) { crossfade(true) }
        }
        val now = ch.tvgId?.let { nowPlaying[it] }
        if (now != null && now.title.isNotEmpty()) {
            infoTitle.text = now.title
            infoProgress.progress = now.pct
            if (now.start > 0 && now.stop > now.start) {
                val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                infoTime.text = "${fmt.format(java.util.Date(now.start * 1000))} – ${fmt.format(java.util.Date(now.stop * 1000))}"
                val mins = (now.stop - System.currentTimeMillis() / 1000) / 60
                infoRemaining.text = if (mins > 0) "$mins 分钟" else ""
            } else {
                infoTime.text = ""; infoRemaining.text = ""
            }
            infoProgress.visibility = View.VISIBLE
            // 下一节目预告（金标准：信息卡显示"接下来"）
            if (now.nextTitle.isNotEmpty()) {
                infoNext.text = "接下来：${now.nextTitle}"
                infoNext.visibility = View.VISIBLE
            } else {
                infoNext.visibility = View.GONE
            }
        } else {
            infoTitle.text = ch.name
            infoTime.text = "正在播放"
            infoRemaining.text = ""
            infoProgress.visibility = View.GONE
            infoNext.visibility = View.GONE
        }
    }

    /** 加载 EPG 并生成网格（复用 Rust core 的流式 XMLTV 解析 + 网格算法）。 */
    private fun loadEpg() {
        val customSources = TiviMatePrefs.epgSources(this).filter { it.enabled }
        val effectiveUrls = if (customSources.isNotEmpty()) customSources.map { it.url } else epgUrls
        if (effectiveUrls.isEmpty()) {
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
                    effectiveUrls.distinct().forEachIndexed { idx, url ->
                        val xml = if (idx == 0 && epgCache != null) epgCache!! else
                            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                                RustBridge.readBody(resp.body!!)
                            }
                        if (idx == 0) epgCache = xml
                        val priority = customSources.getOrNull(idx)?.priority ?: (10 + idx * 10)
                        sources.put(org.json.JSONObject().apply {
                            put("id", "epg$idx")
                            put("priority", priority)
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
                    Log.i(TAG, "EPG_MERGE sources=${effectiveUrls.size} custom=${customSources.isNotEmpty()} stats=$stats")
                    EpgGridData.fromJson(json)
                } catch (e: Exception) {
                    Log.e(TAG, "EPG load failed", e)
                    null
                }
            }
            if (result != null) {
                epgMatched = result.matched
                epgRequested = result.requested
                currentEpgData = result
                // TiviMate 风格：把"当前节目 + 进度"注入频道列表行内显示 + 信息卡
                val nowData = result.rows.associate { row ->
                    val cur = row.programmes.firstOrNull { p -> p.start <= result.now && p.stop > result.now }
                    val pct = if (cur != null && cur.stop > cur.start)
                        ((result.now - cur.start) * 100 / (cur.stop - cur.start)).toInt().coerceIn(0, 100) else 0
                    val next = if (cur != null) row.programmes.firstOrNull { it.start >= cur.stop } else null
                    row.tvgId to ProgramNow(cur?.title ?: "", pct, cur?.start ?: 0, cur?.stop ?: 0, next?.title ?: "")
                }
                nowPlaying = nowData
                val nowPairs = nowData.mapValues { it.value.title to it.value.pct }
                adapter.setNowPlaying(nowPairs)
                posterAdapter.setNowPlaying(nowPairs)
                // 刷新当前频道信息卡（若正在显示）
                allChannels.getOrNull(currentPos)?.let { refreshInfoCard(it) }
                updateProgramColumn()   // 竖列节目单：显示当前频道的节目
                if (showingEpg) status.text = "EPG: ${result.matched}/${result.requested} 频道匹配"
                Log.i(TAG, "EPG_RESULT matched=${result.matched} requested=${result.requested} guide=${result.guideChannels} rows=${result.rows.size} programmes=${result.rows.sumOf { it.programmes.size }}")
            } else {
                if (showingEpg) status.text = "节目单加载失败（可重试）"
                Log.w(TAG, "EPG_RESULT failed")
            }
        }
    }

    /** 启动时在后台提前解析缓存，与 playerInit 并行，省去串行等待 */
    private var prefetched: Deferred<Pair<PlaylistResult, String>?>? = null
    private fun startCachePrefetch(index: Int) {
        prefetched = lifecycleScope.async(Dispatchers.Default) {
            try {
                val cached = PlaylistCache.load(this@MainActivity, index) ?: return@async null
                val result = RustBridge.parsePlaylist(cached, (index + 1).toLong())
                if (result.channels.isEmpty()) null else result to cached
            } catch (e: Exception) { null }
        }
    }

    /** 从本地缓存同步加载频道（冷启动秒开，零网络等待） */
    private fun loadFromCache(index: Int): Boolean {
        val result = try {
            kotlinx.coroutines.runBlocking { prefetched?.await() }
        } catch (e: Exception) { null }
        if (result != null) {
            applyPlaylist(result.first, result.second, true)
            return true
        }
        // 回退：同步解析（prefetch 未命中时）
        val cached = PlaylistCache.load(this, index) ?: return false
        val parsed = try {
            RustBridge.parsePlaylist(cached, (index + 1).toLong())
        } catch (e: Exception) { return false }
        if (parsed.channels.isEmpty()) return false
        applyPlaylist(parsed, cached, true)
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
        adapter.setLists(FavoritesStore.favorites(this@MainActivity), FavoritesStore.recents(this@MainActivity), FavoritesStore.mostWatched(this@MainActivity))
        adapter.refresh()
        posterAdapter.submit(allChannels, FavoritesStore.favorites(this@MainActivity))
        buildGroupChips(result.groups)
        status.text = "已加载 ${allChannels.size} 个频道 · ${result.groups.size} 个分组${if (fromCache) "（缓存）" else ""}"
        if (!fromCache) PlaylistCache.save(this@MainActivity, currentSource, rawContent)
        // 首次启动无缓存时，网络列表到位后也自动开播
        if (!fromCache && !autoPlayed) autoPlayLastChannel()
        Log.i(TAG, "PLAYLIST_APPLIED channels=${allChannels.size} fromCache=$fromCache COLD_START_MS=${System.currentTimeMillis() - coldStartTime}")
    }

    private fun loadSource(index: Int) {
        if (index < 0 || index >= sources.size) return
        val src = sources[index]
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
                            val actual = PlaylistUrlResolver.extractPlaylistUrl(body)
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

    /** 频道长按操作菜单（TiviMate 风格） */
    private fun showChannelMenu(ch: Channel) {
        val fav = if (FavoritesStore.isFavorite(this, ch.id)) "☆ 取消收藏" else "★ 收藏"
        val lock = if (TiviMatePrefs.isLocked(this, ch.id)) "🔓 解锁频道" else "🔒 锁定频道"
        val hide = if (TiviMatePrefs.isHidden(this, ch.id)) "👁 显示频道" else "🚫 隐藏频道"
        val actions = arrayOf(fav, lock, hide, "⏰ 设置提醒", "✏️ 移动到分组…", "🖥 画面比例 / 解码")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        FavoritesStore.toggleFavorite(this, ch.id)
                        adapter.setLists(FavoritesStore.favorites(this), FavoritesStore.recents(this), FavoritesStore.mostWatched(this))
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
                        val start = System.currentTimeMillis() / 1000 + 300 // 示例：5 分钟后
                        val tvg = ch.tvgId ?: ch.providerId
                        TiviMatePrefs.addReminder(this, TiviMatePrefs.Reminder(tvg, ch.name, start))
                        ReminderScheduler.schedule(this, tvg, ch.name, start)
                        Toast.makeText(this, "已设置提醒（5 分钟后）", Toast.LENGTH_SHORT).show()
                    }
                    4 -> showGroupPicker(ch)
                    5 -> showPlaybackOptions(ch)
                }
            }
            .setNegativeButton("取消", null).show()
    }

    /** 画面比例 + 解码器选项（TiviMate 风格，单频道即时生效） */
    private fun showPlaybackOptions(ch: Channel) {
        val aspect = TiviMatePrefs.aspectMode(this)
        val fb = TiviMatePrefs.decoderFallback(this)
        val aspectLabel = when (aspect) { 1 -> "铺满裁切"; 2 -> "完整显示(黑边)"; else -> "自适应" }
        val opts = arrayOf(
            "画面比例: $aspectLabel（点击切换）",
            "硬解失败回退软解: ${if (fb) "开" else "关"}（点击切换）",
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("${ch.name} · 播放选项")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> {
                        val next = (aspect + 1) % 3
                        TiviMatePrefs.setAspectMode(this, next)
                        PlaybackService.applyAspectMode(next)
                        Toast.makeText(this, "画面比例: ${when (next) { 1 -> "铺满裁切"; 2 -> "完整显示"; else -> "自适应" }}", Toast.LENGTH_SHORT).show()
                        showPlaybackOptions(ch)
                    }
                    1 -> {
                        TiviMatePrefs.setDecoderFallback(this, !fb)
                        Toast.makeText(this, "软解回退: ${if (!fb) "开（下次播放生效）" else "关"}", Toast.LENGTH_SHORT).show()
                        showPlaybackOptions(ch)
                    }
                }
            }
            .setNegativeButton("完成", null).show()
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
        adapter.setLists(FavoritesStore.favorites(this), FavoritesStore.recents(this), FavoritesStore.mostWatched(this))
        adapter.refresh()
    }

    private fun buildGroupChips(groups: List<String>) {
        groupRow.removeAllViews()
        val hidden = TiviMatePrefs.hiddenGroups(this)
        val names = TiviMatePrefs.groupNames(this)
        // 过滤隐藏分组；重命名仅影响显示，过滤仍用原始组名
        val visibleGroups = groups.filterNot { hidden.contains(it) }
        // 虚拟分组：收藏 / 最近观看 / 全部（TiviMate 风格）
        val chips = listOf("★收藏" to "__favorites__", "最近观看" to "__recents__", "常看" to "__mostwatched__", "全部" to null) +
            visibleGroups.take(50).map { (names[it] ?: it) to it }
        chips.forEach { (label, key) ->
            Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 15f
                setTextColor(android.graphics.Color.parseColor("#A1A1A6"))
                setBackgroundResource(R.drawable.group_chip)
                // 关键：清除 AppCompat 默认 backgroundTint（否则焦点变黄色，很丑）
                backgroundTintList = null
                minHeight = 0
                minimumWidth = 0
                setPadding(20, 14, 20, 14)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, 0, 8) }
                setOnClickListener {
                    currentGroup = key
                    adapter.setLists(FavoritesStore.favorites(this@MainActivity), FavoritesStore.recents(this@MainActivity), FavoritesStore.mostWatched(this@MainActivity))
                    adapter.filter(searchEdit.text.toString(), key)
                    val count = if (key == null) allChannels.size else adapter.itemCount
                    status.text = "$label: $count 个频道"
                    // 选中态高亮（TiviMate 分组 tab）
                    for (i in 0 until groupRow.childCount) {
                        val c = groupRow.getChildAt(i)
                        if (c is Button) {
                            val selected = c.text == label
                            c.setBackgroundResource(if (selected) R.drawable.group_chip_active else R.drawable.group_chip)
                            c.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                            c.setTextColor(android.graphics.Color.parseColor(if (selected) "#FFFFFF" else "#A1A1A6"))
                        }
                    }
                }
                // 长按真实分组 → 隐藏 / 重命名（TiviMate 功能）
                if (key != null && key != "__favorites__" && key != "__recents__" && key != "__mostwatched__") {
                    setOnLongClickListener {
                        showGroupOptions(key, label)
                        true
                    }
                }
                groupRow.addView(this)
            }
        }
        adapter.setLists(FavoritesStore.favorites(this), FavoritesStore.recents(this), FavoritesStore.mostWatched(this))
    }

    /** 分组长按菜单：隐藏分组 / 重命名 */
    private fun showGroupOptions(group: String, currentLabel: String) {
        val isHidden = TiviMatePrefs.hiddenGroups(this).contains(group)
        val actions = arrayOf(
            if (isHidden) "显示此分组" else "隐藏此分组",
            "重命名分组",
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("分组: $currentLabel")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        TiviMatePrefs.toggleHiddenGroup(this, group)
                        rebuildGroups()
                    }
                    1 -> {
                        val input = EditText(this).apply { setText(currentLabel) }
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("重命名分组")
                            .setView(input)
                            .setPositiveButton("确定") { _, _ ->
                                TiviMatePrefs.renameGroup(this, group, input.text.toString().trim())
                                rebuildGroups()
                            }
                            .setNegativeButton("取消", null).show()
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    /** 用当前所有分组重建 chips（从 allChannels 提取） */
    private fun rebuildGroups() {
        val groups = allChannels.map { it.group }.distinct().sorted()
        buildGroupChips(groups)
        status.text = "分组已更新"
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
        // 记录上一台（切到不同频道时）
        allChannels.getOrNull(currentPos)?.let { prev ->
            if (prev.id != ch.id) previousChannelId = prev.id
        }
        currentPos = allChannels.indexOfFirst { it.id == ch.id }.let { if (it >= 0) it else currentPos }
        urlIndex = 0
        adapter.setPlaying(ch.id)   // 进度条只显示在当前播放频道
        FavoritesStore.markWatched(this, ch.id)
        FavoritesStore.recordPlay(this, ch.id)   // R6 播放统计（本地）
        adapter.setLists(FavoritesStore.favorites(this), FavoritesStore.recents(this), FavoritesStore.mostWatched(this))
        adapter.refresh()
        Log.i(TAG, "WATCHED ${ch.id} ${ch.name} recents=${FavoritesStore.recents(this).size}")
        // 记住上次频道（秒播：下次启动直接续播）
        TiviMatePrefs.saveLastChannel(this, ch.id, currentSource)
        playCurrentUrl(ch)
        // 换台时短暂浮出信息卡（TiviMate 风格）
        showInfoCard(ch)
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
            // 外部播放器兜底（用户开启后，线路全失败时尝试外跳）
            if (TiviMatePrefs.playbackPrefs(this).externalPlayer) openExternalPlayer(ch)
        }
    }

    /** 保存当前 VOD 播放进度（供下次续播） */
    private fun saveCurrentResume() {
        val ch = allChannels.getOrNull(currentPos) ?: return
        val url = ch.url.substringBefore("|")
        val isVod = !ch.radio && !url.contains("/live/") &&
            (url.contains(".mp4") || url.contains(".mkv") || url.contains("/movie/") || url.contains("/series/"))
        if (!isVod) return
        val pos = player.currentPosition
        val dur = player.duration
        if (pos > 0 && dur > 0) {
            TiviMatePrefs.saveResume(this, "vod:ch:${ch.id}", pos, dur)
            Log.i(TAG, "SAVE_RESUME ${ch.name} ${pos}/${dur}")
        }
    }

    /** 外部播放器兜底：用系统已安装的播放器打开流 */
    private fun openExternalPlayer(ch: Channel) {
        try {
            val url = ch.url.substringBefore("|")
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(url), "application/vnd.apple.mpegurl")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ua = PlaylistUrlResolver.resolveUserAgent(ch.userAgent)
            intent.putExtra("User-Agent", ua) // VLC / 部分播放器支持
            startActivity(intent)
            Toast.makeText(this, "已交给外部播放器", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "EXTERNAL_PLAYER ${ch.name} ua=$ua")
        } catch (e: Exception) {
            Toast.makeText(this, "没有可用的外部播放器", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "external player not available", e)
        }
    }

    private fun playCurrentUrl(ch: Channel) {
        val all = listOf(ch.url) + ch.altUrls
        var url = all.getOrNull(urlIndex) ?: ch.url
        // 处理 Kodi 风格的 |User-Agent=VLC 后缀
        val parts = url.split("|", limit = 2)
        url = parts[0]
        val rawUa = ch.userAgent ?: parts.getOrNull(1)?.removePrefix("User-Agent=")
        val ua = PlaylistUrlResolver.resolveUserAgent(rawUa)
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
        // 断点续播：只对 VOD/剧集（非直播）恢复位置；直播永远从边缘看
        // 播放列表里的 URL 带 sign/auth 时效参数，VOD 才可续播；直播/广播不续
        val isVod = !ch.radio && !url.contains("/live/") &&
            (url.contains(".mp4") || url.contains(".mkv") || url.contains("/movie/") || url.contains("/series/"))
        val resumeKey = if (isVod) "vod:ch:${ch.id}" else null
        val resumePos = resumeKey?.let { TiviMatePrefs.getResume(this, it).first } ?: 0L

        zapStartTime = System.currentTimeMillis()
        player.setMediaItem(mediaItem)
        player.prepare()
        // 一次性 seek 到上次位置（等 READY 后再 seek）
        if (resumePos > 0) {
            val resumeListener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        player.seekTo(resumePos)
                        player.removeListener(this)
                        status.text = "▶ ${ch.name} · 已从 ${resumePos / 1000}s 继续"
                        Log.i(TAG, "RESUME ${ch.name} -> ${resumePos}ms")
                    }
                }
            }
            player.addListener(resumeListener)
        }
        player.playWhenReady = true
        status.text = "▶ ${ch.name} (${if (rawUa != null) rawUa else "默认"} UA)"
        playerView.controllerShowTimeoutMs = 3000
    }

    /**
     * 播放列表里用的是短名（如 `http-user-agent="VLC"` / `|User-Agent=VLC`），
     * 必须映射成完整的 UA 字符串 —— 实测老白的 CCTV1/CCTV2 源用 TiviMate UA 返回 403，
     * 只有 VLC UA 才能播放。
     */
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
        val ua = PlaylistUrlResolver.resolveUserAgent(rawUa)
        PlaybackService.setStreamUserAgent(ua)
        val mediaItem = MediaItem.Builder().setUri(url).build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
        status.text = "▶ $label"
    }

    /** 播放器的控制条是否可见（用两种 listener 接口都兼容的方式） */
    private var controllerVisible = false
    private var backExitArmed = false

    /** 右键短按：切回上一个频道（TiviMate 核心操作） */
    private fun switchToPrevious() {
        if (previousChannelId < 0) {
            Toast.makeText(this, "没有上一个频道", Toast.LENGTH_SHORT).show()
            return
        }
        val idx = allChannels.indexOfFirst { it.id == previousChannelId }
        if (idx >= 0) {
            zapTo(idx)
            Toast.makeText(this, "返回上一个频道", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !uiIsVisible()) {
            rightLongPressRunnable?.let { uiHandler.removeCallbacks(it) }
            if (!rightLongPressFired) switchToPrevious()
            rightLongPressFired = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 首次引导：任意键关闭引导；OK 键显示频道信息横幅
        if (firstRunGuide?.visibility == View.VISIBLE) {
            dismissGuide()
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                allChannels.getOrNull(currentPos)?.let { showInfoCard(it) }
            }
            return true
        }
        when (keyCode) {
            // UX-1: OK = 仅显示/隐藏换台信息横幅（不打开大面板）
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (uiIsVisible()) {
                    // 抽屉打开时，OK 交给列表/EPG 处理（选台/选节目）
                    val focused = currentFocus
                    val inList = focused === recycler || focused === posterGrid ||
                        (focused != null && (focused.parent === recycler || focused.parent === posterGrid))
                    if (!showingEpg && inList) {
                        allChannels.getOrNull(currentPos)?.let { play(it) }
                    }
                    return true
                }
                allChannels.getOrNull(currentPos)?.let {
                    if (infoCard.visibility == View.VISIBLE) infoCard.visibility = View.GONE
                    else showInfoCard(it)
                }
                return true
            }
            // 返回：关闭抽屉 → 关闭信息横幅 → (二次)退出
            KeyEvent.KEYCODE_BACK -> {
                if (drawerState > 0) { applyDrawerState(drawerState - 1); return true }
                if (infoCard.visibility == View.VISIBLE) { infoCard.visibility = View.GONE; return true }
                if (backExitArmed) { finish(); return true }
                backExitArmed = true
                Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()
                uiHandler.postDelayed({ backExitArmed = false }, 2000)
                return true
            }
            // 左键层级递进：全屏→频道+节目单→分组+频道→全屏（真机 TiviMate 逻辑）
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                applyDrawerState((drawerState + 1) % 3)
                return true
            }
            // 右键短按 = 上一台（TiviMate 核心）；长按 = 信息卡
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!uiIsVisible()) {
                    rightLongPressFired = false
                    rightLongPressRunnable?.let { uiHandler.removeCallbacks(it) }
                    val lp = Runnable {
                        rightLongPressFired = true
                        allChannels.getOrNull(currentPos)?.let { showInfoCard(it) }
                    }
                    rightLongPressRunnable = lp
                    uiHandler.postDelayed(lp, 500)
                    return true
                }
            }
            // 上/下：换台
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                if (!uiIsVisible()) { zap(1); return true }
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!uiIsVisible()) { zap(-1); return true }
            }
            // 菜单键：打开设置抽屉（同右键）
            KeyEvent.KEYCODE_MENU -> {
                startActivity(android.content.Intent(this, SettingsActivity::class.java))
                return true
            }
            // 信息键：显示信息横幅（同 OK）
            KeyEvent.KEYCODE_INFO -> {
                allChannels.getOrNull(currentPos)?.let {
                    if (infoCard.visibility == View.VISIBLE) infoCard.visibility = View.GONE
                    else showInfoCard(it)
                }
                return true
            }
        }
        // 数字键：频道号跳转（TiviMate 功能，1.5s 内连续输入组合成频道号）
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            val digit = keyCode - KeyEvent.KEYCODE_0
            numberJumpBuffer += digit.toString()
            status.text = "跳转到频道 $numberJumpBuffer…"
            numberJumpRunnable?.let { uiHandler.removeCallbacks(it) }
            val jump = Runnable {
                val idx = numberJumpBuffer.toIntOrNull()?.let { it - 1 } // 频道号从 1 开始
                if (idx != null && idx in allChannels.indices) {
                    zapTo(idx)
                    status.text = "▶ ${allChannels[idx].name}"
                } else {
                    status.text = "没有频道 $numberJumpBuffer"
                }
                numberJumpBuffer = ""
            }
            numberJumpRunnable = jump
            uiHandler.postDelayed(jump, 1500)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 直接跳到指定索引的频道（数字跳转用） */
    private fun zapTo(index: Int) {
        currentPos = index
        syncSelection(index)
        updateProgramColumn()
        recycler.scrollToPosition(index)
        if (posterMode) posterGrid.scrollToPosition(index)
        play(allChannels[index])
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerState > 0) { applyDrawerState(drawerState - 1); return }
        super.onBackPressed()
    }

    private fun zap(delta: Int) {
        if (allChannels.isEmpty()) return
        val next = (currentPos + delta).coerceIn(0, allChannels.size - 1)
        currentPos = next
        syncSelection(next)
        updateProgramColumn()
        recycler.scrollToPosition(next)
        if (posterMode) posterGrid.scrollToPosition(next)
        play(allChannels[next])
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // R2：设置页请求播放诊断（CLEAR_TOP 回到 MainActivity）
        if (intent?.getBooleanExtra("show_diagnostics", false) == true) {
            intent.removeExtra("show_diagnostics")
            showDiagnostics()
        }
        // 手机推源返回后：若自定义源地址变化，立即切换过去
        val current = readPushedSource()
        if (current?.url != pushedSource?.url) {
            rebuildSources()
            (sourceSpinner.adapter as? ArrayAdapter<String>)?.let { ad ->
                ad.clear(); ad.addAll(sources.map { it.name }); ad.notifyDataSetChanged()
            }
            sourceSpinner.setSelection(0, false)
            currentSource = 0
            if (current != null) loadSource(0)
        }
    }

    override fun onStop() {
        super.onStop()
        // 退到后台不停止播放（服务继续），但释放 UI 层
    }

    override fun onDestroy() {
        saveCurrentResume()
        previewPlayer?.release(pipPlayerView)
        previewPlayer = null
        playerView.player = null
        super.onDestroy()
    }
}
