@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.cjlhll.iptv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme
import androidx.compose.ui.graphics.Color as ComposeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class PlaylistUpdateMode { Initial, Background, Recovery }

/** 播放恢复事件，绑定产生它的 session/attempt，防止过期事件覆盖新会话（D03/D04）。 */
private data class PlaybackEvent(val token: String?, val reason: String?)

/** 失败终态信息，作为“待呈现”状态，避免抢已有面板焦点（D04）。 */
private data class FailureInfo(val reason: String?)

/** 成功后提交历史所需信息（R10：使用稳定频道键而非位置序号）。 */
private data class CommitInfo(val url: String, val title: String, val groupIndex: Int, val channelKey: String)

/** 纯逻辑：恢复预算与事件归属，便于单测（D03/D04）。 */
object PlaybackRecoveryPolicy {
    const val MAX_ATTEMPTS = 3
    const val PREPARE_TIMEOUT_MS = 12_000L
    const val BACKOFF_MS = 800L

    fun accepts(eventSession: Int, eventAttempt: Int, currentSession: Int, currentAttempt: Int): Boolean =
        eventSession == currentSession && eventAttempt == currentAttempt

    /** 事件对应 attempt 失败后是否已耗尽预算（初始 attempt=0，最多 3 次恢复）。 */
    fun exhausted(attempt: Int): Boolean = attempt >= MAX_ATTEMPTS
}

class PlayerActivity : ComponentActivity() {
    private var onChannelStep: ((Int) -> Boolean)? = null
    private var onNumberJump: ((Int) -> Boolean)? = null
    private var onResolveNumber: ((Int) -> String?)? = null
    private var onChannelCount: (() -> Int)? = null
    private var onPreviousWatched: (() -> Boolean)? = null
    private var onOkOverlay: (() -> Boolean)? = null
    private var onOpenChannels: (() -> Unit)? = null
    private var onOpenPrograms: (() -> Unit)? = null
    private var setChannelPanelOpen: ((Boolean) -> Unit)? = null
    private var isChannelPanelOpen: Boolean = false
    private var setSettingsOpen: ((Boolean) -> Unit)? = null
    private var isSettingsOpen: Boolean = false
    private var setInfoOpen: ((Boolean) -> Unit)? = null
    private var isInfoOpen: Boolean = false
    private var setFailurePanelOpen: ((Boolean) -> Unit)? = null
    private var isFailurePanelOpen: Boolean = false
    private var isCatchupPlayback: Boolean = false
    private var onCatchupSeek: ((Long) -> Boolean)? = null
    private var onReturnToLive: (() -> Boolean)? = null
    private var lastBackPressTime: Long = 0
    private var lastBackPressTimeCatchup: Long = 0
    private var lastChannelStepAt: Long = 0

    private val numberBuffer = NumberBufferController()
    private val numberHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var numberSubmitRunnable: Runnable? = null
    private var numberHideRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 使用推荐的返回回调注册，替代拦截 KEYCODE_BACK（D16 / lint GestureBackNavigation）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })
        val videoUrl = intent.getStringExtra("VIDEO_URL") ?: ""
        val videoTitle = intent.getStringExtra("VIDEO_TITLE") ?: "Live"
        val playlistFileName = intent.getStringExtra("PLAYLIST_FILE_NAME") ?: Prefs.getPlaylistFileName(this)

        setContent {
            val colorScheme = darkColorScheme(
                primary = ComposeColor(0xFFBDBDBD),
                onPrimary = ComposeColor.Black,
                background = ComposeColor(0xFF101316),
                onBackground = ComposeColor.White,
                surface = ComposeColor(0xFF161C21),
                onSurface = ComposeColor.White,
            )
            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape) {
                    VideoPlayerScreen(
                        url = videoUrl,
                        title = videoTitle,
                        playlistFileName = playlistFileName,
                        numberBuffer = numberBuffer,
                        setOnChannelStep = { onChannelStep = it },
                        setOnNumberJump = { onNumberJump = it },
                        setOnResolveNumber = { onResolveNumber = it },
                        setOnChannelCount = { onChannelCount = it },
                        setOnPreviousWatched = { onPreviousWatched = it },
                        setOnOkOverlay = { onOkOverlay = it },
                        setOnOpenChannels = { onOpenChannels = it },
                        setOnOpenPrograms = { onOpenPrograms = it },
                        setChannelPanelOpenController = { setChannelPanelOpen = it },
                        onChannelPanelOpenChanged = { isChannelPanelOpen = it },
                        setSettingsOpenController = { setSettingsOpen = it },
                        onSettingsOpenChanged = { isSettingsOpen = it },
                        setInfoOpenController = { setInfoOpen = it },
                        onInfoOpenChanged = { isInfoOpen = it },
                        setFailurePanelOpenController = { setFailurePanelOpen = it },
                        onFailurePanelOpenChanged = { isFailurePanelOpen = it },
                        setOnCatchupSeek = { onCatchupSeek = it },
                        setOnReturnToLive = { onReturnToLive = it },
                        onCatchupPlaybackChanged = { isCatchupPlayback = it },
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    private fun calculateSeekStep(repeatCount: Int): Long = when {
        repeatCount == 0 -> 10_000L
        repeatCount < 15 -> 30_000L
        repeatCount < 40 -> 60_000L
        else -> 300_000L
    }

    private fun cancelNumberInput() {
        numberSubmitRunnable?.let { numberHandler.removeCallbacks(it) }
        numberHideRunnable?.let { numberHandler.removeCallbacks(it) }
        numberBuffer.hide()
    }

    private fun scheduleNumberSubmit() {
        numberSubmitRunnable?.let { numberHandler.removeCallbacks(it) }
        val runnable = Runnable { submitNumberInput() }
        numberSubmitRunnable = runnable
        numberHandler.postDelayed(runnable, 1200)
    }

    private fun scheduleNumberHide(delayMs: Long) {
        numberHideRunnable?.let { numberHandler.removeCallbacks(it) }
        val runnable = Runnable { numberBuffer.hide() }
        numberHideRunnable = runnable
        numberHandler.postDelayed(runnable, delayMs)
    }

    private fun submitNumberInput() {
        numberSubmitRunnable?.let { numberHandler.removeCallbacks(it) }
        val n = numberBuffer.text.toIntOrNull()
        if (n == null) { cancelNumberInput(); return }
        val ok = onNumberJump?.invoke(n) == true
        if (ok) {
            numberBuffer.hide()
        } else {
            numberBuffer.show(numberBuffer.text, "没有频道 $n", isError = true)
            scheduleNumberHide(2500)
        }
    }

    private fun handleDigit(event: KeyEvent) {
        if (event.repeatCount > 0) return
        val count = onChannelCount?.invoke() ?: 0
        val maxDigits = if (count <= 0) 3 else minOf(6, maxOf(3, count.toString().length))
        val nextText = numberBuffer.text + (event.keyCode - KeyEvent.KEYCODE_0)
        if (nextText.length > maxDigits) {
            numberBuffer.show(numberBuffer.text, "已达最大位数（$maxDigits）", isError = true)
            scheduleNumberHide(2000)
            return
        }
        val n = nextText.toIntOrNull()
        val target = if (n != null) onResolveNumber?.invoke(n) else null
        val subtitle = if (target != null) "确定立即切换 · $target" else "确定立即切换"
        numberBuffer.show(nextText, subtitle)
        scheduleNumberSubmit()
    }

    /** 换台节流：按住重复最多每 250ms 提交一次（D16）。 */
    private fun channelStepThrottled(direction: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastChannelStepAt < 250L) return true
        lastChannelStepAt = now
        return onChannelStep?.invoke(direction) == true
    }

    /** 由 OnBackPressedCallback 调用（推荐的回调注册方式，避免 lint GestureBackNavigation）。 */
    private fun handleBackPressed(): Boolean {
        val overlayVisible = isChannelPanelOpen || isSettingsOpen || isFailurePanelOpen
        if (overlayVisible) return false // 交给 Compose BackHandler 逐层处理
        if (numberBuffer.visible) { cancelNumberInput(); return true }
        if (isInfoOpen) { setInfoOpen?.invoke(false); return true }
        if (isCatchupPlayback) {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTimeCatchup > 2000) {
                lastBackPressTimeCatchup = now
                Toast.makeText(this, "再按一次返回直播", Toast.LENGTH_SHORT).show()
                return true
            }
            if (onReturnToLive?.invoke() == true) { lastBackPressTimeCatchup = 0; return true }
            return true
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPressTime > 2000) {
            lastBackPressTime = now
            Toast.makeText(this, "再按一次退出app", Toast.LENGTH_SHORT).show()
            return true
        }
        finish()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val overlayVisible = isChannelPanelOpen || isSettingsOpen || isFailurePanelOpen

        if (overlayVisible) return false

        // 其他操作重置双 Back 计时（D16）
        lastBackPressTime = 0
        lastBackPressTimeCatchup = 0

        // 数字键缓冲
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (isCatchupPlayback) {
                Toast.makeText(this, "回看中，可从设置返回直播", Toast.LENGTH_SHORT).show()
                return true
            }
            handleDigit(event)
            return true
        }

        val isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        // Menu 始终为安全设置入口；打开前先取消数字缓冲（R07）
        if (keyCode == KeyEvent.KEYCODE_MENU) { cancelNumberInput(); setSettingsOpen?.invoke(true); return true }

        // 回看模式
        if (isCatchupPlayback) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isOk) return true
                    val step = calculateSeekStep(event.repeatCount)
                    val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step
                    if (onCatchupSeek?.invoke(delta) == true) true else super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { setInfoOpen?.invoke(true); true }
                else -> {
                    if (isOk) {
                        // OK 优先重开失败面板，其次信息条（D09）
                        if (numberBuffer.visible) { submitNumberInput(); true }
                        else if (onOkOverlay?.invoke() == true) true
                        else { setInfoOpen?.invoke(true); true }
                    } else super.onKeyDown(keyCode, event)
                }
            }
        }

        // 数字缓冲存在时，方向先取消输入
        if (numberBuffer.visible && keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN
            )
        ) cancelNumberInput()

        if (isOk && numberBuffer.visible) { submitNumberInput(); return true }
        if (isOk && onOkOverlay?.invoke() == true) return true

        // 先解析有效映射，再处理未自定义时的 Channel± 默认别名（D06）
        val action = AppSettings.actionForKey(this, keyCode)
        if (action != null) {
            when (action) {
                RemoteAction.PREV_CHANNEL -> return channelStepThrottled(-1)
                RemoteAction.NEXT_CHANNEL -> return channelStepThrottled(1)
                RemoteAction.PREV_WATCHED -> return onPreviousWatched?.invoke() == true
                RemoteAction.CHANNEL_LIST -> { cancelNumberInput(); onOpenChannels?.invoke(); return true }
                RemoteAction.PROGRAM_GUIDE -> { cancelNumberInput(); onOpenPrograms?.invoke(); return true }
                RemoteAction.SETTINGS -> { cancelNumberInput(); setSettingsOpen?.invoke(true); return true }
                RemoteAction.INFO -> { setInfoOpen?.invoke(true); return true }
            }
        }

        // 未自定义时才把 Channel+/- 当默认换台
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> return channelStepThrottled(1)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> return channelStepThrottled(-1)
        }

        return super.onKeyDown(keyCode, event)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    url: String,
    title: String,
    playlistFileName: String?,
    numberBuffer: NumberBufferController,
    setOnChannelStep: (((Int) -> Boolean)?) -> Unit,
    setOnNumberJump: (((Int) -> Boolean)?) -> Unit,
    setOnResolveNumber: (((Int) -> String?)?) -> Unit,
    setOnChannelCount: (((() -> Int))?) -> Unit,
    setOnPreviousWatched: (((() -> Boolean))?) -> Unit,
    setOnOkOverlay: (((() -> Boolean))?) -> Unit,
    setOnOpenChannels: (((() -> Unit))?) -> Unit,
    setOnOpenPrograms: (((() -> Unit))?) -> Unit,
    setChannelPanelOpenController: (((Boolean) -> Unit)?) -> Unit,
    onChannelPanelOpenChanged: (Boolean) -> Unit,
    setSettingsOpenController: (((Boolean) -> Unit)?) -> Unit,
    onSettingsOpenChanged: (Boolean) -> Unit,
    setInfoOpenController: (((Boolean) -> Unit)?) -> Unit,
    onInfoOpenChanged: (Boolean) -> Unit,
    setFailurePanelOpenController: (((Boolean) -> Unit)?) -> Unit,
    onFailurePanelOpenChanged: (Boolean) -> Unit,
    setOnCatchupSeek: (((Long) -> Boolean)?) -> Unit,
    setOnReturnToLive: ((() -> Boolean)?) -> Unit,
    onCatchupPlaybackChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var channelPanelOpen by remember { mutableStateOf(false) }
    var requestedLayer by remember { mutableStateOf<NavLayer?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var infoBannerOpen by remember { mutableStateOf(false) }
    var settingsEpoch by remember { mutableIntStateOf(0) }

    // 播放会话/预算
    var sessionId by remember { mutableIntStateOf(0) }
    var preparedUrl by remember { mutableStateOf<String?>(null) }
    var recoveryJob by remember { mutableStateOf<Job?>(null) }
    var probeJob by remember { mutableStateOf<Job?>(null) }
    var pendingFailure by remember { mutableStateOf<FailureInfo?>(null) }
    var failureReason by remember { mutableStateOf<String?>(null) }
    var playbackFailed by remember { mutableStateOf(false) }
    var failureRetained by remember { mutableStateOf(false) }
    val playbackEvents = remember { MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8) }
    var endedCatchupLiveUrl by remember { mutableStateOf<String?>(null) }
    // S01/S02：媒体 attempt 身份与连续失败预算分离；事件来源路由
    val attemptState = remember { PlaybackAttemptState() }
    val eventRouter = remember { AttemptEventRouter() }
    // R02：媒体项身份 token 与用户意图门（事件/探测/恢复统一归属）
    var activeMediaToken by remember { mutableStateOf<String?>(null) }
    var tokenSeq by remember { mutableLongStateOf(0L) }
    val intentGate = remember { PlaybackIntentGate() }
    val probeCoordinator = remember { ProbeCoordinator(intentGate) }
    val handledTokens = remember { java.util.Collections.synchronizedSet(HashSet<String>()) }
    // R06：Guide 直接指定当前播放频道
    var requestedChannelUrl by remember { mutableStateOf<String?>(null) }
    var requestedEntryGuide by remember { mutableStateOf(false) }

    val channelSwitch = rememberChannelSwitchController()
    var videoFormat by remember { mutableStateOf<Format?>(null) }
    var measuredLatencyByUrl by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    var probingUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var probedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sourceSelectGeneration by remember { mutableIntStateOf(0) }
    var playbackHttpFactory by remember { mutableStateOf<OkHttpDataSource.Factory?>(null) }

    var channelGroups by remember { mutableStateOf<List<ChannelGroup>>(emptyList()) }
    var currentGroupIndex by remember { mutableIntStateOf(0) }
    var currentVariantIndex by remember { mutableIntStateOf(0) }
    var selectedGroup by remember { mutableStateOf("全部") }
    var previousChannel by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingCommit by remember { mutableStateOf<CommitInfo?>(null) }
    var lastCommittedUrl by remember { mutableStateOf<String?>(null) }
    var lastCommittedTitle by remember { mutableStateOf<String?>(null) }
    var lastCommittedGroupIndex by remember { mutableIntStateOf(-1) }
    var lastCommittedKey by remember { mutableStateOf<String?>(null) }

    var epgData by remember { mutableStateOf<EpgData?>(null) }
    var epgStatus by remember { mutableStateOf(EpgStatus.UNCONFIGURED) }
    var epgLastSuccessText by remember { mutableStateOf<String?>(null) }
    var epgRefreshInProgress by remember { mutableStateOf(false) }
    var epgRefreshMessage by remember { mutableStateOf<String?>(null) }
    var playlistRefreshInProgress by remember { mutableStateOf(false) }
    var playlistRefreshMessage by remember { mutableStateOf<String?>(null) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var nowProgramByUrl by remember { mutableStateOf<Map<String, NowProgramUi>>(emptyMap()) }
    var catchupRequest by remember { mutableStateOf<CatchupPlayRequest?>(null) }
    var catchupPositionMs by remember { mutableStateOf(0L) }

    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    val drawerLogoWidthPx = remember(density) { with(density) { 48.dp.roundToPx() } }
    val drawerLogoHeightPx = remember(density) { with(density) { 32.dp.roundToPx() } }
    val bannerLogoWidthPx = remember(density) { with(density) { 80.dp.roundToPx() } }
    val bannerLogoHeightPx = remember(density) { with(density) { 48.dp.roundToPx() } }

    val currentChannel = remember(channelGroups, currentGroupIndex, currentVariantIndex) {
        channelGroups.getOrNull(currentGroupIndex)?.variants?.getOrNull(currentVariantIndex)?.channel
    }
    val allChannels = remember(channelGroups) { ChannelGrouper.allChannels(channelGroups) }
    val playingGroupIndex = remember(currentChannel?.url, channelGroups, currentGroupIndex) {
        ChannelGrouper.findGroupIndexByUrl(channelGroups, currentChannel?.url) ?: currentGroupIndex
    }
    val playingGroup = channelGroups.getOrNull(playingGroupIndex)
    val playingVariantIndex = remember(playingGroup, currentChannel?.url) {
        playingGroup?.let { ChannelGrouper.findVariantIndexByUrl(it, currentChannel?.url) } ?: 0
    }

    var heavyWorkEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1500); heavyWorkEnabled = true }

    // ---------- 播放器生命周期：使用最新会话状态重建（D01） ----------
    fun userAgentFor(targetUrl: String?): String =
        channelGroups.asSequence().flatMap { it.variants.asSequence() }
            .firstOrNull { it.channel.url == targetUrl }?.channel?.userAgent
            ?: "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val http = OkHttpDataSource.Factory(NetUtil.ipv4OkHttpClient)
            .setUserAgent(userAgentFor(preparedUrl))
        playbackHttpFactory = http
        val dataSourceFactory = DefaultDataSource.Factory(context, http)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 120_000, 1_000, 2_000)
            .setBackBuffer(30_000, true)
            .build()
        val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val newPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .build()
            .apply { playWhenReady = true; setWakeMode(C.WAKE_MODE_NETWORK) }
        player = newPlayer
        return newPlayer
    }

    fun updateHttpUserAgent(targetUrl: String?) {
        playbackHttpFactory?.setUserAgent(userAgentFor(targetUrl))
    }

    fun releasePlayer() { player?.release(); player = null }

    fun commitSuccessfulPlayback(mediaToken: String?) {
        if (catchupRequest != null) return
        if (mediaToken == null || mediaToken != activeMediaToken) return // 只接受真实成功媒体事件（R10）
        val c = pendingCommit ?: return
        if (preparedUrl != null && c.url != preparedUrl) return
        if (lastCommittedKey != null && lastCommittedKey != c.channelKey) {
            previousChannel = (lastCommittedUrl ?: "") to (lastCommittedTitle ?: "")
        }
        lastCommittedUrl = c.url
        lastCommittedTitle = c.title
        lastCommittedGroupIndex = c.groupIndex
        lastCommittedKey = c.channelKey
        Prefs.setLastChannel(context, c.url, c.title)
    }

    /**
     * 为本次 attempt 的播放器实例绑定监听器。监听器闭包捕获产生它的 [token]（真实来源身份，S02），
     * 不读取回调发生时的可变 currentMediaItem；旧实例/旧 attempt 事件通过 eventRouter 拒收。
     */
    fun attachAttemptListener(p: ExoPlayer, token: String) {
        val activity = context as? Activity
        fun setScreenOn(keepOn: Boolean) {
            val window = activity?.window ?: return
            if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { setScreenOn(isPlaying) }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { videoFormat = p.videoFormat }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { videoFormat = p.videoFormat }
            override fun onPlaybackStateChanged(state: Int) {
                if (!eventRouter.isCurrent(token)) return
                if (state == Player.STATE_READY) {
                    channelSwitch.hide()
                    attemptState.onReady() // S01：仅重置连续失败预算，不改媒体 attempt
                    commitSuccessfulPlayback(token)
                } else if (state == Player.STATE_ENDED) {
                    if (catchupRequest != null) {
                        endedCatchupLiveUrl = catchupRequest?.liveUrl
                        catchupRequest = null
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                // S02：事件来源即本播放器实例捕获的 token，避免“读取当前媒体盖新身份”
                if (!eventRouter.isCurrent(token)) return
                playbackEvents.tryEmit(PlaybackEvent(token, error.localizedMessage ?: error.errorCodeName))
            }
        }
        p.addListener(listener)
        setScreenOn(p.isPlaying)
    }

    /**
     * 真正发起一次 prepare。每次 attempt 隔离播放器实例（S02），媒体项 mediaId 携带
     * intent/mediaAttempt/seq，并建立新的 12 秒监控（S01/S03）。
     */
    fun prepareMediaNow(targetUrl: String) {
        preparedUrl = targetUrl
        val token = "i${intentGate.current()}-a${attemptState.mediaAttempt}-s${++tokenSeq}"
        activeMediaToken = token
        eventRouter.activate(token)
        releasePlayer()
        val p = ensurePlayer()
        attachAttemptListener(p, token)
        channelSwitch.show()
        p.setMediaItem(MediaItem.Builder().setUri(targetUrl).setMediaId(token).build())
        p.prepare()
        p.play()
    }

    /** 新的播放会话（用户换台/回看/主动重试）。提升用户意图代次并使旧探测/恢复失效（R01）。 */
    fun startSession(targetUrl: String, catchup: CatchupPlayRequest?, groupIndex: Int, titleText: String?, channelKey: String? = null) {
        intentGate.newIntent()
        probeJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        sessionId += 1
        attemptState.newSession()
        handledTokens.clear()
        pendingFailure = null
        playbackFailed = false
        failureRetained = false
        failureReason = null
        pendingCommit = if (catchup == null) CommitInfo(targetUrl, titleText ?: "", groupIndex, channelKey ?: titleText ?: targetUrl) else null
        prepareMediaNow(targetUrl)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                // 重建走统一 prepare 入口，建立新 token 与 12 秒监控（R02）
                if (player == null) {
                    val target = preparedUrl
                    if (!target.isNullOrBlank()) prepareMediaNow(target) else ensurePlayer()
                }
            } else if (event == Lifecycle.Event.ON_STOP) {
                // S03：停播即失效用户意图并取消 probe/recovery/timeout，防止后台旧任务起播
                intentGate.newIntent()
                probeJob?.cancel()
                probeJob = null
                recoveryJob?.cancel()
                recoveryJob = null
                eventRouter.clear()
                activeMediaToken = null
                releasePlayer()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); releasePlayer() }
    }

    // 12 秒准备超时：绑定真实媒体 token（S02/S03）
    LaunchedEffect(activeMediaToken) {
        val token = activeMediaToken ?: return@LaunchedEffect
        delay(PlaybackRecoveryPolicy.PREPARE_TIMEOUT_MS)
        if (eventRouter.isCurrent(token) && player?.playbackState != Player.STATE_READY && !playbackFailed) {
            playbackEvents.tryEmit(PlaybackEvent(token, "连接超时（12秒未就绪）"))
        }
    }

    BindChannelSwitchLoading(channelSwitch, player)

    DisposableEffect(Unit) {
        setChannelPanelOpenController { channelPanelOpen = it }
        setSettingsOpenController { settingsOpen = it }
        setInfoOpenController { infoBannerOpen = it }
        setFailurePanelOpenController { playbackFailed = it }
        onDispose {
            setChannelPanelOpenController(null)
            setSettingsOpenController(null)
            setInfoOpenController(null)
            setFailurePanelOpenController(null)
        }
    }

    LaunchedEffect(channelPanelOpen) { onChannelPanelOpenChanged(channelPanelOpen) }
    LaunchedEffect(settingsOpen) { onSettingsOpenChanged(settingsOpen); if (!settingsOpen) settingsEpoch++ }
    LaunchedEffect(infoBannerOpen) { onInfoOpenChanged(infoBannerOpen) }
    LaunchedEffect(playbackFailed) { onFailurePanelOpenChanged(playbackFailed) }
    LaunchedEffect(infoBannerOpen, currentGroupIndex, currentVariantIndex) {
        if (infoBannerOpen) { delay(5000); infoBannerOpen = false }
    }
    LaunchedEffect(catchupRequest) { onCatchupPlaybackChanged(catchupRequest != null) }
    LaunchedEffect(catchupRequest, player) {
        val p = player
        if (catchupRequest == null || p == null) { catchupPositionMs = 0L; return@LaunchedEffect }
        while (true) {
            val req = catchupRequest ?: break
            val total = (req.endMillis - req.startMillis).coerceAtLeast(1L)
            catchupPositionMs = p.currentPosition.coerceIn(0L, total)
            delay(500)
        }
    }

    // 失败终态“待呈现”：等已有面板关闭后再显示（D04）
    LaunchedEffect(pendingFailure, channelPanelOpen, settingsOpen) {
        val f = pendingFailure ?: return@LaunchedEffect
        if (!channelPanelOpen && !settingsOpen) {
            failureReason = f.reason
            playbackFailed = true
            failureRetained = false
            pendingFailure = null
        }
    }

    suspend fun applyPlaylistContent(
        content: String,
        preserveCurrentUrl: String?,
        mode: PlaylistUpdateMode = PlaylistUpdateMode.Initial,
        restoreLast: Boolean = true,
    ) {
        val previousUrl = preserveCurrentUrl ?: preparedUrl ?: url
        val preserveTitle = channelGroups.getOrNull(currentGroupIndex)?.displayTitle ?: title
        val previousGroups = channelGroups
        val previousGroupIndex = currentGroupIndex

        val (parsedGroups, groupIndex, variantIndex) = withContext(Dispatchers.Default) {
            val groups = ChannelGrouper.group(M3uParser.parse(content))
            val pair = when {
                groups.isEmpty() -> 0 to 0
                mode == PlaylistUpdateMode.Initial && !restoreLast -> 0 to groups.first().defaultVariantIndex
                !previousUrl.isNullOrBlank() && ChannelGrouper.containsUrl(groups, previousUrl) -> {
                    val gi = ChannelGrouper.findGroupIndexByUrl(groups, previousUrl)!!
                    gi to ChannelGrouper.findVariantIndexByUrl(groups[gi], previousUrl)
                }
                else -> ChannelGrouper.findBestGroupVariant(groups, previousUrl, preserveTitle, previousGroups, previousGroupIndex)
            }
            Triple(groups, pair.first, pair.second)
        }
        if (parsedGroups.isEmpty()) {
            // 空/坏内容不替换现有内存列表（D10）
            if (channelGroups.isEmpty()) pendingFailure = FailureInfo("无法加载频道列表")
            return
        }

        var targetGroupIndex = groupIndex.coerceIn(0, parsedGroups.lastIndex)
        var targetVariantIndex = variantIndex.coerceIn(0, parsedGroups[targetGroupIndex].variants.lastIndex)
        val urlMissing = !previousUrl.isNullOrBlank() && !ChannelGrouper.containsUrl(parsedGroups, previousUrl)
        if (urlMissing && mode != PlaylistUpdateMode.Initial) {
            ChannelGrouper.findRecoveryVariant(parsedGroups, previousUrl, preserveTitle, previousGroups, previousGroupIndex)?.let {
                targetGroupIndex = it.first
                targetVariantIndex = it.second
            }
        }

        channelGroups = parsedGroups
        currentGroupIndex = targetGroupIndex
        currentVariantIndex = targetVariantIndex
        val newChannel = parsedGroups[targetGroupIndex].variants[targetVariantIndex].channel

        // D01：Initial 缓存解析后显式启动选中频道；不依赖 URL 相等推断已播放
        if (mode == PlaylistUpdateMode.Initial) {
            val noMedia = player == null || player?.currentMediaItem == null
            if (catchupRequest == null && (noMedia || preparedUrl != newChannel.url)) {
                startSession(newChannel.url, null, targetGroupIndex, parsedGroups[targetGroupIndex].displayTitle, channelKey = parsedGroups[targetGroupIndex].key)
            }
        } else if (catchupRequest == null && mode != PlaylistUpdateMode.Initial) {
            // 后台刷新：仅当当前 URL 变化才切流；URL 不变由恢复逻辑显式重试当前流
            if (newChannel.url != preparedUrl && preparedUrl != null) {
                startSession(newChannel.url, null, targetGroupIndex, parsedGroups[targetGroupIndex].displayTitle, channelKey = parsedGroups[targetGroupIndex].key)
            }
        }

        while (!heavyWorkEnabled) delay(200)
        val urls = buildPrefetchLogoUrls(displayChannelsFromGroups(parsedGroups), currentGroupIndex)
        scope.launch { LogoLoader.prefetch(context, urls, drawerLogoWidthPx, drawerLogoHeightPx) }
        scope.launch { delay(800); LogoLoader.prefetch(context, urls, bannerLogoWidthPx, bannerLogoHeightPx) }
    }

    val restoreLastChannel = remember { AppSettings.isRestoreLastChannel(context) }

    LaunchedEffect(playlistFileName, url, title) {
        val liveSource = Prefs.getLiveSource(context)
        val cacheFileName = when {
            !playlistFileName.isNullOrBlank() -> playlistFileName
            liveSource.isNotBlank() -> PlaylistCache.fileNameForSource(liveSource)
            else -> null
        }
        var loaded = false
        if (!cacheFileName.isNullOrBlank()) {
            val content = withContext(Dispatchers.IO) { PlaylistCache.read(context, cacheFileName) }
            if (content != null) {
                applyPlaylistContent(content, preserveCurrentUrl = url, mode = PlaylistUpdateMode.Initial, restoreLast = restoreLastChannel)
                loaded = channelGroups.isNotEmpty()
            }
        }
        // 无可用缓存：按策略尝试后台加载；MANUAL 或有源但缺缓存也要给出可操作出口（R08）
        if (!loaded && liveSource.isNotBlank()) {
            val policy = AppSettings.getPlaylistRefreshPolicy(context)
            if (policy != PlaylistRefreshPolicy.MANUAL) {
                val ok = runCatching { BackgroundSourceUpdater.refreshPlaylistUrgent(context) }.getOrDefault(false)
                if (ok) {
                    val fn = Prefs.getPlaylistFileName(context)
                    val content = if (fn != null) withContext(Dispatchers.IO) { PlaylistCache.read(context, fn) } else null
                    if (content != null) applyPlaylistContent(content, preserveCurrentUrl = url, mode = PlaylistUpdateMode.Initial, restoreLast = restoreLastChannel)
                } else if (channelGroups.isEmpty()) {
                    pendingFailure = FailureInfo("无法加载播放列表")
                }
            } else if (channelGroups.isEmpty()) {
                // MANUAL 不自动联网，但必须显示可操作状态而非黑屏
                pendingFailure = FailureInfo("没有可用缓存，请手动刷新列表或重新配置源")
            }
        } else if (!loaded && liveSource.isBlank() && channelGroups.isEmpty()) {
            pendingFailure = FailureInfo("尚未配置播放源")
        }
    }

    LaunchedEffect(Unit) {
        BackgroundSourceUpdater.playlistUpdated.collectLatest { fileName ->
            val expected = Prefs.getPlaylistFileName(context) ?: return@collectLatest
            if (fileName != expected) return@collectLatest
            val content = withContext(Dispatchers.IO) { PlaylistCache.read(context, fileName) } ?: return@collectLatest
            applyPlaylistContent(content, preserveCurrentUrl = preparedUrl ?: url, mode = PlaylistUpdateMode.Background)
        }
    }

    // ---------- 恢复流程（R01/R02） ----------
    suspend fun tryRecoverAlternative(token: String): Boolean {
        if (token != activeMediaToken) return true
        if (catchupRequest != null) {
            val req = catchupRequest ?: return true
            prepareMediaNow(req.catchupUrl)
            return true
        }
        val refreshed = runCatching { BackgroundSourceUpdater.refreshPlaylistUrgent(context) }.getOrDefault(false)
        if (token != activeMediaToken) return true
        if (refreshed) {
            val fn = Prefs.getPlaylistFileName(context)
            val content = if (fn != null) withContext(Dispatchers.IO) { PlaylistCache.read(context, fn) } else null
            if (content != null) {
                val newGroups = withContext(Dispatchers.Default) { ChannelGrouper.group(M3uParser.parse(content)) }
                if (newGroups.isNotEmpty()) {
                    channelGroups = newGroups
                    val gi = ChannelGrouper.findGroupIndexByUrl(newGroups, preparedUrl)
                    if (gi != null) {
                        currentGroupIndex = gi
                        currentVariantIndex = ChannelGrouper.findVariantIndexByUrl(newGroups[gi], preparedUrl)
                    }
                }
            }
        }
        if (token != activeMediaToken) return true
        val group = channelGroups.getOrNull(currentGroupIndex)
        val variants = group?.variants.orEmpty()
        if (variants.size > 1) {
            val start = (currentVariantIndex + 1) % variants.size
            for (offset in variants.indices) {
                val idx = (start + offset) % variants.size
                val ch = variants[idx].channel
                if (ch.url != preparedUrl) {
                    currentVariantIndex = idx
                    pendingCommit = CommitInfo(ch.url, group?.displayTitle ?: ch.title, currentGroupIndex, group?.key ?: ch.url)
                    prepareMediaNow(ch.url)
                    return true
                }
            }
        }
        prepareMediaNow(preparedUrl ?: url)
        return true
    }

    LaunchedEffect(Unit) {
        playbackEvents.collect { evt ->
            val token = evt.token ?: return@collect
            if (!eventRouter.isCurrent(token)) return@collect   // 真实来源不是当前媒体（S02）
            val parsed = MediaToken.parse(token) ?: return@collect
            if (parsed.intent != intentGate.current()) return@collect // 旧意图
            if (!handledTokens.add(token)) return@collect        // 同一媒体实例只处理一次
            when (val decision = attemptState.onMediaError(parsed.attempt)) {
                RecoveryDecision.Ignore -> return@collect
                RecoveryDecision.Terminal -> {
                    pendingFailure = FailureInfo(evt.reason)
                    channelSwitch.hide()
                    return@collect
                }
                is RecoveryDecision.Recover -> {
                    channelSwitch.show()
                    recoveryJob?.cancel()
                    recoveryJob = scope.launch {
                        delay(PlaybackRecoveryPolicy.BACKOFF_MS)
                        // S03：提交前校验意图仍有效（ON_STOP/换台会使其失效）
                        if (!eventRouter.isCurrent(token)) return@launch
                        tryRecoverAlternative(token)
                    }
                }
            }
        }
    }

    // ---------- EPG 状态（S05/S06：单一协调入口 + 结构化回退） ----------
    suspend fun performEpgRefresh(src: String, manual: Boolean, autoPolicy: EpgRefreshPolicy?): Boolean {
        if (!EpgRefreshCoordinator.tryBegin(src)) return false
        try {
            if (autoPolicy != null && !manual) EpgRefreshCoordinator.markAutoAttempted(src, autoPolicy)
            epgRefreshInProgress = true
            if (manual) epgRefreshMessage = "正在更新EPG…"
            if (epgData == null) epgStatus = EpgStatus.LOADING
            AppSettings.setEpgLastAttempt(context, src, System.currentTimeMillis())
            val result = runCatching { EpgRepository.loadStructured(context, src, forceRefresh = true, allowNetwork = true) }
                .getOrElse { EpgLoadResult.Failure(src, it.message) }
            val d = result.dataOrNull
            return when {
                result.isNetworkSuccess && d != null -> {
                    epgData = d
                    epgStatus = if (d.programsByChannelId.isEmpty()) EpgStatus.EMPTY else EpgStatus.READY
                    AppSettings.setEpgLastSuccess(context, src, System.currentTimeMillis())
                    epgLastSuccessText = formatEpoch(AppSettings.getEpgLastSuccess(context, src))
                    if (manual) epgRefreshMessage = "EPG更新成功"
                    true
                }
                d != null -> { // 网络失败回退同源缓存：可浏览但 STALE，不记成功
                    epgData = d
                    epgStatus = EpgStatus.STALE
                    if (manual) epgRefreshMessage = "EPG更新失败，显示上次节目单"
                    false
                }
                else -> {
                    epgStatus = if (epgData == null) EpgStatus.FAILURE else EpgStatus.STALE
                    if (manual) epgRefreshMessage = "EPG更新失败"
                    false
                }
            }
        } finally {
            epgRefreshInProgress = false
            EpgRefreshCoordinator.end(src)
        }
    }

    suspend fun refreshEpgNow(manual: Boolean): Boolean {
        val epgSource = Prefs.getEpgSource(context)
        if (epgSource.isBlank()) { epgStatus = EpgStatus.UNCONFIGURED; return false }
        return performEpgRefresh(epgSource, manual = manual, autoPolicy = null)
    }

    val epgSourceConfigured = Prefs.getEpgSource(context).isNotBlank()
    LaunchedEffect(epgSourceConfigured) {
        if (!epgSourceConfigured) { epgStatus = EpgStatus.UNCONFIGURED; return@LaunchedEffect }
        val src = Prefs.getEpgSource(context)
        val cached = EpgRepository.load(context, src, forceRefresh = false, allowNetwork = false)
        if (cached != null) {
            epgData = cached
            epgStatus = if (cached.programsByChannelId.isEmpty()) EpgStatus.EMPTY else EpgStatus.READY
        } else {
            epgStatus = EpgStatus.LOADING
        }
        epgLastSuccessText = formatEpoch(AppSettings.getEpgLastSuccess(context, src))
        launch {
            BackgroundSourceUpdater.epgUpdated.collect { d ->
                epgData = d
                epgStatus = if (d.programsByChannelId.isEmpty()) EpgStatus.EMPTY else EpgStatus.READY
                epgLastSuccessText = formatEpoch(AppSettings.getEpgLastSuccess(context, src))
            }
        }
        val policy = AppSettings.getEpgRefreshPolicy(context)
        if (policy == EpgRefreshPolicy.MANUAL) {
            if (cached == null) epgStatus = EpgStatus.FAILURE
            return@LaunchedEffect
        }
        val shouldRun = EpgRefreshCoordinator.shouldRunAuto(
            source = src,
            policy = policy,
            lastSuccess = AppSettings.getEpgLastSuccess(context, src),
            lastAttempt = AppSettings.getEpgLastAttempt(context, src),
        )
        if (shouldRun) {
            performEpgRefresh(src, manual = false, autoPolicy = policy)
        }
        // 否则（如被退避暂缓）保持 LOADING，周期循环补做（S06）
    }

    // EPG 周期刷新（6h / 每天首次启动）；策略 MANUAL 不联网；与启动/手动共享协调入口（S06）
    LaunchedEffect(epgSourceConfigured) {
        if (!epgSourceConfigured) return@LaunchedEffect
        while (true) {
            delay(60_000L)
            val src = Prefs.getEpgSource(context)
            if (src.isBlank()) continue
            val policy = AppSettings.getEpgRefreshPolicy(context)
            if (policy == EpgRefreshPolicy.MANUAL) continue
            val shouldRun = EpgRefreshCoordinator.shouldRunAuto(
                source = src,
                policy = policy,
                lastSuccess = AppSettings.getEpgLastSuccess(context, src),
                lastAttempt = AppSettings.getEpgLastAttempt(context, src),
            )
            if (!shouldRun) continue
            performEpgRefresh(src, manual = false, autoPolicy = policy)
        }
    }

    LaunchedEffect(Unit) { while (true) { nowMillis = System.currentTimeMillis(); delay(30_000) } }

    val groups = remember(channelGroups) { ChannelGrouper.sortedDrawerGroups(channelGroups) }
    val filteredChannels = remember(channelGroups, selectedGroup) {
        ChannelGrouper.numberedDisplayChannelsForGroup(channelGroups, selectedGroup)
    }

    LaunchedEffect(epgData, nowMillis, channelGroups, selectedGroup) {
        val data = epgData
        val scoped = if (selectedGroup == "全部") channelGroups else channelGroups.filter { ChannelGrouper.displayGroupName(it) == selectedGroup }
        if (data == null || scoped.isEmpty()) { nowProgramByUrl = emptyMap(); return@LaunchedEffect }
        val map = withContext(Dispatchers.Default) {
            buildMap {
                for (group in scoped) {
                    val displayChannel = group.defaultChannel
                    // R11：Now/Next 独立计算；无当前节目但有后续节目也要展示 Next
                    val current = data.nowProgram(displayChannel, nowMillis)
                    val next = data.nextProgram(displayChannel, nowMillis)
                    if (current == null && next == null) continue
                    val progress = current?.let {
                        val duration = (it.endMillis - it.startMillis).toFloat()
                        if (duration <= 0f) null else ((nowMillis - it.startMillis).toFloat() / duration).coerceIn(0f, 1f)
                    }
                    val ui = NowProgramUi(title = current?.title ?: "", progress = progress, nextTitle = next?.title)
                    for (variant in group.variants) put(variant.channel.url, ui)
                }
            }
        }
        nowProgramByUrl = map
    }

    fun mergeLatency(results: Map<String, Int?>) {
        if (results.isNotEmpty()) measuredLatencyByUrl = measuredLatencyByUrl + results
    }

    fun playGroupVariant(groupIndex: Int, variantIndex: Int) {
        val group = channelGroups.getOrNull(groupIndex) ?: return
        val variant = group.variants.getOrNull(variantIndex) ?: return
        val channel = variant.channel
        catchupRequest = null
        currentGroupIndex = groupIndex
        currentVariantIndex = variantIndex
        startSession(channel.url, null, groupIndex, group.displayTitle, channelKey = group.key)
    }

    fun playGroupAuto(groupIndex: Int) {
        val group = channelGroups.getOrNull(groupIndex) ?: return
        if (group.variants.isEmpty()) return
        val groupKey = group.key // S03：使用稳定频道 key，而非位置序号
        val intent = intentGate.newIntent()  // 与播放会话共用用户意图代次（R01）
        channelSwitch.show()
        currentGroupIndex = groupIndex
        probeJob?.cancel()
        probeJob = scope.launch {
            val urls = group.variants.map { it.channel.url }
            val selected = probeCoordinator.selectFirstReachable(urls, intent) { u ->
                if (SourceLatencyProber.hasFreshCache(u)) {
                    val ms = SourceLatencyProber.cachedLatencyMs(u)
                    if (ms != null) mergeLatency(mapOf(u to ms))
                    ms
                } else {
                    probingUrls = probingUrls + u
                    val ms = SourceLatencyProber.probe(u)
                    mergeLatency(mapOf(u to ms))
                    probingUrls = probingUrls - u
                    probedUrls = probedUrls + u
                    ms
                }
            }
            if (selected == null) return@launch // 意图已失效：不得覆盖用户更新后的选择
            if (!intentGate.isCurrent(intent)) return@launch
            // S03：提交前校验生命周期仍 STARTED，且按稳定 key 重新解析目标组
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            val freshIndex = channelGroups.indexOfFirst { it.key == groupKey }
            if (freshIndex < 0) return@launch
            val variantCount = channelGroups[freshIndex].variants.size
            if (variantCount == 0) return@launch
            playGroupVariant(freshIndex, selected.coerceIn(0, variantCount - 1))
            probeJob = null
        }
    }

    fun playChannel(channel: Channel) {
        channelGroups.forEachIndexed { gi, group ->
            if (group.defaultChannel.url == channel.url || group.variants.any { it.channel.url == channel.url }) {
                playGroupAuto(gi); return
            }
        }
    }

    fun playChannelByUrl(targetUrl: String): Boolean {
        val gi = ChannelGrouper.findGroupIndexByUrl(channelGroups, targetUrl) ?: return false
        val group = channelGroups[gi]
        val vi = ChannelGrouper.findVariantIndexByUrl(group, targetUrl)
        playGroupVariant(gi, vi)
        return true
    }

    // 回看 ENDED 后自动回直播（D09）
    LaunchedEffect(endedCatchupLiveUrl) {
        val liveUrl = endedCatchupLiveUrl ?: return@LaunchedEffect
        endedCatchupLiveUrl = null
        if (!playChannelByUrl(liveUrl)) startSession(liveUrl, null, currentGroupIndex, currentChannel?.title)
        infoBannerOpen = true
        Toast.makeText(context, "节目已结束，已返回直播", Toast.LENGTH_SHORT).show()
    }

    fun returnToLiveFromCatchup(): Boolean {
        val liveUrl = catchupRequest?.liveUrl ?: return false
        catchupRequest = null
        if (!playChannelByUrl(liveUrl)) {
            startSession(liveUrl, null, currentGroupIndex, currentChannel?.title)
        }
        infoBannerOpen = true
        return true
    }

    fun refreshGroupLatencies(group: ChannelGroup?, force: Boolean = false) {
        val urls = group?.variants?.map { it.channel.url }.orEmpty()
        if (urls.isEmpty()) return
        probingUrls = probingUrls + urls
        scope.launch {
            mergeLatency(SourceLatencyProber.probeAll(urls, force = force))
            probingUrls = probingUrls - urls.toSet()
            probedUrls = probedUrls + urls.toSet() // R11：探测完成后标记已测，区分未测/不可用
        }
    }

    fun playingUrlLive(): String? =
        channelGroups.getOrNull(currentGroupIndex)?.variants?.getOrNull(currentVariantIndex)?.channel?.url

    fun playingGroupNameLive(): String {
        val url = playingUrlLive()
        val gi = ChannelGrouper.findGroupIndexByUrl(channelGroups, url) ?: currentGroupIndex
        return channelGroups.getOrNull(gi)?.let { ChannelGrouper.displayGroupName(it) } ?: "全部"
    }

    val channelGroupsState = rememberUpdatedState(channelGroups)
    val currentGroupIndexState = rememberUpdatedState(currentGroupIndex)
    val playGroupAutoState = rememberUpdatedState<(Int) -> Unit> { index -> playGroupAuto(index) }

    DisposableEffect(player) {
        setOnCatchupSeek { deltaMs ->
            val p = player ?: return@setOnCatchupSeek false
            val req = catchupRequest ?: return@setOnCatchupSeek false
            if (!p.isCurrentMediaItemSeekable) return@setOnCatchupSeek false
            val total = (req.endMillis - req.startMillis).coerceAtLeast(1L)
            val target = (p.currentPosition + deltaMs).coerceIn(0L, total)
            p.seekTo(target)
            catchupPositionMs = target
            infoBannerOpen = true
            true
        }
        setOnReturnToLive { returnToLiveFromCatchup() }
        onDispose { setOnCatchupSeek(null); setOnReturnToLive(null) }
    }

    DisposableEffect(player) {
        setOnChannelStep { direction ->
            val snapshot = channelGroupsState.value
            if (snapshot.size < 2) return@setOnChannelStep false
            val size = snapshot.size
            val next = ((currentGroupIndexState.value + direction) % size + size) % size
            playGroupAutoState.value(next)
            infoBannerOpen = true
            true
        }
        onDispose { setOnChannelStep(null) }
    }

    DisposableEffect(player) {
        setOnNumberJump { number ->
            val snapshot = channelGroupsState.value
            val idx = number - 1
            if (idx !in snapshot.indices) false
            else { playGroupAutoState.value(idx); infoBannerOpen = true; true }
        }
        setOnResolveNumber { number -> channelGroupsState.value.getOrNull(number - 1)?.let { EpgNormalize.displayName(it.displayTitle) } }
        setOnChannelCount { channelGroupsState.value.size }
        setOnPreviousWatched {
            val prev = previousChannel ?: return@setOnPreviousWatched false
            playChannelByUrl(prev.first)
        }
        setOnOkOverlay {
            if (failureRetained && !playbackFailed) { playbackFailed = true; failureRetained = false; true } else false
        }
        setOnOpenChannels {
            val playing = playingUrlLive()
            val filtered = ChannelGrouper.numberedDisplayChannelsForGroup(channelGroups, selectedGroup)
            if (playing != null && filtered.none { it.channel.url == playing }) selectedGroup = playingGroupNameLive()
            requestedChannelUrl = null
            requestedEntryGuide = false
            requestedLayer = NavLayer.Channels
            channelPanelOpen = true
        }
        setOnOpenPrograms {
            // R06：Guide 直接定位当前直播频道，而非上次浏览频道
            selectedGroup = playingGroupNameLive()
            requestedChannelUrl = playingUrlLive()
            requestedEntryGuide = true
            requestedLayer = NavLayer.Programs
            channelPanelOpen = true
        }
        onDispose {
            setOnNumberJump(null)
            setOnResolveNumber(null)
            setOnChannelCount(null)
            setOnPreviousWatched(null)
            setOnOkOverlay(null)
            setOnOpenChannels(null)
            setOnOpenPrograms(null)
        }
    }

    val programClickMode = remember(settingsEpoch) { AppSettings.getProgramClickMode(context) }
    val multiSourceEnabled = (playingGroup?.variants?.size ?: 0) > 1

    val sourceVariants = remember(playingGroup, playingVariantIndex, measuredLatencyByUrl, probingUrls) {
        playingGroup?.variants?.mapIndexed { index, variant ->
            SourceVariantUi(
                label = variant.qualityLabel,
                latencyMs = measuredLatencyByUrl[variant.channel.url] ?: SourceLatencyProber.cachedLatencyMs(variant.channel.url),
                probing = variant.channel.url in probingUrls,
                probed = variant.channel.url in probedUrls,
                selected = index == playingVariantIndex,
            )
        }.orEmpty()
    }

    // 打开设置时触发线路延迟探测（D14）
    LaunchedEffect(settingsOpen) {
        if (settingsOpen && multiSourceEnabled) refreshGroupLatencies(playingGroup, force = false)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelWidth = computePanelWidth(maxWidth)

        if (player != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        PlayerView(it).apply {
                            this.player = player
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            setKeepContentOnPlayerReset(true)
                            setShutterBackgroundColor(Color.TRANSPARENT)
                            keepScreenOn = true
                            isFocusable = false
                            isFocusableInTouchMode = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        }
                    },
                    update = { it.player = player },
                )
                ChannelSwitchOverlay(controller = channelSwitch, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp))
            }
        }

        ChannelPanel(
            visible = channelPanelOpen,
            panelWidth = panelWidth,
            groups = groups,
            selectedGroup = selectedGroup,
            channels = filteredChannels,
            playingUrl = currentChannel?.url,
            epgData = epgData,
            epgStatus = epgStatus,
            epgLastSuccessText = epgLastSuccessText,
            nowMillis = nowMillis,
            nowProgramByUrl = nowProgramByUrl,
            requestedLayer = requestedLayer,
            requestedChannelUrl = requestedChannelUrl,
            requestedEntryGuide = requestedEntryGuide,
            programClickMode = programClickMode,
            onLayerChanged = { layer -> if (requestedLayer == layer) requestedLayer = null },
            onSelectGroup = { group -> selectedGroup = group },
            onSelectChannel = { channel -> playChannel(channel) },
            onPlayProgram = { req ->
                val gi = ChannelGrouper.findGroupIndexByUrl(channelGroups, req.liveUrl)
                if (gi != null) { currentGroupIndex = gi; currentVariantIndex = ChannelGrouper.findVariantIndexByUrl(channelGroups[gi], req.liveUrl) }
                catchupRequest = req
                startSession(req.catchupUrl, req, gi ?: currentGroupIndex, req.programTitle, channelKey = channelGroups.getOrNull(gi ?: currentGroupIndex)?.key)
                channelPanelOpen = false
            },
            onNeedEpgConfig = { channelPanelOpen = false; settingsOpen = true },
            onRetryEpg = { scope.launch { refreshEpgNow(manual = true) } },
            onOpenSettings = { channelPanelOpen = false; settingsOpen = true },
            onClose = { channelPanelOpen = false },
        )

        SettingsDrawer(
            visible = settingsOpen,
            panelWidth = panelWidth,
            currentChannelTitle = currentChannel?.title,
            previousChannelTitle = previousChannel?.second,
            catchupActive = catchupRequest != null,
            multiSourceEnabled = multiSourceEnabled,
            sourceVariants = sourceVariants,
            videoInfoLines = videoInfoLines(videoFormat, playingGroup?.displayTitle, player),
            lastPlaylistSuccessText = formatEpoch(AppSettings.getPlaylistLastSuccess(context)),
            lastEpgSuccessText = epgLastSuccessText,
            epgRefreshMessage = epgRefreshMessage,
            playlistRefreshMessage = playlistRefreshMessage,
            onClose = { settingsOpen = false },
            onSourceConfig = { settingsOpen = false; context.startActivity(Intent(context, MainActivity::class.java)) },
            onPreviousChannel = { previousChannel?.let { playChannelByUrl(it.first) }; settingsOpen = false },
            onReturnToLive = { returnToLiveFromCatchup(); settingsOpen = false },
            onRefreshPlaylist = {
                if (!playlistRefreshInProgress) {
                    playlistRefreshInProgress = true
                    playlistRefreshMessage = "正在刷新列表…"
                    scope.launch {
                        val ok = runCatching { BackgroundSourceUpdater.refreshPlaylistUrgent(context) }.getOrDefault(false)
                        if (ok) {
                            val fn = Prefs.getPlaylistFileName(context)
                            val content = if (fn != null) withContext(Dispatchers.IO) { PlaylistCache.read(context, fn) } else null
                            if (content != null) applyPlaylistContent(content, preserveCurrentUrl = preparedUrl ?: url, mode = PlaylistUpdateMode.Background)
                            playlistRefreshMessage = "列表已更新 " + (formatEpoch(System.currentTimeMillis()) ?: "")
                        } else {
                            playlistRefreshMessage = "更新失败，已保留原列表"
                        }
                        playlistRefreshInProgress = false
                        Toast.makeText(context, playlistRefreshMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onRefreshEpg = { scope.launch { refreshEpgNow(manual = true) } },
            onSelectSource = { idx -> if (playingGroup != null) playGroupVariant(playingGroupIndex, idx) },
            onSelectAutoSource = { playGroupAuto(playingGroupIndex) },
            onCheckUpdate = {
                settingsOpen = false
                Toast.makeText(context, "正在检查更新...", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val info = UpdateChecker.checkUpdateAndGetInfo(context)
                    if (info != null && info.hasUpdate) { updateInfo = info; showUpdateDialog = true }
                    else Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
            },
        )

        if (showUpdateDialog && updateInfo != null) {
            UpdateDialog(updateInfo = updateInfo!!, onDismiss = { showUpdateDialog = false; updateInfo = null })
        }

        PlaybackFailurePanel(
            visible = playbackFailed,
            panelWidth = panelWidth,
            channelTitle = currentChannel?.title,
            reason = failureReason,
            catchupActive = catchupRequest != null,
            onRetry = {
                val target = catchupRequest?.catchupUrl ?: preparedUrl ?: currentChannel?.url
                attemptState.newSession()
                playbackFailed = false
                failureRetained = false
                failureReason = null
                recoveryJob?.cancel()
                intentGate.newIntent()
                if (target != null) {
                    sessionId += 1
                    prepareMediaNow(target)
                } else {
                    // R08：没有可用缓存/频道时，重试=手动刷新列表
                    scope.launch {
                        pendingFailure = null
                        val ok = runCatching { BackgroundSourceUpdater.refreshPlaylistUrgent(context) }.getOrDefault(false)
                        val fn = Prefs.getPlaylistFileName(context)
                        val content = if (ok && fn != null) withContext(Dispatchers.IO) { PlaylistCache.read(context, fn) } else null
                        if (content != null) {
                            applyPlaylistContent(content, preserveCurrentUrl = url, mode = PlaylistUpdateMode.Initial, restoreLast = restoreLastChannel)
                        } else {
                            pendingFailure = FailureInfo("没有可用缓存，请检查网络或重新配置源")
                        }
                    }
                }
            },
            onReturnLive = {
                playbackFailed = false
                failureRetained = false
                failureReason = null
                returnToLiveFromCatchup()
            },
            onSelectChannel = { playbackFailed = false; requestedLayer = NavLayer.Channels; channelPanelOpen = true },
            onSourceConfig = { playbackFailed = false; context.startActivity(Intent(context, MainActivity::class.java)) },
            onDismiss = { playbackFailed = false; failureRetained = true },
        )

        PlaybackFailureStatusBar(
            visible = failureRetained && !playbackFailed,
            channelTitle = currentChannel?.title,
            modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
        )

        NumberInputOverlay(controller = numberBuffer, modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 48.dp))

        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            BannerHost(
                infoBannerOpen = infoBannerOpen,
                currentChannel = currentChannel,
                channelNumber = if (catchupRequest != null) {
                    val liveUrl = catchupRequest?.liveUrl
                    val idx = channelGroups.indexOfFirst { g -> g.variants.any { it.channel.url == liveUrl } }
                    if (idx >= 0) idx + 1 else 0
                } else ChannelGrouper.globalNumberOfGroup(channelGroups, currentGroupIndex),
                catchupRequest = catchupRequest,
                catchupPositionMs = catchupPositionMs,
                epgData = epgData,
                epgConfigured = Prefs.getEpgSource(context).isNotBlank(),
                nowMillis = nowMillis,
                videoFormat = videoFormat,
                allChannels = allChannels,
                playingGroup = playingGroup,
            )
        }
    }
}

private val epochFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatEpoch(millis: Long): String? {
    if (millis <= 0L) return null
    return runCatching { epochFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())) }.getOrNull()
}

private fun videoInfoLines(format: Format?, lineName: String?, player: ExoPlayer?): List<Pair<String, String>> {
    val lines = mutableListOf<Pair<String, String>>()
    val w = format?.width ?: 0
    val h = format?.height ?: 0
    if (w > 0 && h > 0) lines.add("分辨率" to "${w}x$h")
    format?.sampleMimeType?.takeIf { it.isNotBlank() }?.let { lines.add("格式" to it) }
    lineName?.takeIf { it.isNotBlank() }?.let { lines.add("当前线路" to it) }
    val stateText = when (player?.playbackState) {
        Player.STATE_READY -> "播放中"
        Player.STATE_BUFFERING -> "缓冲中"
        Player.STATE_ENDED -> "已结束"
        Player.STATE_IDLE -> "空闲"
        else -> "未知"
    }
    lines.add("播放状态" to stateText)
    return lines
}

@Composable
private fun BannerHost(
    infoBannerOpen: Boolean,
    currentChannel: Channel?,
    channelNumber: Int,
    catchupRequest: CatchupPlayRequest?,
    catchupPositionMs: Long,
    epgData: EpgData?,
    epgConfigured: Boolean,
    nowMillis: Long,
    videoFormat: Format?,
    allChannels: List<Channel>,
    playingGroup: ChannelGroup?,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Bottom) {
        val displayChannel = if (catchupRequest != null) allChannels.firstOrNull { it.url == catchupRequest.liveUrl } else currentChannel
        val bannerChannel = displayChannel?.let { ch ->
            val cleanTitle = playingGroup?.displayTitle ?: EpgNormalize.displayName(ch.title)
            if (cleanTitle == ch.title) ch else ch.copy(title = cleanTitle)
        }
        val nowProg = epgData?.nowProgram(displayChannel ?: return@Column, nowMillis)
        val nextProg = epgData?.nextProgram(displayChannel ?: return@Column, nowMillis)
        val epgState = when {
            !epgConfigured -> BannerEpgState.NOT_CONFIGURED
            epgData == null -> BannerEpgState.NO_DATA
            displayChannel != null && !epgData.hasProgramsFor(displayChannel) -> BannerEpgState.NO_DATA
            else -> BannerEpgState.CONFIGURED
        }
        val catchupConfig = catchupRequest?.let { CatchupConfiguration(it.startMillis, it.endMillis, catchupPositionMs) }
        ChannelInfoBanner(
            visible = infoBannerOpen,
            channel = bannerChannel,
            channelNumber = channelNumber,
            nowTitle = if (catchupRequest != null) catchupRequest.programTitle else nowProg?.title,
            nowTimeText = if (catchupRequest != null) null else nowProg?.let { formatBannerTime(it.startMillis, it.endMillis) },
            nowProgress = if (catchupRequest != null) null else nowProg?.let {
                val duration = (it.endMillis - it.startMillis).toFloat()
                if (duration <= 0f) null else ((nowMillis - it.startMillis).toFloat() / duration).coerceIn(0f, 1f)
            },
            nextTitle = if (catchupRequest != null) null else nextProg?.title,
            nextTimeText = if (catchupRequest != null) null else nextProg?.let { formatBannerTime(it.startMillis, it.endMillis) },
            epgState = epgState,
            videoFormat = videoFormat,
            catchupConfiguration = catchupConfig,
            catchupActive = catchupRequest != null,
            animate = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val bannerFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatBannerTime(startMillis: Long, endMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val start = bannerFormatter.format(Instant.ofEpochMilli(startMillis).atZone(zone))
    val end = bannerFormatter.format(Instant.ofEpochMilli(endMillis).atZone(zone))
    return "$start-$end"
}

private fun displayChannelsFromGroups(groups: List<ChannelGroup>): List<Channel> = ChannelGrouper.displayChannels(groups)

private fun buildPrefetchLogoUrls(channels: List<Channel>, currentIndex: Int): List<String> {
    if (channels.isEmpty()) return emptyList()
    val maxUrls = 220
    val out = ArrayList<String>(minOf(maxUrls, channels.size))
    val seen = HashSet<String>(minOf(maxUrls, channels.size))
    fun addAt(index: Int) {
        if (out.size >= maxUrls) return
        val u = channels.getOrNull(index)?.logoUrl?.trim().orEmpty()
        if (u.isNotBlank() && seen.add(u)) out.add(u)
    }
    addAt(currentIndex)
    for (delta in 1..8) { addAt(currentIndex - delta); addAt(currentIndex + delta) }
    val head = minOf(120, channels.size)
    for (i in 0 until head) addAt(i)
    return out
}
