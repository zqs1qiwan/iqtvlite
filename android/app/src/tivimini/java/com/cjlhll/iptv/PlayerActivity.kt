package com.cjlhll.iptv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.tv.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme
import androidx.compose.ui.graphics.Color as ComposeColor
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private enum class PlaylistUpdateMode {
    /** 加载缓存，仅更新频道列表，不切换播放 */
    Initial,
    /** 后台静默刷新，仅 URL 失效时无感切换 */
    Background,
    /** 播放错误恢复，切换时显示 loading */
    Recovery,
}

class PlayerActivity : ComponentActivity() {
    private var onNumberJump: ((Int) -> Boolean)? = null
    private var digits by mutableStateOf("")
    private var numberJob: Job? = null
    private fun submitNumber() {
        numberJob?.cancel()
        val number = digits.toIntOrNull()
        if (number == null || onNumberJump?.invoke(number) != true) {
            android.widget.Toast.makeText(this, "没有频道 $digits", android.widget.Toast.LENGTH_SHORT).show()
        }
        digits = ""
    }
    override fun onStop() {
        numberJob?.cancel(); digits = ""
        super.onStop()
    }
    private var onChannelStep: ((Int) -> Boolean)? = null
    private var setDrawerOpen: ((Boolean) -> Unit)? = null
    private var isDrawerOpen: Boolean = false
    private val sidebarController = SidebarController()
    private var onSidebarPlay: ((Int, Int) -> Unit)? = null
    private var onSidebarCatchup: ((CatchupPlayRequest) -> Unit)? = null
    private var nativeSidebar: NativeSidebarView? = null
    private var drawerActiveColumn: DrawerColumn? = null
    private var focusToDrawerChannels: (() -> Unit)? = null
    private var setSettingsDrawerOpen: ((Boolean) -> Unit)? = null
    private var isSettingsDrawerOpen: Boolean = false
    private var setQualityDialogOpen: ((Boolean) -> Unit)? = null
    private var isQualityDialogOpen: Boolean = false
    private var setInfoBannerOpen: ((Boolean) -> Unit)? = null
    private var isInfoBannerOpen: Boolean = false
    private var isCatchupPlayback: Boolean = false
    private var onCatchupSeek: ((Long) -> Boolean)? = null
    private var onReturnToLive: (() -> Boolean)? = null
    private var lastBackPressTime: Long = 0
    private var lastBackPressTimeCatchup: Long = 0

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoUrl = intent.getStringExtra("VIDEO_URL") ?: ""
        val videoTitle = intent.getStringExtra("VIDEO_TITLE") ?: "Live"
        val playlistFileName = intent.getStringExtra("PLAYLIST_FILE_NAME") ?: Prefs.getPlaylistFileName(this)

        // Zero-copy rendering base (t38): a native FrameLayout hosts a PlayerView (SurfaceView,
        // zero-copy) as the bottom layer and a transparent ComposeView on top for ALL UI overlays.
        // The video stays on its own compositor layer, so UI jank no longer drags the video and there
        // is no per-frame GPU copy (vs TextureView). GUARD (learned from the t30 grey screen): the
        // SurfaceView must remain a DIRECT child of the content FrameLayout with the ComposeView
        // transparent above it, and no opaque layer may cover its bounds.
        val rootLayout = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val videoView = androidx.media3.ui.PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            keepScreenOn = true
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        rootLayout.addView(videoView, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        // t62: native View sidebar layer, between the zero-copy SurfaceView and the transparent
        // Compose overlay. Renders from SidebarController (single source of truth).
        val sidebarView = NativeSidebarView(this, sidebarController, { groupIndex, variantIndex ->
            onSidebarPlay?.invoke(groupIndex, variantIndex)
        }, { request -> onSidebarCatchup?.invoke(request) }).apply { visibility = android.view.View.GONE }
        nativeSidebar = sidebarView
        rootLayout.addView(sidebarView, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        val composeOverlay = androidx.compose.ui.platform.ComposeView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        rootLayout.addView(composeOverlay, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(rootLayout)
        lifecycleScope.launch {
            sidebarController.state.collect { st ->
                isDrawerOpen = st.drawerOpen
                val sidebar = nativeSidebar ?: return@collect
                if (st.drawerOpen) {
                    val wasHidden = sidebar.visibility != android.view.View.VISIBLE
                    sidebar.visibility = android.view.View.VISIBLE
                    sidebar.render(st)
                    if (wasHidden) sidebar.requestInitialFocus()
                } else {
                    sidebar.visibility = android.view.View.GONE
                }
            }
        }

        composeOverlay.setContent {
            val colorScheme = darkColorScheme(
                // IQTV Lite brand palette (docs/BRAND_GUIDELINES.md).
                primary = ComposeColor(0xFF4EA8FF),
                onPrimary = ComposeColor.Black,
                secondary = ComposeColor(0xFFA9B4C7),
                onSecondary = ComposeColor.Black,
                tertiary = ComposeColor(0xFFF5A524),
                onTertiary = ComposeColor.Black,
                background = ComposeColor(0xFF0E1116),
                onBackground = ComposeColor(0xFFF2F5FA),
                surface = ComposeColor(0xFF161B22),
                onSurface = ComposeColor(0xFFF2F5FA)
            )

            MaterialTheme(colorScheme = colorScheme) {
                // Transparent root: this ComposeView must never paint an opaque full-screen layer,
                // otherwise it would cover the SurfaceView behind it (t30 grey-screen regression).
                Box(Modifier.fillMaxSize()) {
                    VideoPlayerScreen(
                        playerView = videoView,
                        url = videoUrl,
                        title = videoTitle,
                        playlistFileName = playlistFileName,
                        sidebarController = sidebarController,
                        setOnChannelStep = { onChannelStep = it },
                        setOnNumberJump = { onNumberJump = it },
                        setOnSidebarPlay = { onSidebarPlay = it },
                        setOnSidebarCatchup = { onSidebarCatchup = it },
                        setDrawerOpenController = { setDrawerOpen = it },
                        onDrawerOpenChanged = { isDrawerOpen = it },
                        onDrawerActiveColumnChanged = { drawerActiveColumn = it },
                        setFocusToDrawerChannelsController = { focusToDrawerChannels = it },
                        setSettingsDrawerOpenController = { setSettingsDrawerOpen = it },
                        onSettingsDrawerOpenChanged = { isSettingsDrawerOpen = it },
                        setQualityDialogOpenController = { setQualityDialogOpen = it },
                        onQualityDialogOpenChanged = { isQualityDialogOpen = it },
                        setInfoBannerOpenController = { setInfoBannerOpen = it },
                        onInfoBannerOpenChanged = { isInfoBannerOpen = it },
                        setOnCatchupSeek = { onCatchupSeek = it },
                        setOnReturnToLive = { onReturnToLive = it },
                        onCatchupPlaybackChanged = { isCatchupPlayback = it }
                    ) {
                        finish()
                    }
                    if (digits.isNotEmpty()) Text(digits, modifier = Modifier.align(Alignment.TopEnd).padding(48.dp).background(ComposeColor.Black.copy(alpha=.8f)).padding(20.dp), style = MaterialTheme.typography.headlineLarge)
                }
            }
        }
    }

    private fun calculateSeekStep(repeatCount: Int): Long {
        return when {
            repeatCount == 0 -> 10_000L
            repeatCount < 15 -> 30_000L
            repeatCount < 40 -> 60_000L
            else -> 300_000L
        }
    }

    // TV Activity must route unhandled DPAD before Compose; only the inherited core bridge is restricted.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && !isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen) {
                if (event.repeatCount == 0) {
                    if (digits.length < 6) digits += (event.keyCode-KeyEvent.KEYCODE_0).toString()
                    numberJob?.cancel()
                    numberJob = lifecycleScope.launch { delay(1200); submitNumber() }
                }
                return true
            }
            if (digits.isNotEmpty()) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) { numberJob?.cancel(); digits = ""; return true }
                if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) { submitNumber(); return true }
                numberJob?.cancel(); digits = ""
            }
            // t66: layered columns (channel+program main view; groups/date are overlay columns).
            if (sidebarController.value.drawerOpen) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> when (sidebarController.value.activeColumn) {
                        DrawerColumn.Channels -> { sidebarController.setShowGroups(true); nativeSidebar?.clearPosition(DrawerColumn.Groups); nativeSidebar?.focusGroups(); return true }
                        DrawerColumn.Groups -> { sidebarController.setShowGroups(false); nativeSidebar?.clearPosition(DrawerColumn.Groups); nativeSidebar?.focusChannels(); return true }
                        DrawerColumn.Programs -> { nativeSidebar?.focusChannels(); return true }
                        DrawerColumn.Dates -> { sidebarController.setShowDates(false); nativeSidebar?.clearPosition(DrawerColumn.Dates); nativeSidebar?.focusPrograms(); return true }
                        else -> {}
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> when (sidebarController.value.activeColumn) {
                        // SIDEBAR3-01: hide the groups overlay BEFORE focusing channels, otherwise the
                        // 0..151dp overlay keeps covering the focused channel row.
                        DrawerColumn.Groups -> { sidebarController.setShowGroups(false); nativeSidebar?.clearPosition(DrawerColumn.Groups); nativeSidebar?.focusChannels(); return true }
                        DrawerColumn.Channels -> { nativeSidebar?.focusPrograms(); return true }
                        DrawerColumn.Programs -> { sidebarController.setShowDates(true); nativeSidebar?.clearPosition(DrawerColumn.Dates); nativeSidebar?.focusDates(); return true }
                        else -> {}
                    }
                }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen) {
                        setInfoBannerOpen?.invoke(true)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen) {
                        if (isCatchupPlayback) {
                            val step = calculateSeekStep(event.repeatCount)
                            if (onCatchupSeek?.invoke(-step) == true) return true
                        } else {
                            sidebarController.openDrawer()
                            return true
                        }
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen) {
                        if (isCatchupPlayback) {
                            val step = calculateSeekStep(event.repeatCount)
                            if (onCatchupSeek?.invoke(step) == true) return true
                        } else {
                            setSettingsDrawerOpen?.invoke(true)
                            return true
                        }
                    }
                }

                KeyEvent.KEYCODE_MENU -> {
                    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen) {
                        setSettingsDrawerOpen?.invoke(true)
                        return true
                    }
                }

                KeyEvent.KEYCODE_BACK -> {
                    if (isQualityDialogOpen) {
                        setQualityDialogOpen?.invoke(false)
                        return true
                    }
                    if (sidebarController.value.drawerOpen) {
                        // TIV: one Back closes the whole native drawer from any column.
                        sidebarController.closeDrawer()
                        return true
                    }
                    if (isSettingsDrawerOpen) {
                        setSettingsDrawerOpen?.invoke(false)
                        return true
                    }
                    if (isInfoBannerOpen) {
                        setInfoBannerOpen?.invoke(false)
                        return true
                    }

                    if (isCatchupPlayback) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastBackPressTimeCatchup > 2000) {
                            lastBackPressTimeCatchup = currentTime
                            android.widget.Toast.makeText(this, "再按一次返回直播", android.widget.Toast.LENGTH_SHORT).show()
                            return true
                        }
                        if (onReturnToLive?.invoke() == true) {
                            lastBackPressTimeCatchup = 0
                            return true
                        }
                        return true
                    }

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastBackPressTime > 2000) {
                        lastBackPressTime = currentTime
                        android.widget.Toast.makeText(this, "再按一次退出app", android.widget.Toast.LENGTH_SHORT).show()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen) {
                        if (isCatchupPlayback) {
                            setInfoBannerOpen?.invoke(true)
                            return true
                        }
                        if (onChannelStep?.invoke(-1) == true) return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen) {
                        if (isCatchupPlayback) {
                            setInfoBannerOpen?.invoke(true)
                            return true
                        }
                        if (onChannelStep?.invoke(1) == true) return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)
fun VideoPlayerScreen(
    playerView: PlayerView,
    url: String,
    title: String,
    playlistFileName: String?,
    sidebarController: SidebarController,
    setOnChannelStep: (((Int) -> Boolean)?) -> Unit,
    setOnNumberJump: (((Int) -> Boolean)?) -> Unit,
    setOnSidebarPlay: (((Int, Int) -> Unit)?) -> Unit,
    setOnSidebarCatchup: (((CatchupPlayRequest) -> Unit)?) -> Unit,
    setDrawerOpenController: (((Boolean) -> Unit)?) -> Unit,
    onDrawerOpenChanged: (Boolean) -> Unit,
    onDrawerActiveColumnChanged: (DrawerColumn?) -> Unit,
    setFocusToDrawerChannelsController: (() -> Unit) -> Unit,
    setSettingsDrawerOpenController: (((Boolean) -> Unit)?) -> Unit,
    onSettingsDrawerOpenChanged: (Boolean) -> Unit,
    setQualityDialogOpenController: (((Boolean) -> Unit)?) -> Unit,
    onQualityDialogOpenChanged: (Boolean) -> Unit,
    setInfoBannerOpenController: (((Boolean) -> Unit)?) -> Unit,
    onInfoBannerOpenChanged: (Boolean) -> Unit,
    setOnCatchupSeek: (((Long) -> Boolean)?) -> Unit,
    setOnReturnToLive: ((() -> Boolean)?) -> Unit,
    onCatchupPlaybackChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    val sidebarState by sidebarController.state.collectAsState()
    val drawerOpen = sidebarState.drawerOpen
    val drawerActiveColumn = sidebarState.activeColumn
    var settingsDrawerOpen by remember { mutableStateOf(false) }
    var qualityDialogOpen by remember { mutableStateOf(false) }
    var qualityDialogGroupIndex by remember { mutableIntStateOf(-1) }
    var infoBannerOpen by remember { mutableStateOf(false) }
    val channelSwitch = rememberChannelSwitchController()
    val playbackErrorEvents = remember { kotlinx.coroutines.channels.Channel<PlaybackFailure>(kotlinx.coroutines.channels.Channel.BUFFERED) }
    val playbackIntent = remember { PlaybackIntent() }
    var catchupRetryCount by remember { mutableIntStateOf(0) }
    var videoFormat by remember { mutableStateOf<androidx.media3.common.Format?>(null) }
    val scope = rememberCoroutineScope()
    var measuredLatencyByUrl by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    var probingUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sourceSelectGeneration by remember { mutableIntStateOf(0) }
    var sourceSelectJob by remember { mutableStateOf<Job?>(null) }

    var channelGroups by remember { mutableStateOf<List<ChannelGroup>>(emptyList()) }
    var currentGroupIndex by remember { mutableIntStateOf(0) }
    var currentVariantIndex by remember { mutableIntStateOf(0) }
    val selectedGroup = sidebarState.selectedGroup
    // M1: mirror the live channel list/selection into the Activity-level controller so the M2 native
    // sidebar renders from a single source of truth. The existing Compose drawer still reads the
    // local Compose state during M1 (no behaviour change); M2 deletes those locals.
    LaunchedEffect(channelGroups) { sidebarController.setChannelGroups(channelGroups) }
    LaunchedEffect(currentGroupIndex, currentVariantIndex) {
        sidebarController.setSelection(currentGroupIndex, currentVariantIndex)
    }
    var epgData by remember { mutableStateOf<EpgData?>(null) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var nowProgramByUrl by remember { mutableStateOf<Map<String, NowProgramUi>>(emptyMap()) }
    // t62: push EPG into the Activity-level controller so the native sidebar renders it directly.
    LaunchedEffect(epgData) { sidebarController.setEpgData(epgData) }
    var catchupRequest by remember { mutableStateOf<CatchupPlayRequest?>(null) }
    var catchupPositionMs by remember { mutableStateOf(0L) }
    var isSeekOverlayVisible by remember { mutableStateOf(false) }
    var catchupSeekPingAt by remember { mutableStateOf(0L) }

    val drawerLogoWidthPx = remember(density) { with(density) { 64.dp.roundToPx() } }
    val drawerLogoHeightPx = remember(density) { with(density) { 40.dp.roundToPx() } }
    val bannerLogoWidthPx = remember(density) { with(density) { 80.dp.roundToPx() } }
    val bannerLogoHeightPx = remember(density) { with(density) { 50.dp.roundToPx() } }

    val currentLive = playbackIntent.liveOwner(channelGroups, currentGroupIndex, currentVariantIndex)
    val currentChannel = currentLive.channel

    fun selectedLiveNow(): Channel {
        return playbackIntent.liveOwner(channelGroups, currentGroupIndex, currentVariantIndex).channel
            ?: StreamPlayback.restore(context, url, title).let { restored -> restored.copy(
                id=(context as? Activity)?.intent?.getLongExtra("VIDEO_ID", restored.id) ?: restored.id,
                userAgent=(context as? Activity)?.intent?.getStringExtra("VIDEO_UA") ?: restored.userAgent,
                referrer=(context as? Activity)?.intent?.getStringExtra("VIDEO_REFERRER") ?: restored.referrer) }
    }
    fun liveSelection() = PlaylistSelection(channelGroups, currentGroupIndex, selectedLiveNow())
    fun prepareRequest(p: ExoPlayer, request: MediaPlaybackRequest) =
        StreamPlayback.prepare(p, context, request, playbackIntent) { playbackErrorEvents.trySend(it) }

    val allChannels = remember(channelGroups) {
        ChannelGrouper.allChannels(channelGroups)
    }

    val playingGroupIndex = currentLive.groupIndex
    val playingGroup = channelGroups.getOrNull(playingGroupIndex)
    val playingVariantIndex = currentLive.variantIndex.coerceAtLeast(0)
    val epgLoadTag = remember { "EPG" }
    val shouldRefreshEpg = remember { mutableStateOf(false) }

    var heavyWorkEnabled by remember { mutableStateOf(false) }
    var firstDrawerOpenSeen by remember { mutableStateOf(false) }

    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        delay(1500)
        heavyWorkEnabled = true
    }

    fun initializePlayer() {
        if (player != null) return

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                // Faster cold-start/zap than 1500/6000/500/1000, but within Media3 constraints:
                // minBufferMs(1000) >= bufferForPlayback(300) and >= bufferForPlaybackAfterRebuffer(1000).
                // The earlier 800/6000/300/1000 was INVALID (800 < afterRebuffer 1000) and crashed at
                // PlayerActivity start (USAB2-01 / CI10077). Never set minBufferMs below afterRebuffer.
                1_000,
                6_000,
                300,
                1_000
            )
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            // Removed experimental MediaCodec video pre-warming: it eagerly creates and warms a video
            // decoder at startup, which on this weak v7a box costs seconds of cold start and a CPU
            // spike. The normal first-frame path is faster overall here.

        val newPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .build()
            .apply {
                playWhenReady = true
                setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setWakeMode(C.WAKE_MODE_NETWORK)
            }

        val req = catchupRequest
        val request = if (req == null) MediaPlaybackRequest(selectedLiveNow())
            else PlaybackIntent.catchup(ChannelGrouper.allChannels(channelGroups), req, playbackIntent.request)
        if (request == null) { newPlayer.release(); playerView.player = null; return }
        catchupRetryCount = 0
        playerView.player = newPlayer
        prepareRequest(newPlayer, request)
        player = newPlayer
    }

    fun releasePlayer() {
        sourceSelectGeneration += 1
        sourceSelectJob?.cancel()
        playbackIntent.suspendPlayer()
        playerView.player = null
        player?.let { StreamPlayback.release(it) }
        player = null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                initializePlayer()
            } else if (event == Lifecycle.Event.ON_STOP) {
                releasePlayer()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releasePlayer()
        }
    }

    DisposableEffect(player) {
        val p = player
        if (p != null) {
            val activity = context as? Activity

            fun setScreenOn(keepOn: Boolean) {
                val window = activity?.window ?: return
                if (keepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val listener = object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    setScreenOn(isPlaying)
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    videoFormat = p.videoFormat
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    videoFormat = p.videoFormat
                }


            }

            p.addListener(listener)
            setScreenOn(p.isPlaying)
            onDispose {
                setScreenOn(false)
                p.removeListener(listener)
            }
        } else {
            onDispose { }
        }
    }

    val focusToChannels = {
        sidebarController.setActiveColumn(DrawerColumn.Channels)
    }

    BindChannelSwitchLoading(channelSwitch, player)

    DisposableEffect(Unit) {
        setDrawerOpenController { open -> sidebarController.setDrawerOpen(open) }
        setFocusToDrawerChannelsController(focusToChannels)
        setSettingsDrawerOpenController { settingsDrawerOpen = it }
        setQualityDialogOpenController { qualityDialogOpen = it }
        setInfoBannerOpenController { infoBannerOpen = it }
        onDispose {
            setDrawerOpenController(null)
            setSettingsDrawerOpenController(null)
            setQualityDialogOpenController(null)
            setInfoBannerOpenController(null)
        }
    }

    LaunchedEffect(drawerOpen) {
        onDrawerOpenChanged(drawerOpen)
    }

    LaunchedEffect(drawerActiveColumn) {
        onDrawerActiveColumnChanged(drawerActiveColumn)
    }

    LaunchedEffect(settingsDrawerOpen) {
        onSettingsDrawerOpenChanged(settingsDrawerOpen)
    }

    LaunchedEffect(qualityDialogOpen) {
        onQualityDialogOpenChanged(qualityDialogOpen)
        if (!qualityDialogOpen) {
            qualityDialogGroupIndex = -1
        }
    }

    LaunchedEffect(infoBannerOpen, currentGroupIndex, currentVariantIndex) {
        onInfoBannerOpenChanged(infoBannerOpen)
        if (infoBannerOpen) {
            delay(5000)
            infoBannerOpen = false
        }
    }

    LaunchedEffect(catchupRequest) {
        onCatchupPlaybackChanged(catchupRequest != null)
    }

    LaunchedEffect(catchupSeekPingAt) {
        if (catchupSeekPingAt > 0) {
            isSeekOverlayVisible = true
            delay(1500)
            isSeekOverlayVisible = false
        }
    }

    LaunchedEffect(catchupRequest, player) {
        val p = player
        if (catchupRequest == null || p == null) {
            catchupPositionMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            val req = catchupRequest ?: break
            val total = (req.endMillis - req.startMillis).coerceAtLeast(1L)
            catchupPositionMs = p.currentPosition.coerceIn(0L, total)
            delay(500)
        }
    }

    suspend fun parsePlaylist(content: String, source: String): List<ChannelGroup> = withContext(Dispatchers.Default) {
        ChannelGrouper.group(M3uParser.parse(content, source))
    }

    fun commitPlaylist(update: PlaylistApplication, mode: PlaylistUpdateMode) {
        val policy = playbackIntent.commitPlaylist(update, canPrepare=catchupRequest == null && player != null,
            initial=mode == PlaylistUpdateMode.Initial, recovery=mode == PlaylistUpdateMode.Recovery)
        SourceLatencyProber.updateChannels(ChannelGrouper.allChannels(update.groups))
        channelGroups = update.groups
        policy.groupIndex?.let { currentGroupIndex=it }
        policy.variantIndex?.let { currentVariantIndex=it }
        policy.prepare?.let { request ->
            if (mode == PlaylistUpdateMode.Recovery) channelSwitch.show()
            player?.let { prepareRequest(it, request) }
            Prefs.setLastChannel(context, request.channel.url, request.channel.title)
        }
        scope.launch {
            while (!heavyWorkEnabled) delay(200)
            if (channelGroups !== update.groups) return@launch
            // t42: bound the startup logo prefetch to the first 60 channels so a huge playlist can't
            // spike CPU at startup; the rest load lazily when scrolled into view.
            val logos = buildPrefetchLogoUrls(displayChannelsFromGroups(update.groups), currentGroupIndex).take(60)
            LogoLoader.prefetch(context, logos, drawerLogoWidthPx, drawerLogoHeightPx)
            delay(800)
            if (channelGroups === update.groups) LogoLoader.prefetch(context, logos, bannerLogoWidthPx, bannerLogoHeightPx)
        }
    }

    suspend fun applyPlaylistContent(ticket: Long, mode: PlaylistUpdateMode, load: suspend () -> String?, valid: () -> Boolean = { true }): Boolean {
        val source = Prefs.getLiveSource(context)
        return playbackIntent.updatePlaylist(ticket, { liveSelection() }, load,
            { parsePlaylist(it, source) }, mode != PlaylistUpdateMode.Initial,
            { Prefs.getLiveSource(context) == source && valid() }, { commitPlaylist(it, mode) })
    }

    LaunchedEffect(playlistFileName, url, title, player) {
        val liveSource = Prefs.getLiveSource(context)
        val fileName = playlistFileName?.takeIf { it.isNotBlank() }
            ?: liveSource.takeIf { it.isNotBlank() }?.let { PlaylistCache.fileNameForSource(it) }
        if (fileName != null) applyPlaylistContent(playbackIntent.ticket(), PlaylistUpdateMode.Initial,
            { withContext(Dispatchers.IO) { PlaylistCache.read(context, fileName) } })
    }

    LaunchedEffect(Unit) {
        BackgroundSourceUpdater.playlistUpdated.collectLatest { fileName ->
            if (fileName != Prefs.getPlaylistFileName(context)) return@collectLatest
            val ticket = playbackIntent.ticket()
            applyPlaylistContent(ticket, PlaylistUpdateMode.Background,
                { withContext(Dispatchers.IO) { PlaylistCache.read(context, fileName) } },
                { Prefs.getPlaylistFileName(context) == fileName })
        }
    }

    LaunchedEffect(Unit) {
        for (failure in playbackErrorEvents) {
            val failed = failure.playback
            val p = player ?: continue
            if (!playbackIntent.isCurrent(failed) || p.currentMediaItem?.mediaId != failed.mediaId) continue
            if (failed.request.isCatchup) {
                if (catchupRetryCount >= 3) continue
                catchupRetryCount += 1
                delay(800)
                if (player === p && playbackIntent.isCurrent(failed) && p.currentMediaItem?.mediaId == failed.mediaId) {
                    prepareRequest(p, failed.request)
                }
                continue
            }
            channelSwitch.show()
            val source = Prefs.getLiveSource(context)
            val applied = playbackIntent.recover(failure, { liveSelection() }, {
                val refreshed = BackgroundSourceUpdater.refreshPlaylistUrgent(context)
                if (!playbackIntent.isCurrent(failed) || !refreshed) null
                else Prefs.getPlaylistFileName(context)?.let { file -> withContext(Dispatchers.IO) { PlaylistCache.read(context, file) } }
            }, { parsePlaylist(it, source) },
                { player === p && p.currentMediaItem?.mediaId == failed.mediaId && Prefs.getLiveSource(context) == source },
                { commitPlaylist(it, PlaylistUpdateMode.Recovery) })
            if (applied || !playbackIntent.isCurrent(failed) || player !== p || Prefs.getLiveSource(context) != source) continue
            if (playbackIntent.retainsUnlistedLive) continue // No listed alternate may impersonate the retained owner.
            val groupIndex = ChannelGrouper.findGroupIndexByUrl(channelGroups, failed.request.channel.url) ?: continue
            val group = channelGroups[groupIndex]
            if (group.variants.size <= 1) continue
            val failedIndex = ChannelGrouper.findVariantIndexByUrl(group, failed.request.channel.url)
            for (offset in 1 until group.variants.size) {
                val index = (failedIndex+offset) % group.variants.size
                val channel = group.variants[index].channel
                if (channel.url == failed.request.channel.url) continue
                if (!playbackIntent.isCurrent(failed)) break
                currentGroupIndex = groupIndex
                currentVariantIndex = index
                prepareRequest(p, MediaPlaybackRequest(channel))
                Prefs.setLastChannel(context, channel.url, channel.title)
                break
            }
        }
    }

    LaunchedEffect(Unit) {
        val epgSource = Prefs.getEpgSource(context)
        if (epgSource.isNotBlank()) {
            val cachedEpg = EpgRepository.load(context, epgSource, forceRefresh = false)
            if (cachedEpg != null) {
                epgData = cachedEpg
                Log.i(epgLoadTag, "cache loaded. programs=${cachedEpg.programsByChannelId.size}")
            }

            launch {
                BackgroundSourceUpdater.epgUpdated.collect { data ->
                    epgData = data
                    Log.i(epgLoadTag, "background updated. programs=${data.programsByChannelId.size}")
                }
            }

            // t50: revert the t42 8s deferral — t45 showed deferring only moved the parse spike into the
            // user-browsing window. The cached EPG above already serves the first screen; refresh runs
            // immediately on IO.
            var lastAttemptTime = System.currentTimeMillis()
            while (true) {
                val now = System.currentTimeMillis()

                if (!shouldRefreshEpg.value && (now - lastAttemptTime < 5 * 60 * 1000L)) {
                    delay(10_000L)
                    continue
                }

                val lastEpgUpdate = Prefs.getLastEpgUpdateTime(context)
                val zone = ZoneId.systemDefault()
                val lastDate = Instant.ofEpochMilli(lastEpgUpdate).atZone(zone).toLocalDate()
                val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

                val isDifferentDay = !lastDate.isEqual(nowDate)
                val isOver6Hours = (now - lastEpgUpdate) > 6 * 3600 * 1000L

                if (shouldRefreshEpg.value || isDifferentDay || isOver6Hours) {
                    lastAttemptTime = now
                    val result = EpgRepository.loadStructured(context, epgSource, forceRefresh = true)
                    val newEpg = result.dataOrNull
                    if (result.isNetworkSuccess && newEpg != null && Prefs.getEpgSource(context) == epgSource) {
                        epgData = newEpg
                        Prefs.setLastEpgUpdateTime(context, System.currentTimeMillis())
                        Log.i(epgLoadTag, "network refreshed. programs=${newEpg.programsByChannelId.size}")
                        if (shouldRefreshEpg.value) {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "EPG更新成功", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Log.w(epgLoadTag, "network refresh failed")
                    }
                    shouldRefreshEpg.value = false
                }
                delay(600_000L)
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(30_000)
        }
    }

    val groups = remember(channelGroups) {
        ChannelGrouper.sortedDrawerGroups(channelGroups)
    }

    val filteredChannels = remember(channelGroups, selectedGroup) {
        val display = displayChannelsFromGroups(channelGroups)
        if (selectedGroup == "全部") display
        else {
            channelGroups.mapIndexedNotNull { index, group ->
                if (ChannelGrouper.displayGroupName(group) == selectedGroup) display.getOrNull(index) else null
            }
        }
    }

    LaunchedEffect(epgData, nowMillis, channelGroups, selectedGroup) {
        val data = epgData
        val groups = if (selectedGroup == "全部") {
            channelGroups
        } else {
            channelGroups.filter {
                ChannelGrouper.displayGroupName(it) == selectedGroup
            }
        }
        if (data == null || groups.isEmpty()) {
            nowProgramByUrl = emptyMap()
            return@LaunchedEffect
        }

        val map = withContext(Dispatchers.Default) {
            buildMap {
                for (group in groups) {
                    val displayChannel = group.defaultChannel
                    val t = data.nowProgramTitle(displayChannel, nowMillis)
                    if (!t.isNullOrBlank()) {
                        val current = data.nowProgram(displayChannel, nowMillis)
                        val progress = current?.let {
                            val duration = (it.endMillis - it.startMillis).toFloat()
                            if (duration <= 0f) null
                            else ((nowMillis - it.startMillis).toFloat() / duration).coerceIn(0f, 1f)
                        }
                        val ui = NowProgramUi(title = t, progress = progress)
                        for (variant in group.variants) {
                            put(variant.channel.url, ui)
                        }
                    }
                }
            }
        }
        nowProgramByUrl = map
    }

    fun startPlayback(request: MediaPlaybackRequest, showLoading: Boolean = true) {
        val sameLive = !request.isCatchup && request.channel.url == playbackIntent.request?.channel?.url
        if (showLoading && !sameLive) channelSwitch.show()
        val p = player
        if (p != null) prepareRequest(p, request) else playbackIntent.retain(request)
    }

    fun playGroupVariant(groupIndex: Int, variantIndex: Int, selectionTicket: Long? = null) {
        if (selectionTicket != null && !playbackIntent.matches(selectionTicket)) return
        val group = channelGroups.getOrNull(groupIndex) ?: return
        val channel = group.variants.getOrNull(variantIndex)?.channel ?: return
        if (selectionTicket == null) {
            sourceSelectGeneration += 1
            sourceSelectJob?.cancel()
            playbackIntent.beginSelection()
        }
        catchupRequest = null
        startPlayback(MediaPlaybackRequest(channel))
        currentGroupIndex = groupIndex
        currentVariantIndex = variantIndex
        Prefs.setLastChannel(context, channel.url, channel.title)
    }

    fun mergeMeasuredLatencies(results: Map<String, Int?>) {
        if (results.isEmpty()) return
        measuredLatencyByUrl = measuredLatencyByUrl + results
    }

    fun refreshGroupLatencies(group: ChannelGroup, force: Boolean = false) {
        val urls = group.variants.map { it.channel.url }
        if (urls.isEmpty()) return
        probingUrls = probingUrls + urls
        scope.launch {
            val results = SourceLatencyProber.probeAll(urls, force = force)
            mergeMeasuredLatencies(results)
            probingUrls = probingUrls - urls.toSet()
        }
    }

    /**
     * 切台：按源列表顺序探测，第一个能连上的就用；都失败则仍播第一个。
     */
    fun playGroupAuto(groupIndex: Int) {
        val group = channelGroups.getOrNull(groupIndex) ?: return
        if (group.variants.isEmpty()) return

        val generation = sourceSelectGeneration + 1
        sourceSelectGeneration = generation
        sourceSelectJob?.cancel()
        val selectionTicket = playbackIntent.beginSelection()

        channelSwitch.show()
        currentGroupIndex = groupIndex

        sourceSelectJob = scope.launch {
            var selectedIndex = 0
            for ((index, variant) in group.variants.withIndex()) {
                if (generation != sourceSelectGeneration || !playbackIntent.matches(selectionTicket)) return@launch
                val url = variant.channel.url
                val reachable = when {
                    SourceLatencyProber.hasFreshCache(url) -> {
                        val ms = SourceLatencyProber.cachedLatencyMs(url)
                        if (ms != null) {
                            mergeMeasuredLatencies(mapOf(url to ms))
                        }
                        ms != null
                    }
                    else -> {
                        probingUrls = probingUrls + url
                        val ms = SourceLatencyProber.probe(url)
                        mergeMeasuredLatencies(mapOf(url to ms))
                        probingUrls = probingUrls - url
                        ms != null
                    }
                }
                if (reachable) {
                    selectedIndex = index
                    break
                }
            }

            if (generation != sourceSelectGeneration || !playbackIntent.matches(selectionTicket)) return@launch
            playGroupVariant(groupIndex, selectedIndex, selectionTicket)

            val unchecked = group.variants.map { it.channel.url }
                .filter { !SourceLatencyProber.hasFreshCache(it) }
            if (unchecked.isNotEmpty() && generation == sourceSelectGeneration) {
                probingUrls = probingUrls + unchecked
                mergeMeasuredLatencies(SourceLatencyProber.probeAll(unchecked))
                probingUrls = probingUrls - unchecked.toSet()
            }
        }
    }

    fun playChannel(channel: Channel) {
        channelGroups.forEachIndexed { groupIndex, group ->
            if (group.variants.any { it.channel.url == channel.url && (channel.id == 0L || it.channel.id == channel.id) }) {
                playGroupAuto(groupIndex)
                return
            }
        }
    }

    DisposableEffect(player, channelGroups, currentGroupIndex, currentVariantIndex, catchupRequest) {
        setOnCatchupSeek { deltaMs ->
            val p = player ?: return@setOnCatchupSeek false
            val req = catchupRequest ?: return@setOnCatchupSeek false
            if (!p.isCurrentMediaItemSeekable) return@setOnCatchupSeek false
            val total = (req.endMillis - req.startMillis).coerceAtLeast(1L)
            val target = (p.currentPosition + deltaMs).coerceIn(0L, total)
            p.seekTo(target)
            catchupSeekPingAt = System.currentTimeMillis()
            catchupPositionMs = target
            true
        }
        setOnReturnToLive {
            val req = catchupRequest ?: return@setOnReturnToLive false
            val transition = playbackIntent.returnToLive(req, channelGroups) ?: return@setOnReturnToLive false
            if (transition.groupIndex != null) {
                playChannel(transition.request.channel)
            } else {
                sourceSelectGeneration += 1
                sourceSelectJob?.cancel()
                catchupRequest = null
                startPlayback(transition.request)
            }
            true
        }
        onDispose {
            setOnCatchupSeek(null)
            setOnReturnToLive(null)
        }
    }

    val channelGroupsState = rememberUpdatedState(channelGroups)
    val currentGroupIndexState = rememberUpdatedState(currentGroupIndex)
    val drawerOpenState = rememberUpdatedState(drawerOpen)
    val settingsDrawerOpenState = rememberUpdatedState(settingsDrawerOpen)
    val qualityDialogOpenState = rememberUpdatedState(qualityDialogOpen)
    val playGroupAutoState = rememberUpdatedState<(Int) -> Unit> { index -> playGroupAuto(index) }

    DisposableEffect(player) {
        setOnNumberJump { number ->
            val groups = channelGroupsState.value
            if (number !in 1..groups.size) false else {
                playGroupAutoState.value(number-1)
                infoBannerOpen = true
                true
            }
        }
        setOnChannelStep { direction ->
            val groups = channelGroupsState.value
            if (groups.size < 2) return@setOnChannelStep false
            if (drawerOpenState.value || settingsDrawerOpenState.value || qualityDialogOpenState.value) {
                return@setOnChannelStep false
            }
            val size = groups.size
            val current = currentGroupIndexState.value
            val nextGroupIndex = ((current + direction) % size + size) % size
            playGroupAutoState.value(nextGroupIndex)
            infoBannerOpen = true
            true
        }

        setOnSidebarPlay { groupIndex, variantIndex -> playGroupVariant(groupIndex, variantIndex) }
        setOnSidebarCatchup { req ->
            val bound = PlaybackIntent.catchup(ChannelGrouper.allChannels(channelGroups), req, playbackIntent.request)
            if (bound != null) {
                sourceSelectGeneration += 1
                sourceSelectJob?.cancel()
                playbackIntent.beginSelection()
                catchupRequest = req
                startPlayback(bound)
                sidebarController.closeDrawer()
            } else {
                // GAP-08: never fail silently.
                android.widget.Toast.makeText(context, "该节目暂不可回看", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        onDispose {
            setOnChannelStep(null)
            setOnNumberJump(null)
            setOnSidebarPlay(null)
            setOnSidebarCatchup(null)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Video is rendered by the native PlayerView(SurfaceView) BELOW this transparent ComposeView
        // (owned by the Activity). Compose only draws overlays here — no AndroidView / PlayerView in
        // the Compose tree — so the SurfaceView layer is never occluded (no t30 grey-screen regression).
        ChannelSwitchOverlay(
            controller = channelSwitch,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        )

        // t62: Compose PlayerDrawer removed — the native NativeSidebarView owns the sidebar.

        val qualityDialogGroup = channelGroups.getOrNull(
            if (qualityDialogGroupIndex >= 0) qualityDialogGroupIndex else playingGroupIndex
        )
        val qualityVariants = qualityDialogGroup?.variants?.takeIf { it.size > 1 }?.mapIndexed { index, variant ->
            val selectedIndex = if (qualityDialogOpen && qualityDialogGroupIndex >= 0) {
                ChannelGrouper.findVariantIndexByUrl(qualityDialogGroup, currentChannel?.url)
            } else {
                playingVariantIndex
            }
            val url = variant.channel.url
            SourceVariantUi(
                label = variant.qualityLabel,
                latencyMs = measuredLatencyByUrl[url] ?: SourceLatencyProber.cachedLatencyMs(url),
                probing = url in probingUrls,
                selected = index == selectedIndex
            )
        }.orEmpty()

        LaunchedEffect(qualityDialogOpen, qualityDialogGroupIndex, playingGroupIndex) {
            if (!qualityDialogOpen) return@LaunchedEffect
            val group = channelGroups.getOrNull(
                if (qualityDialogGroupIndex >= 0) qualityDialogGroupIndex else playingGroupIndex
            ) ?: return@LaunchedEffect
            refreshGroupLatencies(group, force = true)
        }

        SettingsDrawer(
            visible = settingsDrawerOpen,
            onSourceConfigClick = {
                val intent = Intent(context, MainActivity::class.java)
                context.startActivity(intent)
            },
            onEpgSettingsClick = {
                shouldRefreshEpg.value = true
                settingsDrawerOpen = false
                android.widget.Toast.makeText(context, "开始更新EPG...", android.widget.Toast.LENGTH_SHORT).show()
            },
            qualityMenuEnabled = playingGroup?.variants?.size?.let { it > 1 } == true,
            onQualityMenuClick = {
                settingsDrawerOpen = false
                qualityDialogGroupIndex = playingGroupIndex
                qualityDialogOpen = true
            },
            onCheckUpdateClick = {
                settingsDrawerOpen = false
                android.widget.Toast.makeText(context, "正在检查更新...", android.widget.Toast.LENGTH_SHORT).show()
                scope.launch {
                    val info = UpdateChecker.checkUpdateAndGetInfo(context)
                    if (info != null && info.hasUpdate) {
                        updateInfo = info
                        showUpdateDialog = true
                    } else {
                        android.widget.Toast.makeText(context, "已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onClose = { settingsDrawerOpen = false }
        )

        QualitySelectorDialog(
            visible = qualityDialogOpen,
            channelTitle = qualityDialogGroup?.displayTitle.orEmpty(),
            variants = qualityVariants,
            onSelect = { variantIndex ->
                sourceSelectGeneration += 1
                sourceSelectJob?.cancel()
                val groupIndex = if (qualityDialogGroupIndex >= 0) qualityDialogGroupIndex else playingGroupIndex
                playGroupVariant(groupIndex, variantIndex)
            },
            onClose = { qualityDialogOpen = false }
        )

        if (showUpdateDialog && updateInfo != null) {
            UpdateDialog(
                updateInfo = updateInfo!!,
                onDismiss = {
                    showUpdateDialog = false
                    updateInfo = null
                }
            )
        }

        val req = catchupRequest

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Bottom
            ) {
                val catchupConfig = if (req != null) {
                    CatchupConfiguration(
                        startMillis = req.startMillis,
                        endMillis = req.endMillis,
                        positionMs = catchupPositionMs
                    )
                } else null

                val displayChannel = if (req != null) {
                    allChannels.firstOrNull { it.url == req.liveUrl }
                } else {
                    currentChannel
                }

                val bannerChannel = displayChannel?.let { ch ->
                    val cleanTitle = channelGroups.getOrNull(playingGroupIndex)?.displayTitle
                        ?: EpgNormalize.displayName(ch.title)
                    if (cleanTitle == ch.title) ch else ch.copy(title = cleanTitle)
                }

                val displayTitle = if (req != null) {
                    "${req.programTitle} (回看中)"
                } else {
                    if (currentLive.retainedOutsideList) null else displayChannel?.let { nowProgramByUrl[it.url]?.title }
                }

                val displayProgress = if (req != null) {
                    null
                } else {
                    if (currentLive.retainedOutsideList) null else displayChannel?.let { nowProgramByUrl[it.url]?.progress }
                }

                val displayNumber = if (req != null) {
                    val liveUrl = req.liveUrl
                    val groupIdx = channelGroups.indexOfFirst { group ->
                        group.variants.any { it.channel.url == liveUrl }
                    }
                    if (groupIdx >= 0) groupIdx + 1 else 0
                } else {
                    currentLive.number
                }

                ChannelInfoBanner(
                    visible = infoBannerOpen || isSeekOverlayVisible,
                    channel = bannerChannel,
                    programTitle = displayTitle,
                    programProgress = displayProgress,
                    channelNumber = displayNumber,
                    videoFormat = videoFormat,
                    catchupConfiguration = catchupConfig,
                    animate = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun displayChannelsFromGroups(groups: List<ChannelGroup>): List<Channel> {
    return ChannelGrouper.displayChannels(groups)
}

private fun buildPrefetchLogoUrls(channels: List<Channel>, currentIndex: Int): List<String> {
    if (channels.isEmpty()) return emptyList()

    val maxUrls = 220
    val out = ArrayList<String>(minOf(maxUrls, channels.size))
    val seen = HashSet<String>(minOf(maxUrls, channels.size))

    fun addAt(index: Int) {
        if (out.size >= maxUrls) return
        val url = channels.getOrNull(index)?.logoUrl?.trim().orEmpty()
        if (url.isNotBlank() && seen.add(url)) out.add(url)
    }

    addAt(currentIndex)
    for (delta in 1..8) {
        addAt(currentIndex - delta)
        addAt(currentIndex + delta)
    }

    val head = minOf(120, channels.size)
    for (i in 0 until head) addAt(i)

    return out
}

private val catchupTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun CatchupProgressBar(
    startMillis: Long,
    endMillis: Long,
    positionMs: Long,
    modifier: Modifier = Modifier,
    isBottomPaddingNeeded: Boolean = false
) {
    val total = (endMillis - startMillis).coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    val zone = ZoneId.systemDefault()
    val startText = catchupTimeFormatter.format(Instant.ofEpochMilli(startMillis).atZone(zone))
    val endText = catchupTimeFormatter.format(Instant.ofEpochMilli(endMillis).atZone(zone))

    val containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp)
    val bottomPadding = if (isBottomPaddingNeeded) 20.dp else 4.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor, shape)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = bottomPadding)
        ) {
            androidx.tv.material3.Text(
                text = startText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(12.dp)
            ) {
                // Track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), RoundedCornerShape(2.dp))
                )

                // Progress Fill
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .align(Alignment.CenterStart)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )

                // Dot
                val bias = if (progress.isNaN()) -1f else (progress * 2) - 1
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .align(BiasAlignment(bias.coerceIn(-1f, 1f), 0f))
                        .background(ComposeColor.White, CircleShape)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            androidx.tv.material3.Text(
                text = endText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            )
        }
    }
}
