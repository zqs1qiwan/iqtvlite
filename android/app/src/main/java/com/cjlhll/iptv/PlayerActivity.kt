package com.cjlhll.iptv

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import tv.iqtvlite.app.R
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as CoroutineChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class PlaylistUpdateMode {
    Initial,
    Background,
    Recovery,
}

/**
 * 重构后的播放器 Activity：
 * - 零拷贝渲染底座（原生 FrameLayout 底层 SurfaceView PlayerView + 顶层透明 ComposeView）完全保留；
 * - 覆盖层状态统一收口到 [uiController]（StateFlow 单源真理，彻底消灭异步 Boolean 镜像竞态）；
 * - 遥控输入统一经中央路由分发，正确区分短按/长按、自定义全映射无死角、弹窗零穿透；
 * - 任意抽屉列操作均刷新无操作计时器，并提供确定性兜底按键闭环，绝不失焦死锁。
 */
class PlayerActivity : ComponentActivity() {
    private val uiController = PlayerUiController()
    private val sidebarController = SidebarController()

    private var onChannelStep: ((Int) -> Boolean)? = null
    private var onNumberJump: ((Int) -> Boolean)? = null
    private var onDigitMatchJump: ((Int) -> Boolean)? = null
    private var digitMatchCount: Int = 0
    private var digitMatchSelectedIndex by mutableIntStateOf(0)
    private var activeChannelGroups by mutableStateOf<List<ChannelGroup>>(emptyList())
    private var onPlayLastChannel: (() -> Boolean)? = null
    private var onCatchupSeek: ((Long) -> Boolean)? = null
    private var onReturnToLive: (() -> Boolean)? = null
    private var onOpenInfoCardFromOk: (() -> Boolean)? = null
    private var onInfoCardFocusedAction: ((InfoCardPolicy.FocusedKeyAction) -> Boolean)? = null
    private var onExtraBack: (() -> Boolean)? = null
    private var subtitleDialogOpenFlag: Boolean = false
    private var isInPipMode by mutableStateOf(false)
    private var isInAppPipActive by mutableStateOf(false)
    private var onSwapPip: (() -> Boolean)? = null
    private var onExitPip: (() -> Unit)? = null
    private lateinit var nativeDrawer: NativeChannelDrawerView

    private var isCatchupPlayback: Boolean = false
    private var lastBackPressTime: Long = 0L
    private var lastBackPressTimeCatchup: Long = 0L
    private var lastBackPressTimePip: Long = 0L

    private var digitsMode: RemoteHabits.Digits = RemoteHabits.DEFAULT.digits
    private var remoteBindings by mutableStateOf(RemoteBindings.DEFAULT)

    private var numberJob: Job? = null
    private var numberConfirmJob: Job? = null

    // 真·长短按分离状态机（仅当按键绑定了长按动作时延迟短按至 ACTION_UP，未绑长按则 ACTION_DOWN 0ms 响应）
    private var pendingLongPressKeyCode: Int? = null
    private var longPressFiredForKey: Int? = null
    private var longPressJob: Job? = null

    override fun onResume() {
        super.onResume()
        AppStrings.set(Prefs.getAppLanguage(this))
        digitsMode = Prefs.getRemoteSettings(this).digits
        remoteBindings = Prefs.getRemoteBindings(this)
    }

    override fun onStop() {
        cancelNumberTimers()
        cancelLongPress()
        uiController.clearDigits()
        super.onStop()
    }

    private fun cancelNumberTimers() {
        numberJob?.cancel()
        numberJob = null
        numberConfirmJob?.cancel()
        numberConfirmJob = null
    }

    private fun cancelLongPress() {
        longPressJob?.cancel()
        longPressJob = null
        pendingLongPressKeyCode = null
        longPressFiredForKey = null
    }

    private fun restartDigitTimeoutTimer() {
        numberJob?.cancel()
        val mode = digitsMode
        numberJob = lifecycleScope.launch {
            delay(DigitOsdPolicy.TIMEOUT_MS)
            when (DigitOsdPolicy.timeoutAction(mode)) {
                DigitOsdPolicy.TimeoutAction.SUBMIT -> submitNumber()
                DigitOsdPolicy.TimeoutAction.CLEAR -> uiController.clearDigits()
            }
        }
    }

    private fun submitNumber() {
        numberJob?.cancel()
        numberJob = null
        val typed = uiController.currentDigits.digits
        if (typed.isEmpty()) return
        val number = typed.toIntOrNull()
        // 修复 P1-2：先切为蓝色确认态并保留 220ms 让用户明确看到确认反馈，再清空隐藏 OSD。
        uiController.markDigitsConfirmed()
        val jumped = onDigitMatchJump?.invoke(digitMatchSelectedIndex) == true ||
            (number != null && onNumberJump?.invoke(number) == true)
        if (!jumped) {
            Toast.makeText(
                this,
                AppStrings.tr("没有频道 $typed", "Channel $typed not found"),
                Toast.LENGTH_SHORT,
            ).show()
        }
        numberConfirmJob?.cancel()
        numberConfirmJob = lifecycleScope.launch {
            delay(220L)
            uiController.clearDigits()
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStrings.set(Prefs.getAppLanguage(this))
        digitsMode = Prefs.getRemoteSettings(this).digits
        remoteBindings = Prefs.getRemoteBindings(this)

        val videoUrl = intent.getStringExtra("VIDEO_URL") ?: ""
        val videoTitle = intent.getStringExtra("VIDEO_TITLE") ?: "Live"
        val playlistFileName = intent.getStringExtra("PLAYLIST_FILE_NAME") ?: Prefs.getPlaylistFileName(this)

        // 零拷贝底座红线：PlayerView(SurfaceView) 必须为 rootLayout 直接子 View，上层透明 ComposeView 绘制全部 UI。
        val rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val videoView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(Color.TRANSPARENT)
            resizeMode = Prefs.getVideoAspectMode(this@PlayerActivity).resizeModeValue
            keepScreenOn = true
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        rootLayout.addView(
            videoView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // 应用内双台画中画小窗（TextureView，居中在主画面之上、抽屉与 Compose 之下，零 Z-order 穿透）
        val density = resources.displayMetrics.density
        val pipPad = (2 * density).toInt()
        val pipContainer = FrameLayout(this).apply {
            setBackgroundColor(0xD90A0F18.toInt())
            setPadding(pipPad, pipPad, pipPad, pipPad)
            visibility = View.GONE
            isFocusable = false
            isFocusableInTouchMode = false
        }
        val pipVideoView = (LayoutInflater.from(this).inflate(R.layout.view_pip_player, pipContainer, false) as PlayerView).apply {
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val pipLabelView = TextView(this).apply {
            setTextColor(0xFFF0F4FA.toInt())
            setBackgroundColor(0x99000000.toInt())
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
        }
        pipContainer.addView(
            pipVideoView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        pipContainer.addView(
            pipLabelView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                val m = (6 * density).toInt()
                setMargins(m, m, m, m)
            }
        )
        rootLayout.addView(
            pipContainer,
            FrameLayout.LayoutParams((304 * density).toInt(), (171 * density).toInt()).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (20 * density).toInt()
                marginEnd = (24 * density).toInt()
            }
        )
        // 原生 RecyclerView 60fps 选台抽屉：直接挂在 rootLayout（PlayerView 之上、ComposeView 之下），
        // 零 Compose diff / 零重组 / 0px 焦点恒定居中。
        nativeDrawer = NativeChannelDrawerView(this)
        rootLayout.addView(
            nativeDrawer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val composeOverlay = ComposeView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        rootLayout.addView(
            composeOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(rootLayout)

        // 自动收起定时器放在 Activity lifecycleScope 中，独立监听时间戳流，彻底消除 Compose 整屏重组。
        lifecycleScope.launch {
            uiController.drawerLastInteractionMs.collectLatest { lastMs ->
                if (!uiController.isDrawerOpen || lastMs <= 0L) return@collectLatest
                while (uiController.isDrawerOpen) {
                    val now = System.currentTimeMillis()
                    val interactionAt = uiController.currentDrawerInteractionMs
                    if (PerfPolicy.shouldAutoCloseDrawer(uiController.isDrawerOpen, interactionAt, now)) {
                        closeDrawerSynced()
                        return@collectLatest
                    }
                    delay((PerfPolicy.DRAWER_AUTO_CLOSE_MS - (now - interactionAt)).coerceAtLeast(16L))
                }
            }
        }
        lifecycleScope.launch {
            uiController.infoCardLastInteractionMs.collectLatest { lastMs ->
                if (!uiController.isInfoCardVisible || lastMs <= 0L) return@collectLatest
                while (true) {
                    val cur = uiController.currentOverlay as? PlayerOverlay.InfoCard ?: return@collectLatest
                    val timeoutMs = InfoCardPolicy.autoHideTimeoutMs(cur.manual, cur.focused)
                    val now = System.currentTimeMillis()
                    val interactionAt = uiController.currentInfoCardInteractionMs
                    if (InfoCardPolicy.shouldArmAutoHide(
                            cur.policyState,
                            interactionAt,
                            now,
                            timeoutMs = timeoutMs,
                        )
                    ) {
                        uiController.hideInfoCard()
                        return@collectLatest
                    }
                    delay((timeoutMs - (now - interactionAt)).coerceAtLeast(16L))
                }
            }
        }

        composeOverlay.setContent {
            MaterialTheme(colorScheme = TvDesignTokens.colorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    VideoPlayerScreen(
                        playerView = videoView,
                        nativeDrawer = nativeDrawer,
                        url = videoUrl,
                        title = videoTitle,
                        playlistFileName = playlistFileName,
                        uiController = uiController,
                        sidebarController = sidebarController,
                        playback = PlaybackCallbacks(
                            setOnChannelStep = { onChannelStep = it },
                            setOnNumberJump = { onNumberJump = it },
                            setOnPlayLastChannel = { onPlayLastChannel = it },
                            setOnCatchupSeek = { onCatchupSeek = it },
                            setOnReturnToLive = { onReturnToLive = it },
                            onCatchupPlaybackChanged = { isCatchupPlayback = it },
                            onBack = { finish() },
                            isPipSupported = isPipSupportedOnDevice(),
                            isInPipMode = isInPipMode,
                            onEnterPip = { enterPipMode() },
                            pipPlayerView = pipVideoView,
                            pipContainerView = pipContainer,
                            pipLabelView = pipLabelView,
                            onInAppPipActiveChanged = { isInAppPipActive = it },
                            setOnSwapPip = { onSwapPip = it },
                            setOnExitPip = { onExitPip = it },
                            setOnExtraBack = { onExtraBack = it },
                            onSubtitleDialogOpenChanged = { subtitleDialogOpenFlag = it },
                            digitMatchSelectedIndex = digitMatchSelectedIndex,
                            setOnDigitMatchJump = { onDigitMatchJump = it },
                            onChannelGroupsChanged = { activeChannelGroups = it },
                        ),
                        infoCard = InfoCardCallbacks(
                            setOpenInfoCardFromOkController = { onOpenInfoCardFromOk = it },
                            setInfoCardFocusedActionController = { onInfoCardFocusedAction = it },
                        ),
                        remoteCbs = RemoteCallbacks(
                            onRemoteSettingsChanged = { settings -> digitsMode = settings.digits },
                            remoteBindings = remoteBindings,
                            onBindingsChanged = { bindings ->
                                remoteBindings = bindings
                                Prefs.setRemoteBindings(this@PlayerActivity, bindings)
                            },
                        ),
                    )

                    val digitState by uiController.digitOsd.collectAsState()
                    val digitMatches = remember(digitState.digits, activeChannelGroups) {
                        DigitOsdPolicy.matchChannels(digitState.digits, activeChannelGroups, limit = 6)
                    }
                    LaunchedEffect(digitMatches.size, digitState.digits) {
                        digitMatchCount = digitMatches.size
                        digitMatchSelectedIndex = 0
                    }
                    if (!isInPipMode && digitState.visible) {
                        val osdDigitColor = if (digitState.confirmed) BrandFocus.BlueColor else TvDesignTokens.TextPrimary
                        val stripShape = RoundedCornerShape(14.dp)
                        val uiAlpha = Prefs.uiSurfaceAlpha(Prefs.getUiTransparencyPct(this@PlayerActivity))
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(
                                    start = 32.dp,
                                    end = if (isInAppPipActive) 340.dp else 32.dp,
                                    bottom = 28.dp,
                                )
                                .testTag("digit_osd")
                                .clip(stripShape)
                                .background(ComposeColor(0xFF0A0D12).copy(alpha = uiAlpha.coerceAtLeast(0.76f)))
                                .border(1.dp, ComposeColor.White.copy(alpha = 0.14f), stripShape)
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 88.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ComposeColor.White.copy(alpha = 0.08f))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = digitState.digits,
                                    color = osdDigitColor,
                                    fontSize = 34.sp,
                                    lineHeight = 38.sp,
                                    fontWeight = if (digitState.confirmed) FontWeight.Bold else FontWeight.SemiBold,
                                    letterSpacing = 2.sp,
                                    style = TextStyle(fontFeatureSettings = "tnum"),
                                )
                            }
                            if (digitMatches.isEmpty()) {
                                Text(
                                    text = AppStrings.tr("暂无匹配频道", "No matching channel"),
                                    color = TvDesignTokens.TextSecondary,
                                    fontSize = 15.sp,
                                )
                            } else {
                                val selIdx = digitMatchSelectedIndex.coerceIn(0, digitMatches.lastIndex)
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    digitMatches.forEachIndexed { idx, match ->
                                        val selected = idx == selIdx
                                        val cardShape = RoundedCornerShape(10.dp)
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(cardShape)
                                                .background(
                                                    if (selected) BrandFocus.FocusBgColor
                                                    else ComposeColor.White.copy(alpha = 0.06f)
                                                )
                                                .border(
                                                    width = if (selected) 1.5.dp else 1.dp,
                                                    color = if (selected) BrandFocus.BlueColor else ComposeColor.White.copy(alpha = 0.10f),
                                                    shape = cardShape,
                                                )
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            ChannelLogo(
                                                logoUrl = match.logoUrl,
                                                fallbackTitle = match.title,
                                                width = 32.dp,
                                                height = 24.dp,
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${match.number}  ${match.title}",
                                                    color = if (selected) TvDesignTokens.TextPrimary else TvDesignTokens.TextSecondary,
                                                    fontSize = 14.sp,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isPipSupportedOnDevice(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPipMode() {
        if (!isPipSupportedOnDevice()) return
        uiController.closeAll()
        onExtraBack?.invoke()
        closeDrawerSynced()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Prefs.isHomePipEnabled(this) &&
            isPipSupportedOnDevice() &&
            !isInAppPipActive &&
            !isFinishing &&
            !isDestroyed
        ) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            uiController.closeAll()
            closeDrawerSynced()
        }
    }

    private fun calculateSeekStep(repeatCount: Int): Long = when {
        repeatCount == 0 -> 10_000L
        repeatCount < 15 -> 30_000L
        repeatCount < 40 -> 60_000L
        else -> 300_000L
    }

    private fun playerContext(): UiContext =
        if (isCatchupPlayback) UiContext.CATCHUP else UiContext.PLAYER_ONLY

    private fun openDrawerSynced() {
        sidebarController.openDrawer()
        uiController.openDrawer()
    }

    private fun closeDrawerSynced() {
        sidebarController.closeDrawer()
        uiController.closeDrawer()
    }

    private fun handleCapture(event: KeyEvent): Boolean {
        val req = uiController.currentCapture ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            uiController.cancelCapture()
            return true
        }
        val updated = RemoteBindings.bind(remoteBindings, req.first, event.keyCode, req.second)
        remoteBindings = updated
        Prefs.setRemoteBindings(this, updated)
        uiController.cancelCapture()
        val lang = AppStrings.current
        val modeLabel = if (req.second) {
            AppStrings.tr(lang, "长按", "long press")
        } else {
            AppStrings.tr(lang, "短按", "short press")
        }
        val keyText = RemoteBindings.keyLabel(event.keyCode, lang)
        val actText = req.first.localizedLabel(lang)
        Toast.makeText(
            this,
            AppStrings.tr(lang, "已将 [$keyText] 设为「$actText」$modeLabel", "Bound [$keyText] to \"$actText\" ($modeLabel)"),
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    /** 统一动作执行器：任何键值（默认键或自定义键、短按或长按）均走同一入口，零固定键死角。 */
    private fun executePlayerAction(action: RemoteAction, repeatCount: Int): Boolean {
        return when (action) {
            RemoteAction.CHANNEL_UP -> onChannelStep?.invoke(-1) == true
            RemoteAction.CHANNEL_DOWN -> onChannelStep?.invoke(1) == true
            RemoteAction.OPEN_DRAWER -> {
                openDrawerSynced()
                true
            }
            RemoteAction.OPEN_SETTINGS -> {
                uiController.openSettings()
                true
            }
            RemoteAction.SHOW_INFO -> {
                val cur = uiController.currentOverlay
                if (cur is PlayerOverlay.InfoCard) {
                    if (!cur.focused) {
                        onOpenInfoCardFromOk?.invoke() ?: uiController.showInfoCard(focused = false, manual = true)
                    } else {
                        uiController.noteInfoCardInteraction(promoteManual = true)
                    }
                } else {
                    if (onOpenInfoCardFromOk?.invoke() != true) {
                        uiController.showInfoCard(focused = false, manual = true)
                    }
                }
                true
            }
            RemoteAction.RECENT_CHANNELS -> {
                uiController.openRecentDialog()
                true
            }
            RemoteAction.LAST_CHANNEL -> {
                if (repeatCount == 0) onPlayLastChannel?.invoke() == true else true
            }
            RemoteAction.RETURN_LIVE -> {
                if (isCatchupPlayback) onReturnToLive?.invoke() == true else false
            }
            RemoteAction.SEEK_BACK -> {
                val step = calculateSeekStep(repeatCount)
                onCatchupSeek?.invoke(-step) == true
            }
            RemoteAction.SEEK_FORWARD -> {
                val step = calculateSeekStep(repeatCount)
                onCatchupSeek?.invoke(step) == true
            }
            RemoteAction.EXIT_APP -> {
                finish()
                true
            }
            else -> false
        }
    }

    private fun handleBackKey(): Boolean {
        if (onExtraBack?.invoke() == true) {
            return true
        }
        val nowMs = System.currentTimeMillis()
        val within2s = when {
            isInAppPipActive -> nowMs - lastBackPressTimePip <= 2000L
            isCatchupPlayback -> nowMs - lastBackPressTimeCatchup <= 2000L
            else -> nowMs - lastBackPressTime <= 2000L
        }
        when (uiController.decideBack(catchup = isCatchupPlayback, lastBackWithin2s = within2s, inAppPipActive = isInAppPipActive)) {
            BackKeyPolicy.Action.CLOSE_UPDATE_DIALOG -> uiController.closeUpdateDialog()
            BackKeyPolicy.Action.CLOSE_AUDIO_DIALOG -> uiController.closeAudioDialog()
            BackKeyPolicy.Action.CLOSE_RECENT -> uiController.closeRecentDialog()
            BackKeyPolicy.Action.CLOSE_QUALITY_DIALOG -> uiController.closeQualityDialog()
            BackKeyPolicy.Action.CLOSE_DRAWER -> closeDrawerSynced()
            BackKeyPolicy.Action.SETTINGS_BACK_TO_ROOT -> uiController.settingsBackToRoot()
            BackKeyPolicy.Action.CLOSE_SETTINGS -> uiController.closeSettings()
            BackKeyPolicy.Action.INFO_CARD_UNFOCUS -> uiController.unfocusInfoCard()
            BackKeyPolicy.Action.INFO_CARD_CLOSE -> uiController.hideInfoCard()
            BackKeyPolicy.Action.TOAST_EXIT_PIP -> {
                lastBackPressTimePip = nowMs
                Toast.makeText(this, AppStrings.tr("再按一次返回退出画中画", "Press Back again to exit PiP"), Toast.LENGTH_SHORT).show()
            }
            BackKeyPolicy.Action.EXIT_PIP -> {
                onExitPip?.invoke()
                lastBackPressTimePip = 0L
            }
            BackKeyPolicy.Action.TOAST_RETURN_LIVE -> {
                lastBackPressTimeCatchup = nowMs
                Toast.makeText(this, AppStrings.tr("再按一次返回直播", "Press Back again to return live"), Toast.LENGTH_SHORT).show()
            }
            BackKeyPolicy.Action.RETURN_LIVE -> {
                onReturnToLive?.invoke()
                lastBackPressTimeCatchup = 0L
            }
            BackKeyPolicy.Action.TOAST_EXIT -> {
                if (!Prefs.isDoubleBackExitEnabled(this)) {
                    finish()
                } else {
                    lastBackPressTime = nowMs
                    Toast.makeText(this, AppStrings.tr("再按一次退出app", "Press Back again to exit"), Toast.LENGTH_SHORT).show()
                }
            }
            BackKeyPolicy.Action.EXIT_APP -> finish()
        }
        return true
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // 0. 设置页按键捕获态优先（消费 DOWN 与 UP，绝不执行原动作）
        if (uiController.currentCapture != null) {
            return handleCapture(event)
        }

        // 1. 数字键 ACTION_UP 直接吞掉，防透传
        if (event.action == KeyEvent.ACTION_UP && keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return true
        }

        // 2. 长按延后短按的 ACTION_UP 收口
        if (event.action == KeyEvent.ACTION_UP) {
            if (pendingLongPressKeyCode == keyCode) {
                val firedLong = longPressFiredForKey == keyCode
                cancelLongPress()
                if (!firedLong && !uiController.isBlockingOverlayOpen) {
                    val action = resolveSingleKeyAction(keyCode, longPress = false)
                    if (action != null) {
                        executePlayerAction(action, repeatCount = 0)
                        return true
                    }
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        // 3. 数字 OSD 激活态优先（BACK 取消、←/→ 选匹配台、OK 提交、其余键先清 OSD）
        if (uiController.currentDigits.digits.isNotEmpty() && !uiController.currentDigits.confirmed) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                cancelNumberTimers()
                uiController.clearDigits()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && digitMatchCount > 0) {
                digitMatchSelectedIndex = (digitMatchSelectedIndex - 1).coerceAtLeast(0)
                restartDigitTimeoutTimer()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && digitMatchCount > 0) {
                digitMatchSelectedIndex = (digitMatchSelectedIndex + 1).coerceAtMost((digitMatchCount - 1).coerceAtLeast(0))
                restartDigitTimeoutTimer()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            ) {
                submitNumber()
                return true
            }
            if (keyCode !in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                cancelNumberTimers()
                uiController.clearDigits()
            }
        }

        // 4. BACK 统一单一分层状态机（零副作用、永不透传 finish）
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancelLongPress()
            return handleBackKey()
        }

        // 5. 左侧选台抽屉打开态：刷新无操作计时并直达原生 RecyclerView 抽屉（<0.5ms 同步消费，零重组、零焦锁）
        if (uiController.isDrawerOpen) {
            cancelLongPress()
            uiController.noteDrawerInteraction()
            if (nativeDrawer.handleKeyEvent(keyCode)) return true
            return keyCode in NAV_AND_OK_KEYS
        }

        // 6. 其它阻断型覆盖层（设置 / 线路 / 音轨 / 字幕 / 最近观看 / 更新弹窗）：全部交给浮层消费，绝不透传到底层播放器
        if (uiController.isBlockingOverlayOpen || subtitleDialogOpenFlag) {
            cancelLongPress()
            return super.dispatchKeyEvent(event)
        }

        // 7. 全屏/信息卡态下的数字键 0..9
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (event.repeatCount == 0) {
                numberConfirmJob?.cancel()
                uiController.appendDigit(keyCode - KeyEvent.KEYCODE_0)
                restartDigitTimeoutTimer()
            }
            return true
        }

        // 8. 信息卡 FOCUSED 态：方向键/OK 确定性闭环，已绑 OPEN_SETTINGS 的非方向键（菜单/彩键）放行
        if (uiController.isInfoCardFocused) {
            val resolved = resolveSingleKeyAction(keyCode, longPress = false)
            if (!FocusedKeyPolicy.passthrough(keyCode, resolved) && keyCode in NAV_AND_OK_KEYS) {
                uiController.noteInfoCardInteraction()
                if (super.dispatchKeyEvent(event)) return true
                val isCenter = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                val action = InfoCardPolicy.focusedKeyAction(keyCode, isCenter)
                if (action != InfoCardPolicy.FocusedKeyAction.NONE) {
                    onInfoCardFocusedAction?.invoke(action)
                }
                return true
            }
        }

        // 8b. 主动呼出的控制台空闲态下：按 DOWN 直接进入底部选区，不触发换台
        val curCard = uiController.currentOverlay as? PlayerOverlay.InfoCard
        if (curCard != null && curCard.manual && !curCard.focused && !isCatchupPlayback && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            cancelLongPress()
            onOpenInfoCardFromOk?.invoke()
            return true
        }

        // 9. 遥控上一台专用键（MEDIA_PREVIOUS / LAST_CHANNEL）
        if (keyCode == KeyEvent.KEYCODE_LAST_CHANNEL || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            val bound = RemoteBindings.resolveWith(remoteBindings, keyCode, false, playerContext())
            val action = bound ?: RemoteAction.LAST_CHANNEL
            if (executePlayerAction(action, event.repeatCount)) return true
        }

        // 9b. 应用内画中画态：右键切换主副两个频道，禁开设置快捷键
        if (isInAppPipActive && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            cancelLongPress()
            if (event.repeatCount == 0) onSwapPip?.invoke()
            return true
        }

        // 10. 全屏统一中央键位路由（正确支持短按与长按分离）
        val ctx = playerContext()
        val hasLongBinding = RemoteBindings.hasLongPressBinding(remoteBindings, keyCode, ctx)
        if (hasLongBinding) {
            if (event.repeatCount == 0) {
                cancelLongPress()
                pendingLongPressKeyCode = keyCode
                longPressJob = lifecycleScope.launch {
                    delay(LONG_PRESS_THRESHOLD_MS)
                    if (pendingLongPressKeyCode == keyCode && !uiController.isBlockingOverlayOpen) {
                        longPressFiredForKey = keyCode
                        val longAction = resolveSingleKeyAction(keyCode, longPress = true)
                        if (longAction != null) executePlayerAction(longAction, repeatCount = 1)
                    }
                }
                return true
            } else {
                // 系统 repeat 提前到达且已达长按标志，立即触发长按
                if (event.isLongPress && longPressFiredForKey != keyCode) {
                    longPressJob?.cancel()
                    longPressFiredForKey = keyCode
                    val longAction = resolveSingleKeyAction(keyCode, longPress = true)
                    if (longAction != null) executePlayerAction(longAction, event.repeatCount)
                }
                return true
            }
        } else {
            val action = resolveSingleKeyAction(keyCode, longPress = false)
            if (action != null && executePlayerAction(action, event.repeatCount)) {
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun resolveSingleKeyAction(keyCode: Int, longPress: Boolean): RemoteAction? {
        val resolved = RemoteBindings.resolveWith(remoteBindings, keyCode, longPress, playerContext())
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !longPress) {
            val rightAction = RemoteBindings.resolveRight(
                resolved = resolved,
                selectedGroup = sidebarController.value.selectedGroup,
                inGroupColumn = sidebarController.value.activeColumn == DrawerColumn.Groups ||
                    sidebarController.value.activeColumn == DrawerColumn.Sources,
                rightCustomBound = RemoteBindings.isKeyCustomBound(remoteBindings, RemoteBindings.KEY_DPAD_RIGHT),
            )
            if (rightAction == RemoteAction.OPEN_SETTINGS) {
                val pureFullscreen = !uiController.isBlockingOverlayOpen &&
                    !uiController.isDrawerOpen &&
                    !uiController.isInfoCardVisible &&
                    !uiController.currentDigits.visible &&
                    !subtitleDialogOpenFlag &&
                    !isInAppPipActive &&
                    !isInPipMode
                if (!pureFullscreen) return null
            }
            return rightAction
        }
        return resolved
    }

    companion object {
        private const val LONG_PRESS_THRESHOLD_MS = 450L
        private val NAV_AND_OK_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)
fun VideoPlayerScreen(
    playerView: PlayerView,
    nativeDrawer: NativeChannelDrawerView,
    url: String,
    title: String,
    playlistFileName: String?,
    uiController: PlayerUiController,
    sidebarController: SidebarController,
    playback: PlaybackCallbacks,
    infoCard: InfoCardCallbacks,
    remoteCbs: RemoteCallbacks,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val overlay by uiController.overlay.collectAsState()
    val captureRequest by uiController.captureRequest.collectAsState()
    // 仅订阅分组切换（不订阅高频 focusedChannelUrl），避免上下翻台触发整屏 Compose 重组
    val selectedGroup by remember(sidebarController) {
        sidebarController.state.map { it.selectedGroup }.distinctUntilChanged()
    }.collectAsState(initial = SidebarState.ALL_GROUP)

    val drawerOpen = overlay is PlayerOverlay.Drawer
    val settingsOverlay = overlay as? PlayerOverlay.Settings
    val settingsDrawerOpen = settingsOverlay != null
    val qualityOverlay = overlay as? PlayerOverlay.QualityDialog
    val qualityDialogOpen = qualityOverlay != null
    val audioDialogOpen = overlay is PlayerOverlay.AudioDialog
    val recentChannelsOpen = overlay is PlayerOverlay.RecentDialog
    val infoCardOverlay = overlay as? PlayerOverlay.InfoCard
    val infoCardState = infoCardOverlay?.policyState ?: InfoCardPolicy.State.HIDDEN
    val infoCardFocusIndex = infoCardOverlay?.focusIndex ?: 0

    // 播放器状态 + 软解粘滞标志（修复 P0-8：切离软解频道自动恢复 MediaCodec 硬解）
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playerUsesSoftVideo by remember { mutableStateOf(false) }
    var pipPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var pipGroupIndex by remember { mutableIntStateOf(-1) }
    var pipVariantIndex by remember { mutableIntStateOf(0) }
    var pipChannel by remember { mutableStateOf<Channel?>(null) }
    val inAppPipActive = pipChannel != null
    LaunchedEffect(inAppPipActive) { playback.onInAppPipActiveChanged(inAppPipActive) }

    fun exitInAppPip() {
        val old = pipPlayer
        pipPlayer = null
        playback.pipPlayerView?.player = null
        playback.pipContainerView?.visibility = View.GONE
        playback.pipLabelView?.text = ""
        pipGroupIndex = -1
        pipVariantIndex = 0
        pipChannel = null
        runCatching { old?.release() }
    }
    val rebuildState = remember { arrayOfNulls<MediaPlaybackRequest>(1) }
    val rebuildBusy = remember { booleanArrayOf(false) }

    val channelSwitch = rememberChannelSwitchController()
    val playbackErrorEvents = remember { CoroutineChannel<PlaybackFailure>(CoroutineChannel.BUFFERED) }
    val playbackIntent = remember { PlaybackIntent() }
    var catchupRetryCount by remember { mutableIntStateOf(0) }
    var videoFormat by remember { mutableStateOf<Format?>(null) }

    var measuredLatencyByUrl by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    var probingUrls by remember { mutableStateOf<Set<String>>(emptySet()) }

    var channelGroups by remember { mutableStateOf<List<ChannelGroup>>(emptyList()) }
    var currentGroupIndex by remember { mutableIntStateOf(0) }
    var currentVariantIndex by remember { mutableIntStateOf(0) }
    val triedVariantUrls = remember { mutableSetOf<String>() }

    LaunchedEffect(channelGroups) { sidebarController.setChannelGroups(channelGroups) }
    LaunchedEffect(currentGroupIndex, currentVariantIndex) {
        sidebarController.setSelection(currentGroupIndex, currentVariantIndex)
    }

    var epgData by remember { mutableStateOf<EpgData?>(null) }
    var epgVersion by remember { mutableIntStateOf(0) }
    val epgSourceConfigured = remember(context) { Prefs.getEpgSource(context).isNotBlank() }
    var epgLoadFailed by remember { mutableStateOf(false) }
    var nowProgramByUrl by remember { mutableStateOf<Map<String, NowProgramUi>>(emptyMap()) }

    // 仅在浮层可见时才驱动 nowMillis 与帧率轮询（稳态全屏播放 0 轮询、0 无谓重组）
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val needsClockTick = drawerOpen || infoCardOverlay != null
    LaunchedEffect(needsClockTick) {
        if (!needsClockTick) return@LaunchedEffect
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    var firstFrameRendered by remember { mutableStateOf(false) }
    var inferredFrameRate by remember { mutableStateOf<Float?>(null) }
    val needsFpsPoll = infoCardOverlay != null
    LaunchedEffect(player, needsFpsPoll, currentGroupIndex, currentVariantIndex) {
        if (!needsFpsPoll) return@LaunchedEffect
        while (true) {
            inferredFrameRate = StreamPlayback.inferredFrameRate(player)
            delay(400L)
        }
    }

    // 最近观看缓存
    val recentCache = remember {
        RecentChannelsCache(object : RecentChannelsCache.Storage {
            override fun readEncoded(): String? = Prefs.getRecentChannels(context)
            override fun writeEncoded(encoded: String) = Prefs.setRecentChannels(context, encoded)
            override fun clearEncoded() = Prefs.clearRecentChannels(context)
            override fun isCleared(): Boolean = Prefs.isRecentChannelsCleared(context)
        })
    }
    var recentEntries by remember { mutableStateOf(recentCache.entries) }
    var recentWriteJob by remember { mutableStateOf<Job?>(null) }
    var lastChannelSwitchAt by remember { mutableLongStateOf(0L) }

    var logoPrefetchEnabled by remember { mutableStateOf(Prefs.isLogoPrefetchEnabled(context)) }
    var logoLoadEnabled by remember { mutableStateOf(Prefs.isLogoLoadEnabled(context)) }
    LaunchedEffect(Unit) { LogoLoader.setLogoLoadEnabledRuntime(logoLoadEnabled) }

    val manualEpgRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val requestManualEpg: () -> Unit = { manualEpgRequests.tryEmit(Unit) }

    var audioTracks by remember { mutableStateOf<List<AudioTrackUi.Raw>>(emptyList()) }
    var audioOverrideGroup by remember { mutableIntStateOf(-1) }
    var audioOverrideTrack by remember { mutableIntStateOf(-1) }

    var subtitleTracks by remember { mutableStateOf<List<SubtitleTrackUi.Raw>>(emptyList()) }
    var subtitleDisabled by remember { mutableStateOf(false) }
    var subtitleOverrideGroup by remember { mutableIntStateOf(-1) }
    var subtitleOverrideTrack by remember { mutableIntStateOf(-1) }
    var subtitleDialogOpen by remember { mutableStateOf(false) }

    var videoAspectMode by remember { mutableStateOf(Prefs.getVideoAspectMode(context)) }
    LaunchedEffect(videoAspectMode) { playerView.resizeMode = videoAspectMode.resizeModeValue }
    LaunchedEffect(channelGroups) { playback.onChannelGroupsChanged(channelGroups) }
    LaunchedEffect(subtitleDialogOpen) { playback.onSubtitleDialogOpenChanged(subtitleDialogOpen) }
    LaunchedEffect(overlay) { if (overlay != PlayerOverlay.None) subtitleDialogOpen = false }

    var autoUpdatePlaylist by remember { mutableStateOf(Prefs.getAutoUpdatePlaylist(context)) }
    var autoUpdateEpg by remember { mutableStateOf(Prefs.getAutoUpdateEpg(context)) }
    var autoUpdateTick by remember { mutableIntStateOf(0) }
    var autoCheckUpdate by remember { mutableStateOf(Prefs.isAutoCheckUpdateEnabled(context)) }
    var remoteHabits by remember { mutableStateOf(Prefs.getRemoteSettings(context)) }
    var playbackBufferMin by remember { mutableStateOf(Prefs.getPlaybackBufferMin(context)) }
    var zapPreconnectEnabled by remember { mutableStateOf(Prefs.getZapPreconnectEnabled(context)) }
    var multicastPrewarmEnabled by remember { mutableStateOf(Prefs.getMulticastPrewarmEnabled(context)) }
    var jingcaiExperiment by remember { mutableStateOf(JingcaiExperiment.parse(Prefs.getJingcaiExperiment(context))) }
    var infoOkMode by remember { mutableStateOf(Prefs.getInfoOkMode(context)) }
    var appLanguage by remember { mutableStateOf(Prefs.getAppLanguage(context)) }
    LaunchedEffect(Unit) { AppStrings.set(appLanguage) }
    var uiScale by remember { mutableStateOf(Prefs.getUiScale(context)) }
    var favoriteUrls by remember { mutableStateOf(Prefs.getFavoriteUrls(context)) }
    var groupConfigsVersion by remember { mutableIntStateOf(0) }
    var uiTransparencyPct by remember { mutableIntStateOf(Prefs.getUiTransparencyPct(context)) }
    var infoPanelTimeoutSec by remember { mutableIntStateOf(Prefs.getInfoPanelTimeoutSec(context)) }
    var showInfoOnZap by remember { mutableStateOf(Prefs.isShowInfoOnZap(context)) }
    var showClockInPlayer by remember { mutableStateOf(Prefs.isShowClockInPlayer(context)) }
    var showExactResolution by remember { mutableStateOf(Prefs.isShowExactResolution(context)) }
    var showFavoritesGroup by remember { mutableStateOf(Prefs.isShowFavoritesGroup(context)) }
    var showAllChannelsGroup by remember { mutableStateOf(Prefs.isShowAllChannelsGroup(context)) }
    var sources by remember { mutableStateOf(Prefs.getSources(context)) }
    var activeSourceId by remember { mutableStateOf(Prefs.getActiveSourceId(context)) }
    var globalUserAgent by remember { mutableStateOf(Prefs.getGlobalUserAgent(context)) }
    var globalCustomHeaders by remember { mutableStateOf(Prefs.getGlobalCustomHeaders(context)) }
    val visibleSources = remember(sources) { PlaylistSources.visibleSources(sources) }
    var sourceSwitchJob by remember { mutableStateOf<Job?>(null) }
    var updateJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            sourceSwitchJob?.cancel()
            updateJob?.cancel()
        }
    }

    var catchupRequest by remember { mutableStateOf<CatchupPlayRequest?>(null) }
    var catchupPositionMs by remember { mutableLongStateOf(0L) }
    var isSeekOverlayVisible by remember { mutableStateOf(false) }
    var catchupSeekPingAt by remember { mutableLongStateOf(0L) }
    var inlineUpdateState by remember { mutableStateOf<InlineUpdateState>(InlineUpdateState.Idle) }
    var settingsNotice by remember { mutableStateOf<String?>(null) }
    var settingsNoticeSeq by remember { mutableIntStateOf(0) }
    fun notifySettings(text: String) {
        settingsNotice = text
        settingsNoticeSeq++
    }
    var heavyWorkEnabled by remember { mutableStateOf(false) }
    var logoCacheTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(2500L)
        heavyWorkEnabled = true
    }

    LaunchedEffect(heavyWorkEnabled) {
        if (heavyWorkEnabled && autoCheckUpdate && inlineUpdateState is InlineUpdateState.Idle) {
            val info = runCatching { UpdateChecker.checkUpdateAndGetInfo(context) }.getOrNull()
            if (info?.hasUpdate == true && inlineUpdateState is InlineUpdateState.Idle) {
                inlineUpdateState = InlineUpdateState.Available(info)
            }
        }
    }

    LaunchedEffect(settingsNoticeSeq) {
        if (settingsNotice != null) {
            delay(3000L)
            settingsNotice = null
        }
    }

    LaunchedEffect(catchupRequest) {
        playback.onCatchupPlaybackChanged(catchupRequest != null)
    }

    LaunchedEffect(catchupSeekPingAt) {
        if (catchupSeekPingAt > 0L) {
            isSeekOverlayVisible = true
            delay(1500L)
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
            delay(500L)
        }
    }

    val currentLive = playbackIntent.liveOwner(channelGroups, currentGroupIndex, currentVariantIndex)
    val currentChannel = currentLive.channel
    val allChannels = remember(channelGroups) { ChannelGrouper.allChannels(channelGroups) }
    val playingGroupIndex = currentLive.groupIndex
    val playingGroup = channelGroups.getOrNull(playingGroupIndex)
    val playingVariantIndex = currentLive.variantIndex.coerceAtLeast(0)

    fun selectedLiveNow(): Channel {
        return playbackIntent.liveOwner(channelGroups, currentGroupIndex, currentVariantIndex).channel
            ?: StreamPlayback.restore(context, url, title).let { restored ->
                restored.copy(
                    id = (context as? Activity)?.intent?.getLongExtra("VIDEO_ID", restored.id) ?: restored.id,
                    userAgent = (context as? Activity)?.intent?.getStringExtra("VIDEO_UA") ?: restored.userAgent,
                    referrer = (context as? Activity)?.intent?.getStringExtra("VIDEO_REFERRER") ?: restored.referrer,
                )
            }
    }

    fun liveSelection() = PlaylistSelection(channelGroups, currentGroupIndex, selectedLiveNow())

    fun prepareRequest(p: ExoPlayer, request: MediaPlaybackRequest): PreparedPlayback {
        rebuildState[0] = request
        return StreamPlayback.prepare(
            p, context, request, playbackIntent,
            onFailure = { playbackErrorEvents.trySend(it) },
        )
    }

    fun initializePlayer(softVideoOverride: Boolean = false, prepareNow: Boolean = true) {
        if (player != null) return
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                PerfTuning.minBufferMs(context),
                PerfTuning.maxBufferMs(context),
                LoadControlTuning.playbackBufferMinMs(Prefs.getPlaybackBufferMin(context)),
                PerfTuning.afterRebufferMs(context),
            )
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val effectiveSoftVideo = softVideoOverride || Prefs.getVideoDecoderMode(context) == "SOFTWARE"
        val renderersFactory = JingcaiRenderersFactory(context, softVideo = effectiveSoftVideo)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val newPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .build()
            .apply {
                playWhenReady = true
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                setWakeMode(C.WAKE_MODE_NETWORK)
            }

        playerView.setKeepContentOnPlayerReset(true)
        playerUsesSoftVideo = effectiveSoftVideo
        if (!prepareNow) {
            playerView.player = newPlayer
            player = newPlayer
            return
        }
        val req = catchupRequest
        val request = if (req == null) {
            MediaPlaybackRequest(selectedLiveNow())
        } else {
            PlaybackIntent.catchup(allChannels, req, playbackIntent.request)
        }
        if (request == null) {
            newPlayer.release()
            playerView.player = null
            return
        }
        catchupRetryCount = 0
        playerView.player = newPlayer
        prepareRequest(newPlayer, request)
        player = newPlayer
    }

    fun releasePlayer() {
        playbackIntent.suspendPlayer()
        playerView.player = null
        player?.let { StreamPlayback.release(it) }
        player = null
        playerUsesSoftVideo = false
    }

    fun rebuildPlayer(softVideo: Boolean) {
        if (rebuildBusy[0]) return
        val request = rebuildState[0] ?: return
        rebuildBusy[0] = true
        try {
            releasePlayer()
            initializePlayer(softVideoOverride = softVideo, prepareNow = false)
            val fresh = player ?: return
            StreamPlayback.prepareForRebuild(
                fresh, context, request, playbackIntent,
                onFailure = { playbackErrorEvents.trySend(it) },
            )
        } finally {
            rebuildBusy[0] = false
        }
    }

    fun isLikely4kChannel(ch: Channel?): Boolean {
        if (ch == null) return false
        val text = "${ch.title} ${ch.group}".uppercase()
        return text.contains("4K") || text.contains("UHD") || text.contains("2160") || text.contains("8K")
    }

    fun isFormat4kOrHevc(fmt: Format?): Boolean {
        if (fmt == null) return false
        if (fmt.width >= 2560 || fmt.height >= 1440) return true
        val codecs = fmt.codecs.orEmpty().lowercase()
        val mime = fmt.sampleMimeType.orEmpty().lowercase()
        return (codecs.contains("hvc") || codecs.contains("hev") || mime.contains("hevc")) &&
            (fmt.width >= 1920 || fmt.height >= 1080)
    }

    /** 修复 P0-8 & 4K 花屏：软解降级或 4K/HEVC 切台时重建播放器以清理旧解码器 DPB 残留帧。 */
    fun ensureHardwarePlayerForNewZap(targetChannel: Channel? = null): ExoPlayer? {
        val wantSoft = Prefs.getVideoDecoderMode(context) == "SOFTWARE"
        val rebuild4k = Prefs.isRebuildOn4kZap(context) &&
            (isLikely4kChannel(targetChannel) || isFormat4kOrHevc(videoFormat))
        if (player != null && (playerUsesSoftVideo != wantSoft || rebuild4k)) {
            releasePlayer()
            initializePlayer(softVideoOverride = wantSoft, prepareNow = false)
        }
        return player
    }

    DisposableEffect(Unit) {
        StreamPlayback.setRebuildHandler { _, soft -> rebuildPlayer(soft) }
        onDispose { StreamPlayback.setRebuildHandler(null) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> initializePlayer()
                Lifecycle.Event.ON_RESUME -> {
                    // 从扫码配置页或后台返回时同步所有可能被 Web 控制台修改的配置
                    appLanguage = Prefs.getAppLanguage(context)
                    AppStrings.set(appLanguage)
                    sources = Prefs.getSources(context)
                    activeSourceId = Prefs.getActiveSourceId(context)
                    globalUserAgent = Prefs.getGlobalUserAgent(context)
                    globalCustomHeaders = Prefs.getGlobalCustomHeaders(context)
                    uiScale = Prefs.getUiScale(context)
                    infoOkMode = Prefs.getInfoOkMode(context)
                    playbackBufferMin = Prefs.getPlaybackBufferMin(context)
                    autoUpdatePlaylist = Prefs.getAutoUpdatePlaylist(context)
                    autoUpdateEpg = Prefs.getAutoUpdateEpg(context)
                    autoCheckUpdate = Prefs.isAutoCheckUpdateEnabled(context)
                    logoPrefetchEnabled = Prefs.isLogoPrefetchEnabled(context)
                    logoLoadEnabled = Prefs.isLogoLoadEnabled(context)
                    LogoLoader.setLogoLoadEnabledRuntime(logoLoadEnabled)
                    remoteHabits = Prefs.getRemoteSettings(context)
                    remoteCbs.onRemoteSettingsChanged(remoteHabits)
                    remoteCbs.onBindingsChanged(Prefs.getRemoteBindings(context))
                }
                Lifecycle.Event.ON_STOP -> {
                    releasePlayer()
                    exitInAppPip()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releasePlayer()
            exitInAppPip()
        }
    }

    DisposableEffect(player) {
        val p = player
        if (p != null) {
            val activity = context as? Activity
            fun setScreenOn(keepOn: Boolean) {
                val window = activity?.window ?: return
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = setScreenOn(isPlaying)
                override fun onVideoSizeChanged(videoSize: VideoSize) { videoFormat = p.videoFormat }
                override fun onRenderedFirstFrame() {
                    ZapTrace.onFirstFrame(SystemClock.elapsedRealtime())
                    firstFrameRendered = true
                    if (Log.isLoggable(ZAP_TAG, Log.DEBUG)) {
                        ZapTrace.summaryLineOnce()?.let { Log.d(ZAP_TAG, it) }
                    }
                }
                override fun onTracksChanged(tracks: Tracks) {
                    videoFormat = p.videoFormat
                    audioTracks = TracksFlattening.audioTracks(tracks)
                    subtitleTracks = TracksFlattening.subtitleTracks(tracks)
                    if (subtitleOverrideGroup < 0 && !subtitleDisabled && subtitleTracks.isNotEmpty()) {
                        val prefEnabled = Prefs.isDefaultSubtitlesEnabled(context)
                        if (!prefEnabled) {
                            subtitleDisabled = true
                            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                        } else {
                            val prefLang = Prefs.getSubtitlePreferredLang(context)
                            val target = when (prefLang) {
                                "zh" -> subtitleTracks.firstOrNull {
                                    val l = it.language.orEmpty().lowercase()
                                    val lb = it.label.orEmpty().lowercase()
                                    l.startsWith("zh") || l.startsWith("chi") || l == "zho" ||
                                        lb.contains("中") || lb.contains("简") || lb.contains("繁") || lb.contains("chs") || lb.contains("cht")
                                }
                                "en" -> subtitleTracks.firstOrNull {
                                    val l = it.language.orEmpty().lowercase()
                                    val lb = it.label.orEmpty().lowercase()
                                    l.startsWith("en") || l == "eng" || lb.contains("eng") || lb.contains("英")
                                }
                                else -> null
                            } ?: subtitleTracks.firstOrNull()
                            if (target != null) {
                                val g = tracks.groups.getOrNull(target.groupIndex)
                                if (g != null && g.type == C.TRACK_TYPE_TEXT && target.trackIndex in 0 until g.length && g.isTrackSupported(target.trackIndex)) {
                                    subtitleOverrideGroup = target.groupIndex
                                    subtitleOverrideTrack = target.trackIndex
                                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, target.trackIndex))
                                        .build()
                                }
                            }
                        }
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    audioOverrideGroup = -1
                    audioOverrideTrack = -1
                    subtitleDisabled = !Prefs.isDefaultSubtitlesEnabled(context)
                    subtitleOverrideGroup = -1
                    subtitleOverrideTrack = -1
                    clearAudioOverride(p)
                    if (subtitleDisabled) {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    } else {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                    }
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

    BindChannelSwitchLoading(channelSwitch, player)

    // ── 播放列表加载 & 提交 ──────────────────────────────────────────────────
    suspend fun parsePlaylist(content: String, source: String): List<ChannelGroup> =
        withContext(Dispatchers.Default) { ChannelGrouper.group(M3uParser.parse(content, source)) }

    fun commitPlaylist(update: PlaylistApplication, mode: PlaylistUpdateMode) {
        val policy = playbackIntent.commitPlaylist(
            update,
            canPrepare = catchupRequest == null && player != null,
            initial = mode == PlaylistUpdateMode.Initial,
            recovery = mode == PlaylistUpdateMode.Recovery,
        )
        SourceLatencyProber.updateChannels(ChannelGrouper.allChannels(update.groups))
        channelGroups = update.groups
        policy.groupIndex?.let { currentGroupIndex = it }
        policy.variantIndex?.let { currentVariantIndex = it }
        policy.prepare?.let { request ->
            if (mode == PlaylistUpdateMode.Recovery) channelSwitch.show()
            player?.let { prepareRequest(it, request) }
            Prefs.setLastChannel(context, request.channel.url, request.channel.title)
        }
        // 统一按标准台标尺寸预取一次（消除旧 80×50 100% 缓存未命中的二次预取）
        scope.launch {
            while (!heavyWorkEnabled) delay(250L)
            if (channelGroups !== update.groups) return@launch
            val logos = buildPrefetchLogoUrls(ChannelGrouper.displayChannels(update.groups), currentGroupIndex).take(48)
            LogoLoader.prefetch(context, logos, LogoLoader.CANONICAL_WIDTH_PX, LogoLoader.CANONICAL_HEIGHT_PX)
        }
    }

    suspend fun applyPlaylistContent(
        ticket: Long,
        mode: PlaylistUpdateMode,
        load: suspend () -> String?,
        valid: () -> Boolean = { true },
    ): Boolean {
        val source = Prefs.getLiveSource(context)
        return playbackIntent.updatePlaylist(
            ticket,
            { liveSelection() },
            load,
            { parsePlaylist(it, source) },
            mode != PlaylistUpdateMode.Initial,
            { Prefs.getLiveSource(context) == source && valid() },
            { commitPlaylist(it, mode) },
        )
    }

    LaunchedEffect(playlistFileName, url, title, player) {
        val liveSource = Prefs.getLiveSource(context)
        val fileName = playlistFileName?.takeIf { it.isNotBlank() }
            ?: liveSource.takeIf { it.isNotBlank() }?.let { PlaylistCache.fileNameForSource(it) }
        if (fileName != null) {
            applyPlaylistContent(
                playbackIntent.ticket(),
                PlaylistUpdateMode.Initial,
                { withContext(Dispatchers.IO) { PlaylistCache.read(context, fileName) } },
            )
        }
    }

    LaunchedEffect(Unit) {
        BackgroundSourceUpdater.playlistUpdated.collectLatest { fileName ->
            if (fileName != Prefs.getPlaylistFileName(context)) return@collectLatest
            applyPlaylistContent(
                playbackIntent.ticket(),
                PlaylistUpdateMode.Background,
                { withContext(Dispatchers.IO) { PlaylistCache.read(context, fileName) } },
                { Prefs.getPlaylistFileName(context) == fileName },
            )
        }
    }

    // 播放错误自动恢复：先刷新源缓存，若同频道有多线路则自动切下一条可用线路
    LaunchedEffect(Unit) {
        for (failure in playbackErrorEvents) {
            val failed = failure.playback
            val p = player ?: continue
            if (!playbackIntent.isCurrent(failed) || p.currentMediaItem?.mediaId != failed.mediaId) continue
            if (failed.request.isCatchup) {
                if (catchupRetryCount >= 3) continue
                catchupRetryCount += 1
                delay(800L)
                if (player === p && playbackIntent.isCurrent(failed) && p.currentMediaItem?.mediaId == failed.mediaId) {
                    prepareRequest(p, failed.request)
                }
                continue
            }
            channelSwitch.show()
            val groupIndex = ChannelGrouper.findGroupIndexByUrl(channelGroups, failed.request.channel.url)
            if (groupIndex != null && !playbackIntent.retainsUnlistedLive) {
                val group = channelGroups[groupIndex]
                if (group.variants.size > 1) {
                    triedVariantUrls.add(failed.request.channel.url)
                    val failedIdx = ChannelGrouper.findVariantIndexByUrl(group, failed.request.channel.url)
                    val nextIdx = (1 until group.variants.size)
                        .map { offset -> (failedIdx + offset) % group.variants.size }
                        .firstOrNull { idx -> group.variants[idx].channel.url !in triedVariantUrls }
                    if (nextIdx != null && playbackIntent.isCurrent(failed)) {
                        val nextCh = group.variants[nextIdx].channel
                        currentGroupIndex = groupIndex
                        currentVariantIndex = nextIdx
                        prepareRequest(p, MediaPlaybackRequest(nextCh))
                        Prefs.setLastChannel(context, nextCh.url, nextCh.title)
                        continue
                    } else {
                        triedVariantUrls.clear()
                    }
                }
            }
            val source = Prefs.getLiveSource(context)
            playbackIntent.recover(
                failure,
                { liveSelection() },
                {
                    val refreshed = BackgroundSourceUpdater.refreshPlaylistUrgent(context)
                    if (!playbackIntent.isCurrent(failed) || !refreshed) null
                    else Prefs.getPlaylistFileName(context)?.let { file ->
                        withContext(Dispatchers.IO) { PlaylistCache.read(context, file) }
                    }
                },
                { parsePlaylist(it, source) },
                { player === p && p.currentMediaItem?.mediaId == failed.mediaId && Prefs.getLiveSource(context) == source },
                { commitPlaylist(it, PlaylistUpdateMode.Recovery) },
            )
        }
    }

    // 相邻频道实验预热
    LaunchedEffect(zapPreconnectEnabled, multicastPrewarmEnabled, drawerOpen, firstFrameRendered, currentGroupIndex) {
        val urls = channelGroups.map { it.variants.firstOrNull()?.channel?.url ?: "" }
        if (urls.isEmpty()) return@LaunchedEffect
        val idle = firstFrameRendered && !drawerOpen
        if (zapPreconnectEnabled) {
            ZapPreconnect.warmNeighbours(context, urls, currentGroupIndex, drawerOpen, idle)
        }
        if (multicastPrewarmEnabled) {
            MulticastPrewarm.warmNeighbour(context, urls, currentGroupIndex, drawerOpen, idle)
        }
    }

    LaunchedEffect(Unit) {
        AutoUpdateScheduler.start(context)
        AutoUpdateScheduler.events.collect { message ->
            AutoUpdateScheduler.clearReplayedEvent()
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            autoUpdateTick++
        }
    }

    // EPG 加载（SQLite 毫秒冷启；仍保留首帧后启动以避免抢占起播 IO）
    LaunchedEffect(Unit) {
        val epgSource = Prefs.getEpgSource(context)
        if (epgSource.isNotBlank()) {
            launch {
                BackgroundSourceUpdater.epgUpdated.collect { data ->
                    epgData = data
                    epgVersion++
                    epgLoadFailed = false
                }
            }
            launch {
                manualEpgRequests.collect {
                    if (ManualRefreshPolicy.actionFor(true, false) == ManualRefreshPolicy.Action.CALL_SCHEDULER) {
                        AutoUpdateScheduler.requestManualEpg(context)
                    }
                }
            }
            withTimeoutOrNull(ColdStartPolicy.EPG_CACHE_FALLBACK_MS) {
                snapshotFlow { firstFrameRendered }.first { it }
            }
            if (ColdStartPolicy.EPG_POST_FIRST_FRAME_DELAY_MS > 0L) {
                delay(ColdStartPolicy.EPG_POST_FIRST_FRAME_DELAY_MS)
            }
            val t0 = System.currentTimeMillis()
            val cachedEpg = withContext(Dispatchers.IO) {
                PerfTrace.section("IQTV-epg-cache-load") {
                    EpgRepository.load(context, epgSource, forceRefresh = false)
                }
            }
            epgLoadFailed = cachedEpg == null
            PerfTrace.d { "epg_cache_load ms=${System.currentTimeMillis() - t0} firstFrame=$firstFrameRendered" }
            if (cachedEpg != null) {
                epgData = cachedEpg
                epgVersion++
            }
        }
    }

    // 当前节目批量查询（SQLite 索引查询 <5ms；仅在 EPG 就绪/打开浮层/30s 刷新时执行）
    LaunchedEffect(epgData, epgVersion, nowMillis, channelGroups, needsClockTick) {
        val data = epgData
        if (data == null || channelGroups.isEmpty()) {
            nowProgramByUrl = emptyMap()
            return@LaunchedEffect
        }
        val queryNow = System.currentTimeMillis()
        val map = withContext(Dispatchers.IO) {
            if (EpgRepository.isSqlBacked(data)) {
                EpgSqlStore.queryNowPrograms(context, channelGroups, queryNow)
            } else {
                buildMap {
                    for (group in channelGroups) {
                        val displayChannel = group.defaultChannel
                        val t = data.nowProgramTitle(displayChannel, queryNow)
                        if (!t.isNullOrBlank()) {
                            val current = data.nowProgram(displayChannel, queryNow)
                            val next = data.nextProgram(displayChannel, queryNow)
                            val progress = current?.let {
                                val d = (it.endMillis - it.startMillis).toFloat()
                                if (d <= 0f) null else ((queryNow - it.startMillis).toFloat() / d).coerceIn(0f, 1f)
                            }
                            val ui = NowProgramUi(
                                title = t,
                                progress = progress,
                                nextTitle = next?.title,
                                startMillis = current?.startMillis ?: 0L,
                                endMillis = current?.endMillis ?: 0L,
                                nextStartMillis = next?.startMillis ?: 0L,
                            )
                            for (variant in group.variants) put(variant.channel.url, ui)
                        }
                    }
                }
            }
        }
        nowProgramByUrl = map
    }

    val epgProgramProvider: (suspend (Channel) -> List<EpgProgram>)? = remember(epgData, epgVersion) {
        val d = epgData
        if (d != null && EpgRepository.isSqlBacked(d)) {
            { ch -> withContext(Dispatchers.IO) { EpgSqlStore.queryChannelPrograms(context, ch) } }
        } else null
    }

    // ── 切台与音轨操作 ───────────────────────────────────────────────────────
    fun flushRecentChannels(gen: Int? = null) {
        recentCache.flush(gen)
    }

    fun recordRecentChannel(group: ChannelGroup, number: Int, variantIndex: Int) {
        recentEntries = recentCache.record(group, number, variantIndex)
        val gen = recentCache.generationSnapshot()
        recentWriteJob?.cancel()
        recentWriteJob = scope.launch {
            delay(400L)
            flushRecentChannels(gen)
        }
    }

    fun startPlayback(request: MediaPlaybackRequest, showLoading: Boolean = true) {
        val sameLive = !request.isCatchup && request.channel.url == playbackIntent.request?.channel?.url
        if (showLoading && !sameLive) channelSwitch.show()
        val p = ensureHardwarePlayerForNewZap(request.channel)
        if (p != null) prepareRequest(p, request) else playbackIntent.retain(request)
    }

    fun playGroupVariant(groupIndex: Int, variantIndex: Int, armZap: Boolean = true) {
        triedVariantUrls.clear()
        ZapPreconnect.cancel()
        MulticastPrewarm.cancel()
        StreamPlayback.onUserZap()
        val group = channelGroups.getOrNull(groupIndex) ?: return
        val channel = group.variants.getOrNull(variantIndex)?.channel ?: return
        if (armZap && Log.isLoggable(ZAP_TAG, Log.DEBUG)) {
            ZapTrace.onZapRequested(ZapTrace.groupKey(groupIndex), SystemClock.elapsedRealtime())
        }
        playbackIntent.beginSelection()
        catchupRequest = null
        currentGroupIndex = groupIndex
        currentVariantIndex = variantIndex
        startPlayback(MediaPlaybackRequest(channel))
        Prefs.setLastChannel(context, channel.url, channel.title)
        recordRecentChannel(group, groupIndex + 1, variantIndex)
    }

    /**
     * 修复 Perf-2：切台热路径不再同步阻塞等待 SourceLatencyProber 测速！
     * 优先选已知延迟最低且健康的线路，无缓存时立即起播默认线路（0ms 额外等待），
     * 若实际起播失败由 playbackErrorEvents 自动无缝切备用线路。
     */
    fun playGroupAuto(groupIndex: Int) {
        val group = channelGroups.getOrNull(groupIndex) ?: return
        if (group.variants.isEmpty()) return
        val bestIdx = if (group.variants.size == 1) {
            0
        } else {
            val cachedReachable = group.variants.indexOfFirst { v ->
                SourceLatencyProber.hasFreshCache(v.channel.url) &&
                    SourceLatencyProber.cachedLatencyMs(v.channel.url) != null
            }
            if (cachedReachable >= 0) cachedReachable else group.defaultVariantIndex.coerceIn(0, group.variants.lastIndex)
        }
        playGroupVariant(groupIndex, bestIdx, armZap = true)
    }

    fun playChannel(channel: Channel) {
        val targetIdx = channelGroups.indexOfFirst { group ->
            group.variants.any { it.channel.url == channel.url && (channel.id == 0L || it.channel.id == channel.id) }
        }
        if (targetIdx < 0) return
        val liveChannel = playbackIntent.request?.channel
        if (catchupRequest == null &&
            targetIdx == currentGroupIndex &&
            liveChannel != null &&
            channelGroups[targetIdx].variants.any { it.channel.url == liveChannel.url }
        ) {
            // 已在播放当前频道时再次确认：不重载，直接收起选台抽屉进入全屏
            sidebarController.closeDrawer()
            uiController.closeDrawer()
            return
        }
        channel.logoUrl?.trim()?.takeIf { it.isNotBlank() }?.let { u ->
            scope.launch { LogoLoader.prefetch(context, listOf(u), LogoLoader.CANONICAL_WIDTH_PX, LogoLoader.CANONICAL_HEIGHT_PX) }
        }
        playGroupAuto(targetIdx)
    }

    fun applyAudioOverride(groupIndex: Int, trackIndex: Int) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (groupIndex < 0) {
            builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            audioOverrideGroup = -1
            audioOverrideTrack = -1
        } else {
            val group = p.currentTracks.groups.getOrNull(groupIndex) ?: return
            if (group.type != C.TRACK_TYPE_AUDIO || trackIndex !in 0 until group.length || !group.isTrackSupported(trackIndex)) return
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            audioOverrideGroup = groupIndex
            audioOverrideTrack = trackIndex
        }
        p.trackSelectionParameters = builder.build()
    }

    fun applySubtitleOverride(item: SubtitleTrackUi.Item) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (item.isOff) {
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            subtitleDisabled = true
            subtitleOverrideGroup = -1
            subtitleOverrideTrack = -1
        } else {
            val group = p.currentTracks.groups.getOrNull(item.groupIndex) ?: return
            if (group.type != C.TRACK_TYPE_TEXT || item.trackIndex !in 0 until group.length || !group.isTrackSupported(item.trackIndex)) return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, item.trackIndex))
            subtitleDisabled = false
            subtitleOverrideGroup = item.groupIndex
            subtitleOverrideTrack = item.trackIndex
        }
        p.trackSelectionParameters = builder.build()
    }

    fun formatPipLabel(ch: Channel): String =
        "${ch.title} · ${AppStrings.tr(appLanguage, "→ 切换", "→ Swap")}"

    fun enterInAppPip() {
        val gs = channelGroups
        val live = playbackIntent.liveOwner(gs, currentGroupIndex, currentVariantIndex)
        val gIdx = live.groupIndex
        val vIdx = live.variantIndex.coerceAtLeast(0)
        val ch = live.channel ?: return
        if (gIdx !in gs.indices) return
        val pipView = playback.pipPlayerView ?: return
        val prev = pipPlayer
        pipPlayer = null
        runCatching { prev?.release() }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 6000, 500, 1000)
            .build()
        val p = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, false)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (pipPlayer === p) {
                    exitInAppPip()
                    Toast.makeText(
                        context,
                        AppStrings.tr(appLanguage, "设备解码通道不足或流异常，已退出画中画", "Decoder busy or stream failed, exited PiP"),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        })
        pipPlayer = p
        pipGroupIndex = gIdx
        pipVariantIndex = vIdx
        pipChannel = ch
        pipView.player = p
        playback.pipLabelView?.text = formatPipLabel(ch)
        playback.pipContainerView?.visibility = View.VISIBLE
        StreamPlayback.prepareAuxiliaryMutedPlayer(p, context, ch)
        uiController.hideInfoCard()
        Toast.makeText(
            context,
            AppStrings.tr(appLanguage, "画中画已开启：可换台双看，右键互换，双击返回退出", "PiP active: zap to second channel, Right to swap, 2x Back to exit"),
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun swapInAppPip(): Boolean {
        val p = pipPlayer ?: return false
        val gs = channelGroups
        val oldPipG = pipGroupIndex
        val oldPipV = pipVariantIndex
        val oldPipCh = pipChannel ?: return false
        val live = playbackIntent.liveOwner(gs, currentGroupIndex, currentVariantIndex)
        val oldMainG = live.groupIndex
        val oldMainV = live.variantIndex.coerceAtLeast(0)
        val oldMainCh = live.channel ?: return false
        if (oldPipG !in gs.indices || oldMainG !in gs.indices) return false
        if (oldMainG == oldPipG && oldMainCh.url == oldPipCh.url) {
            Toast.makeText(
                context,
                AppStrings.tr(appLanguage, "请先换台到第二个频道后再按右键切换", "Zap to another channel before swapping"),
                Toast.LENGTH_SHORT,
            ).show()
            return true
        }
        p.stop()
        pipGroupIndex = oldMainG
        pipVariantIndex = oldMainV
        pipChannel = oldMainCh
        playback.pipLabelView?.text = formatPipLabel(oldMainCh)
        playGroupVariant(oldPipG, oldPipV, armZap = true)
        uiController.showInfoCard(focused = false)
        StreamPlayback.prepareAuxiliaryMutedPlayer(p, context, oldMainCh)
        return true
    }

    val bannerQuickActions = remember(playingGroup, playingVariantIndex, videoAspectMode, appLanguage, inAppPipActive, favoriteUrls, currentChannel) {
        buildList {
            val isFav = currentChannel?.url?.let { it in favoriteUrls } == true
            add(
                InfoCardQuickAction(
                    id = InfoCardQuickActionId.FAVORITE,
                    label = if (isFav) AppStrings.tr(appLanguage, "已收藏", "Fav") else AppStrings.tr(appLanguage, "收藏", "Fav"),
                    description = AppStrings.tr(appLanguage, "收藏/取消收藏当前频道", "Toggle favorite"),
                    active = isFav,
                )
            )
            val variantCount = playingGroup?.variants?.size ?: 0
            if (variantCount > 1) {
                val idx = (playingVariantIndex + 1).coerceIn(1, variantCount)
                add(
                    InfoCardQuickAction(
                        id = InfoCardQuickActionId.SOURCE,
                        label = AppStrings.tr(appLanguage, "线路 $idx/$variantCount", "Src $idx/$variantCount"),
                        description = AppStrings.tr(appLanguage, "切换播放线路", "Switch source"),
                    )
                )
            }
            add(
                InfoCardQuickAction(
                    id = InfoCardQuickActionId.ASPECT,
                    label = videoAspectMode.shortLabel(appLanguage),
                    description = AppStrings.tr(appLanguage, "画面比例：${videoAspectMode.localizedLabel(appLanguage)}", "Aspect ratio: ${videoAspectMode.localizedLabel(appLanguage)}"),
                )
            )
            add(
                InfoCardQuickAction(
                    id = InfoCardQuickActionId.AUDIO,
                    label = AppStrings.tr(appLanguage, "音轨", "Audio"),
                )
            )
            add(
                InfoCardQuickAction(
                    id = InfoCardQuickActionId.SUBTITLE,
                    label = AppStrings.tr(appLanguage, "字幕", "Subtitles"),
                )
            )
            add(
                InfoCardQuickAction(
                    id = InfoCardQuickActionId.PIP,
                    label = if (inAppPipActive) {
                        AppStrings.tr(appLanguage, "退出画中画", "Exit PiP")
                    } else {
                        AppStrings.tr(appLanguage, "画中画", "PiP")
                    },
                    description = AppStrings.tr(appLanguage, "应用内双台画中画", "In-app dual-channel PiP"),
                    active = inAppPipActive,
                )
            )
            add(
                InfoCardQuickAction(
                    id = InfoCardQuickActionId.SETTINGS,
                    label = AppStrings.tr(appLanguage, "设置", "Settings"),
                )
            )
        }
    }

    fun triggerQuickAction(actionId: InfoCardQuickActionId) {
        when (actionId) {
            InfoCardQuickActionId.FAVORITE -> {
                val ch = currentChannel ?: return
                Prefs.toggleFavoriteUrl(context, ch.url)
                favoriteUrls = Prefs.getFavoriteUrls(context)
                uiController.noteInfoCardInteraction(promoteManual = true)
            }
            InfoCardQuickActionId.SOURCE -> uiController.openQualityDialog(currentGroupIndex)
            InfoCardQuickActionId.ASPECT -> {
                val next = videoAspectMode.next()
                videoAspectMode = next
                Prefs.setVideoAspectMode(context, next)
                uiController.noteInfoCardInteraction(promoteManual = true)
            }
            InfoCardQuickActionId.AUDIO -> uiController.openAudioDialog()
            InfoCardQuickActionId.SUBTITLE -> {
                uiController.hideInfoCard()
                subtitleDialogOpen = true
            }
            InfoCardQuickActionId.PIP -> if (pipChannel != null) exitInAppPip() else enterInAppPip()
            InfoCardQuickActionId.SETTINGS -> uiController.openSettings()
        }
    }

    fun playLastChannel(): Boolean {
        if (uiController.isBlockingOverlayOpen) return false
        val now = System.currentTimeMillis()
        if (now - lastChannelSwitchAt < 600L) return false
        val currentGroupKey = channelGroups.getOrNull(currentGroupIndex)?.key
        val target = RecentChannels.previousResolved(recentEntries, channelGroups, currentGroupKey) ?: return false
        if (target.groupIndex !in channelGroups.indices || target.variantIndex !in target.group.variants.indices) return false
        lastChannelSwitchAt = now
        playGroupVariant(target.groupIndex, target.variantIndex, armZap = true)
        uiController.showInfoCard(focused = false)
        return true
    }

    // 持久化最近观看（暂停/退后台不丢）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE) flushRecentChannels()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flushRecentChannels()
        }
    }

    // ── 最近观看列表与信息卡回调接线 ──────────────────────────────────────────
    val recentResolved = remember(recentEntries, channelGroups, currentGroupIndex) {
        val currentGroupKey = channelGroups.getOrNull(currentGroupIndex)?.key
        RecentChannels.resolvedDistinct(recentEntries, channelGroups).map { resolved ->
            RecentChannelUi(
                title = resolved.group.displayTitle,
                number = resolved.groupIndex + 1,
                logoUrl = resolved.group.logoUrl ?: resolved.channel.logoUrl,
                selected = resolved.group.key == currentGroupKey,
                groupIndex = resolved.groupIndex,
                variantIndex = resolved.variantIndex,
            )
        }
    }
    val cardRecentItems = remember(recentResolved) {
        InfoCardPolicy.cardRecentItems(recentResolved) { it.selected }
    }

    fun switchToRecent(item: RecentChannelUi): Boolean {
        if (item.groupIndex !in channelGroups.indices) return false
        val group = channelGroups[item.groupIndex]
        if (group.variants.isEmpty()) return false
        val variant = item.variantIndex.takeIf { it in group.variants.indices } ?: 0
        sidebarController.closeDrawer()
        playGroupVariant(item.groupIndex, variant, armZap = true)
        return true
    }

    // 注册 Activity 回调（使用 rememberUpdatedState 保证始终读到最新状态，避免频繁 Dispose）
    val latestPlayGroupAuto = rememberUpdatedState(::playGroupAuto)
    val latestPlayLast = rememberUpdatedState(::playLastChannel)
    val latestChannelGroups = rememberUpdatedState(channelGroups)
    val latestCurrentGroupIdx = rememberUpdatedState(currentGroupIndex)
    val latestPlayer = rememberUpdatedState(player)
    val latestCatchupReq = rememberUpdatedState(catchupRequest)
    val latestCardRecent = rememberUpdatedState(cardRecentItems)
    val latestQuickActions = rememberUpdatedState(bannerQuickActions)
    val latestTriggerQuickAction = rememberUpdatedState<(InfoCardQuickActionId) -> Unit> { triggerQuickAction(it) }
    val latestSwapPip = rememberUpdatedState<() -> Boolean> { swapInAppPip() }
    val latestExitPip = rememberUpdatedState<() -> Unit> { exitInAppPip() }
    val latestSubtitleDialogOpen = rememberUpdatedState(subtitleDialogOpen)
    val latestInfoOkMode = rememberUpdatedState(infoOkMode)
    val latestSeekOverlay = rememberUpdatedState(isSeekOverlayVisible)

    DisposableEffect(Unit) {
        playback.setOnSwapPip { latestSwapPip.value() }
        playback.setOnExitPip { latestExitPip.value() }
        playback.setOnExtraBack {
            if (latestSubtitleDialogOpen.value) {
                subtitleDialogOpen = false
                true
            } else false
        }
        playback.setOnNumberJump { number ->
            val gs = latestChannelGroups.value
            if (number !in 1..gs.size) false else {
                latestPlayGroupAuto.value(number - 1)
                uiController.showInfoCard(focused = false)
                true
            }
        }
        playback.setOnDigitMatchJump { matchIndex ->
            val gs = latestChannelGroups.value
            val matches = DigitOsdPolicy.matchChannels(uiController.currentDigits.digits, gs, limit = 6)
            val chosen = matches.getOrNull(matchIndex) ?: return@setOnDigitMatchJump false
            if (chosen.groupIndex !in gs.indices) return@setOnDigitMatchJump false
            latestPlayGroupAuto.value(chosen.groupIndex)
            uiController.showInfoCard(focused = false)
            true
        }
        playback.setOnChannelStep { direction ->
            val gs = latestChannelGroups.value
            if (gs.size < 2 || uiController.isBlockingOverlayOpen) return@setOnChannelStep false
            val nextIdx = ((latestCurrentGroupIdx.value + direction) % gs.size + gs.size) % gs.size
            latestPlayGroupAuto.value(nextIdx)
            uiController.showInfoCard(focused = false)
            true
        }
        playback.setOnPlayLastChannel { latestPlayLast.value() }
        playback.setOnCatchupSeek { deltaMs ->
            val p = latestPlayer.value ?: return@setOnCatchupSeek false
            val req = latestCatchupReq.value ?: return@setOnCatchupSeek false
            if (!p.isCurrentMediaItemSeekable) return@setOnCatchupSeek false
            val total = (req.endMillis - req.startMillis).coerceAtLeast(1L)
            val target = (p.currentPosition + deltaMs).coerceIn(0L, total)
            p.seekTo(target)
            catchupSeekPingAt = System.currentTimeMillis()
            catchupPositionMs = target
            true
        }
        playback.setOnReturnToLive {
            val req = latestCatchupReq.value ?: return@setOnReturnToLive false
            val transition = playbackIntent.returnToLive(req, latestChannelGroups.value) ?: return@setOnReturnToLive false
            if (transition.groupIndex != null) {
                playChannel(transition.request.channel)
            } else {
                catchupRequest = null
                startPlayback(transition.request)
            }
            true
        }
        infoCard.setOpenInfoCardFromOkController {
            val recentShownCount = if (latestCatchupReq.value == null && !latestSeekOverlay.value) {
                latestCardRecent.value.size
            } else 0
            val quickCount = if (!latestSeekOverlay.value) latestQuickActions.value.size else 0
            val totalSlots = recentShownCount + quickCount
            val canFocus = InfoCardPolicy.canFocusRecent(
                recentCount = totalSlots,
                catchup = false,
                seekOverlayVisible = latestSeekOverlay.value,
            )
            val cur = uiController.currentOverlay as? PlayerOverlay.InfoCard
            if (cur != null) {
                // 第二段 OK（IDLE -> FOCUSED）
                if (!cur.focused && canFocus) uiController.focusInfoCard(0)
                else uiController.noteInfoCardInteraction(promoteManual = true)
            } else {
                val directFocus = InfoCardPolicy.openCardFocusesDirectly(latestInfoOkMode.value) && canFocus
                uiController.showInfoCard(focused = directFocus, manual = true)
            }
            true
        }
        infoCard.setInfoCardFocusedActionController { action ->
            val items = latestCardRecent.value
            val actions = latestQuickActions.value
            val recentShownCount = if (latestCatchupReq.value == null && !latestSeekOverlay.value) items.size else 0
            val totalSlots = recentShownCount + actions.size
            when (action) {
                InfoCardPolicy.FocusedKeyAction.MOVE_LEFT -> uiController.moveInfoCardFocus(-1, totalSlots)
                InfoCardPolicy.FocusedKeyAction.MOVE_RIGHT -> uiController.moveInfoCardFocus(1, totalSlots)
                InfoCardPolicy.FocusedKeyAction.SWITCH -> {
                    val idx = (uiController.currentOverlay as? PlayerOverlay.InfoCard)?.focusIndex ?: 0
                    if (idx < recentShownCount) {
                        InfoCardPolicy.resolveRecentTarget(items, idx)?.let { item ->
                            uiController.noteInfoCardInteraction()
                            if (switchToRecent(item)) uiController.focusInfoCard(0)
                        }
                    } else {
                        actions.getOrNull(idx - recentShownCount)?.let { act ->
                            latestTriggerQuickAction.value(act.id)
                        }
                    }
                }
                InfoCardPolicy.FocusedKeyAction.EXIT_FOCUS -> uiController.unfocusInfoCard()
                InfoCardPolicy.FocusedKeyAction.NONE -> Unit
            }
            true
        }
        onDispose {
            playback.setOnSwapPip(null)
            playback.setOnExitPip(null)
            playback.setOnExtraBack(null)
            playback.setOnNumberJump(null)
            playback.setOnChannelStep(null)
            playback.setOnPlayLastChannel(null)
            playback.setOnCatchupSeek(null)
            playback.setOnReturnToLive(null)
            infoCard.setOpenInfoCardFromOkController(null)
            infoCard.setInfoCardFocusedActionController(null)
        }
    }

    // ── 抽屉数据派生 ─────────────────────────────────────────────────────────
    val activeGroupConfig = remember(activeSourceId, groupConfigsVersion) {
        val sid = activeSourceId ?: "default"
        Prefs.getSourceGroupConfig(context, sid)
    }
    val groups = remember(channelGroups, favoriteUrls, showFavoritesGroup, showAllChannelsGroup, activeGroupConfig) {
        ChannelGrouper.sortedDrawerGroups(
            channelGroups = channelGroups,
            favoriteUrls = favoriteUrls,
            showFavorites = showFavoritesGroup,
            showAll = showAllChannelsGroup,
            groupConfig = activeGroupConfig,
        )
    }
    val filteredChannels = remember(channelGroups, selectedGroup, favoriteUrls) {
        ChannelGrouper.numberedDisplayChannelsForGroup(channelGroups, selectedGroup, favoriteUrls).map { it.channel }
    }
    val channelNumbersMap = remember(channelGroups) { ChannelGrouper.globalNumberByUrl(channelGroups) }

    // ── 线路弹窗数据 + 异步测速（仅在弹窗打开时执行） ─────────────────────────
    val qualityDialogGroupIndex = qualityOverlay?.groupIndex ?: playingGroupIndex
    val qualityDialogGroup = channelGroups.getOrNull(qualityDialogGroupIndex)
    val qualityVariants = remember(qualityDialogGroup, currentChannel?.url, playingVariantIndex, measuredLatencyByUrl, probingUrls, qualityDialogOpen) {
        qualityDialogGroup?.variants?.takeIf { it.size > 1 }?.mapIndexed { index, variant ->
            val selIdx = if (qualityDialogOpen) {
                ChannelGrouper.findVariantIndexByUrl(qualityDialogGroup, currentChannel?.url)
            } else playingVariantIndex
            val u = variant.channel.url
            SourceVariantUi(
                label = variant.qualityLabel,
                latencyMs = measuredLatencyByUrl[u] ?: SourceLatencyProber.cachedLatencyMs(u),
                probing = u in probingUrls,
                selected = index == selIdx,
            )
        }.orEmpty()
    }

    LaunchedEffect(qualityDialogOpen, qualityDialogGroupIndex) {
        if (!qualityDialogOpen) return@LaunchedEffect
        val group = channelGroups.getOrNull(qualityDialogGroupIndex) ?: return@LaunchedEffect
        val urls = group.variants.map { it.channel.url }
        if (urls.isEmpty()) return@LaunchedEffect
        probingUrls = probingUrls + urls
        val results = SourceLatencyProber.probeAll(urls, force = true)
        if (results.isNotEmpty()) measuredLatencyByUrl = measuredLatencyByUrl + results
        probingUrls = probingUrls - urls.toSet()
    }

    val logoCacheSummary by produceState(initialValue = "", settingsDrawerOpen, logoCacheTick) {
        value = if (settingsDrawerOpen) {
            val s = LogoLoader.stats(context)
            "内存 ${s.memoryEntries} 条 · 磁盘 ${s.diskFiles} 个 / ${s.diskBytes / 1024} KB"
        } else ""
    }

    // ── 多源热切换（支持选台抽屉播放源列 & 设置多源管理） ──────────────────
    fun switchToSource(source: PlaylistSource, closeSettingsAfter: Boolean = false) {
        if (source.url.isBlank()) return
        sourceSwitchJob?.cancel()
        val prevEpg = Prefs.getEpgSource(context)
        Prefs.setActiveSource(context, source.id)
        sources = Prefs.getSources(context)
        activeSourceId = source.id
        Toast.makeText(
            context,
            AppStrings.tr(appLanguage, "正在切换播放源：${source.name}…", "Switching source: ${source.name}…"),
            Toast.LENGTH_SHORT,
        ).show()

        sourceSwitchJob = scope.launch {
            try {
                val fileName = PlaylistCache.fileNameForSource(source.url)
                var content = withContext(Dispatchers.IO) { PlaylistCache.read(context, fileName) }
                if (content.isNullOrBlank()) {
                    val expectedVersion = SourceStore.beginChange(context)
                    val client = NetUtil.ipv4OkHttpClient.newBuilder()
                        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val ua = source.userAgent.ifBlank { globalUserAgent }.ifBlank { HttpStreamAddress.DEFAULT_USER_AGENT }
                    val req = okhttp3.Request.Builder().url(source.url).header("User-Agent", ua).build()
                    val fetched = withContext(Dispatchers.IO) {
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                            resp.body?.string() ?: throw java.io.IOException("empty response")
                        }
                    }
                    val detectedEpg = HttpStreamAddress.extractM3uExtras(fetched).headerEpgUrl.orEmpty()
                    val effectiveEpg = source.epgUrl.ifBlank { detectedEpg }.ifBlank { prevEpg }
                    withContext(Dispatchers.IO) {
                        SourceStore.commitSource(context, source.url, effectiveEpg, fetched, expectedVersion)
                    }
                    content = fetched
                } else {
                    val detectedEpg = withContext(Dispatchers.Default) {
                        HttpStreamAddress.extractM3uExtras(content!!).headerEpgUrl.orEmpty()
                    }
                    val effectiveEpg = source.epgUrl.ifBlank { detectedEpg }.ifBlank { prevEpg }
                    Prefs.setLiveSource(context, source.url)
                    Prefs.setPlaylistFileName(context, fileName)
                    if (effectiveEpg.isNotBlank()) Prefs.setEpgSource(context, effectiveEpg)
                }

                val parsedGroups = parsePlaylist(content!!, source.url)
                if (parsedGroups.isEmpty()) {
                    Toast.makeText(
                        context,
                        AppStrings.tr(appLanguage, "「${source.name}」未找到有效频道", "No channels found in \"${source.name}\""),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }

                SourceLatencyProber.updateChannels(ChannelGrouper.allChannels(parsedGroups))
                channelGroups = parsedGroups
                currentGroupIndex = 0
                currentVariantIndex = 0
                sidebarController.selectGroup(SidebarState.ALL_GROUP, resetFocus = true)
                val firstChannel = parsedGroups.first().defaultChannel
                catchupRequest = null
                playbackIntent.beginSelection()
                val activePlayer = ensureHardwarePlayerForNewZap()
                if (activePlayer != null) {
                    prepareRequest(activePlayer, MediaPlaybackRequest(firstChannel))
                }
                Prefs.setLastChannel(context, firstChannel.url, firstChannel.title)
                if (closeSettingsAfter) uiController.closeSettings()
                Toast.makeText(
                    context,
                    AppStrings.tr(appLanguage, "已切换至「${source.name}」（${parsedGroups.size} 台）", "Switched to \"${source.name}\" (${parsedGroups.size} channels)"),
                    Toast.LENGTH_SHORT,
                ).show()
                if (Prefs.getEpgSource(context) != prevEpg) {
                    requestManualEpg()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PlayerActivity", "switchToSource failed: ${e.message}")
                Toast.makeText(
                    context,
                    AppStrings.tr(appLanguage, "切换「${source.name}」失败：${e.message ?: "网络异常"}", "Failed to switch \"${source.name}\": ${e.message ?: "Network error"}"),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // ── 原生 RecyclerView 选台抽屉绑定（仅数据变化或开合时同步，翻台 0 重组） ────
    val selectedDrawerChannelUrl = if (currentLive.retainedOutsideList) null
        else channelGroups.getOrNull(currentGroupIndex)?.defaultChannel?.url

    val latestPlayChannel = rememberUpdatedState(::playChannel)
    val latestAllChannels = rememberUpdatedState(allChannels)
    val latestStartPlayback = rememberUpdatedState(::startPlayback)
    val latestSwitchSource = rememberUpdatedState<(PlaylistSource) -> Unit>({ src -> switchToSource(src, closeSettingsAfter = false) })
    DisposableEffect(nativeDrawer) {
        nativeDrawer.listener = object : NativeChannelDrawerView.Listener {
            override fun onSelectSource(source: PlaylistSource) {
                latestSwitchSource.value(source)
            }
            override fun onSelectGroup(group: String, resetFocus: Boolean) {
                sidebarController.selectGroup(group, resetFocus = resetFocus)
            }
            override fun onSelectChannel(channel: Channel) {
                latestPlayChannel.value(channel)
            }
            override fun onToggleFavorite(channel: Channel) {
                val added = Prefs.toggleFavoriteUrl(context, channel.url)
                favoriteUrls = Prefs.getFavoriteUrls(context)
                val msg = if (added) AppStrings.tr(appLanguage, "已加入收藏夹", "Added to Favorites")
                else AppStrings.tr(appLanguage, "已移出收藏夹", "Removed from Favorites")
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            override fun onPlayProgram(request: CatchupPlayRequest) {
                val bound = PlaybackIntent.catchup(latestAllChannels.value, request, playbackIntent.request)
                if (bound != null) {
                    playbackIntent.beginSelection()
                    catchupRequest = request
                    latestStartPlayback.value(bound, true)
                    sidebarController.closeDrawer()
                    uiController.closeDrawer()
                }
            }
            override fun onClose() {
                sidebarController.closeDrawer()
                uiController.closeDrawer()
            }
            override fun onActiveColumnChanged(column: DrawerColumn) {
                sidebarController.setActiveColumn(column)
            }
            override fun onFocusedChannelChanged(url: String) {
                sidebarController.focusChannel(url)
            }
        }
        onDispose { nativeDrawer.listener = null }
    }

    LaunchedEffect(
        visibleSources,
        activeSourceId,
        groups,
        selectedGroup,
        filteredChannels,
        channelNumbersMap,
        selectedDrawerChannelUrl,
        nowProgramByUrl,
        epgData,
        epgVersion,
        epgProgramProvider,
        epgLoadFailed,
        uiScale,
        appLanguage,
        drawerOpen,
        favoriteUrls,
    ) {
        nativeDrawer.updateSnapshot(
            NativeChannelDrawerView.Snapshot(
                sources = visibleSources,
                activeSourceId = activeSourceId,
                groups = groups,
                selectedGroup = selectedGroup,
                channels = filteredChannels,
                channelNumbers = channelNumbersMap,
                selectedChannelUrl = selectedDrawerChannelUrl,
                nowProgramByChannelUrl = nowProgramByUrl,
                epgData = epgData,
                epgVersion = epgVersion,
                epgProgramProvider = epgProgramProvider,
                epgSourceConfigured = epgSourceConfigured,
                epgLoadFailed = epgLoadFailed,
                initialFocusUrl = sidebarController.lastFocusedUrlFor(selectedGroup),
                uiScale = uiScale,
                lang = appLanguage,
                favoriteUrls = favoriteUrls,
            )
        )
        nativeDrawer.setOpen(drawerOpen)
    }

    // ── UI 渲染层 ────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        ChannelSwitchOverlay(
            controller = channelSwitch,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
        )

        val audioSummaryText = remember(audioTracks, audioOverrideGroup, audioOverrideTrack, appLanguage) {
            AudioTrackUi.summary(audioTracks, audioOverrideGroup, audioOverrideTrack, appLanguage)
        }
        LaunchedEffect(settingsDrawerOpen) {
            if (!settingsDrawerOpen) {
                settingsNotice = null
                if (inlineUpdateState is InlineUpdateState.UpToDate || inlineUpdateState is InlineUpdateState.Failed) {
                    inlineUpdateState = InlineUpdateState.Idle
                }
            }
        }

        val lastUpdateSummary = remember(settingsDrawerOpen, autoUpdateTick, appLanguage) {
            if (!settingsDrawerOpen) "" else {
                val now = System.currentTimeMillis()
                val epg = AutoUpdateConfig.lastSuccessLabel(Prefs.getLastEpgUpdateTime(context), now, appLanguage)
                val pl = AutoUpdateConfig.lastSuccessLabel(Prefs.getPlaylistLastSuccess(context), now, appLanguage)
                if (appLanguage == AppLang.EN) {
                    "Last updated: Playlist $pl · EPG $epg"
                } else {
                    "上次更新：播放列表 $pl · EPG $epg"
                }
            }
        }

        val settingsState = SettingsUiState(
            visible = settingsDrawerOpen,
            page = settingsOverlay?.page,
            picker = settingsOverlay?.picker,
            rootFocusIndex = settingsOverlay?.rootFocusIndex ?: 0,
            language = appLanguage,
            uiScale = uiScale,
            infoOkMode = infoOkMode,
            playbackBufferMin = playbackBufferMin,
            qualityMenuEnabled = playingGroup?.variants?.size?.let { it > 1 } == true,
            recentCount = recentEntries.size,
            logoCacheSummary = logoCacheSummary,
            logoPrefetchEnabled = logoPrefetchEnabled,
            logoLoadEnabled = logoLoadEnabled,
            audioSummary = audioSummaryText,
            audioEntryLabel = AudioTrackUi.entryLabel(audioTracks, appLanguage),
            audioSelectable = AudioTrackUi.canSelect(audioTracks),
            zapPreconnectEnabled = zapPreconnectEnabled,
            multicastPrewarmEnabled = multicastPrewarmEnabled,
            jingcaiExperiment = jingcaiExperiment,
            remoteBindings = remoteCbs.remoteBindings,
            remoteDigits = remoteHabits.digits,
            captureRequest = captureRequest,
            autoUpdatePlaylist = autoUpdatePlaylist,
            autoUpdateEpg = autoUpdateEpg,
            lastUpdateSummary = lastUpdateSummary,
            sources = sources,
            activeSourceId = activeSourceId,
            globalUserAgent = globalUserAgent,
            globalCustomHeaders = globalCustomHeaders,
            autoCheckUpdate = autoCheckUpdate,
            updateState = inlineUpdateState,
            notice = settingsNotice,
            autoStartOnBoot = Prefs.isAutoStartOnBoot(context),
            resumeLastChannel = Prefs.isResumeLastChannel(context),
            homePipEnabled = Prefs.isHomePipEnabled(context),
            doubleBackExitEnabled = Prefs.isDoubleBackExitEnabled(context),
            epgRetentionDays = Prefs.getEpgRetentionDays(context),
            epgUpdateOnPlaylistChange = Prefs.isEpgUpdateOnPlaylistChange(context),
            uiTransparencyPct = uiTransparencyPct,
            infoPanelTimeoutSec = infoPanelTimeoutSec,
            showInfoOnZap = showInfoOnZap,
            showClockInPlayer = showClockInPlayer,
            showExactResolution = showExactResolution,
            showFavoritesGroup = showFavoritesGroup,
            showAllChannelsGroup = showAllChannelsGroup,
            videoDecoderMode = Prefs.getVideoDecoderMode(context),
            rebuildOn4kZap = Prefs.isRebuildOn4kZap(context),
            defaultSubtitlesEnabled = Prefs.isDefaultSubtitlesEnabled(context),
            subtitlePreferredLang = Prefs.getSubtitlePreferredLang(context),
            allGroupNames = remember(channelGroups) { ChannelGrouper.allRawGroupNames(channelGroups) },
            activeGroupConfig = activeGroupConfig,
        )

        val settingsActions = remember(
            appLanguage,
            remoteCbs.remoteBindings,
            remoteHabits,
            uiScale,
            sources,
            activeSourceId,
            globalUserAgent,
            globalCustomHeaders,
            autoCheckUpdate,
            inlineUpdateState,
            playbackBufferMin,
            zapPreconnectEnabled,
            multicastPrewarmEnabled,
            jingcaiExperiment,
            infoOkMode,
            autoUpdatePlaylist,
            autoUpdateEpg,
            audioTracks,
            playingGroupIndex,
            filteredChannels,
            uiTransparencyPct,
            infoPanelTimeoutSec,
            showInfoOnZap,
            showClockInPlayer,
            showExactResolution,
            showFavoritesGroup,
            showAllChannelsGroup,
            groupConfigsVersion,
        ) {
            SettingsActions(
                onOpenPage = { uiController.openSettingsPage(it) },
                onOpenPicker = { uiController.openSettingsPicker(it) },
                onClosePicker = { uiController.closeSettingsPicker() },
                onBackToRoot = { uiController.settingsBackToRoot() },
                onRootFocusChanged = { uiController.setSettingsRootFocus(it) },
                onClose = { uiController.closeSettings() },
                onSelectLanguage = { nextLang ->
                    appLanguage = nextLang
                    Prefs.setAppLanguage(context, nextLang)
                    AppStrings.set(nextLang)
                    notifySettings(AppStrings.tr(nextLang, "语言已切换为简体中文", "Language set to English"))
                },
                onSelectUiScale = { preset ->
                    uiScale = preset
                    Prefs.setUiScale(context, preset)
                    notifySettings(
                        AppStrings.tr(appLanguage, "界面大小已切换为「${preset.localizedLabel(appLanguage)}」", "UI size: ${preset.localizedLabel(appLanguage)}"),
                    )
                },
                onSelectInfoOkMode = { mode ->
                    Prefs.setInfoOkMode(context, mode)
                    infoOkMode = mode
                },
                onSelectPlaybackBuffer = { tier ->
                    Prefs.setPlaybackBufferMin(context, tier)
                    playbackBufferMin = tier
                    notifySettings(
                        AppStrings.tr(appLanguage, "起播缓冲：${LoadControlTuning.tierLabel(tier, appLanguage)}（下次切台生效）", "Buffer: ${LoadControlTuning.tierLabel(tier, appLanguage)} (next tune)"),
                    )
                },
                onSelectJingcaiExperiment = { mode ->
                    Prefs.setJingcaiExperiment(context, mode.id)
                    jingcaiExperiment = mode
                    notifySettings(
                        AppStrings.tr(appLanguage, "码流兼容模式：${mode.localizedLabel(appLanguage)}（重新选台生效）", "Stream mode: ${mode.localizedLabel(appLanguage)} (next tune)"),
                    )
                },
                onSelectPlaylistInterval = { iv ->
                    autoUpdatePlaylist = iv
                    Prefs.setAutoUpdatePlaylist(context, iv)
                },
                onSelectEpgInterval = { iv ->
                    autoUpdateEpg = iv
                    Prefs.setAutoUpdateEpg(context, iv)
                },
                onSelectRemoteUpDown = { habit ->
                    val chKeys = RemoteBindings.deriveChannelKeysEnabled(remoteCbs.remoteBindings)
                    val updated = RemoteBindings.applyUpDownHabit(remoteCbs.remoteBindings, habit, chKeys)
                    Prefs.setRemoteUpDown(context, habit)
                    remoteCbs.onBindingsChanged(updated)
                },
                onSelectRemoteOk = { habit ->
                    val updated = RemoteBindings.applyOkHabit(remoteCbs.remoteBindings, habit)
                    Prefs.setRemoteOk(context, habit)
                    remoteCbs.onBindingsChanged(updated)
                },
                onSelectRemoteDigits = { habit ->
                    Prefs.setRemoteDigits(context, habit)
                    remoteHabits = remoteHabits.copy(digits = habit)
                    remoteCbs.onRemoteSettingsChanged(remoteHabits)
                },
                onSourceConfigClick = { context.startActivity(Intent(context, MainActivity::class.java)) },
                onSwitchSource = { src -> switchToSource(src, closeSettingsAfter = true) },
                onToggleSourceEnabled = { id ->
                    val target = sources.firstOrNull { it.id == id }
                    if (target != null) {
                        val visibleCount = sources.count { it.enabled }
                        if (target.enabled && visibleCount <= 1) {
                            notifySettings(
                                AppStrings.tr(appLanguage, "至少保留 1 个在选台列表显示的播放源", "Keep at least 1 visible source"),
                            )
                        } else {
                            val updated = PlaylistSources.toggleEnabled(sources, id)
                            Prefs.saveSources(context, updated, activeSourceId)
                            sources = Prefs.getSources(context)
                            val nowEnabled = sources.firstOrNull { it.id == id }?.enabled == true
                            notifySettings(
                                if (nowEnabled) {
                                    AppStrings.tr(appLanguage, "已在选台列表显示「${target.name}」", "Showing \"${target.name}\" in drawer")
                                } else {
                                    AppStrings.tr(appLanguage, "已在选台列表隐藏「${target.name}」", "Hidden \"${target.name}\" in drawer")
                                },
                            )
                        }
                    }
                },
                onSelectGlobalUserAgent = { ua ->
                    Prefs.setGlobalUserAgent(context, ua)
                    globalUserAgent = ua
                    val label = ua.ifBlank { AppStrings.tr(appLanguage, "自动（默认 VLC）", "Auto (VLC)") }
                    notifySettings(
                        AppStrings.tr(appLanguage, "全局 User-Agent：$label（下次切台生效）", "Global User-Agent: $label (next tune)"),
                    )
                },
                onClearGlobalCustomHeaders = {
                    Prefs.setGlobalCustomHeaders(context, "")
                    globalCustomHeaders = ""
                    notifySettings(AppStrings.tr(appLanguage, "已清空全部全局自定义 Header", "Cleared global custom headers"))
                },
                onQualityMenuClick = { uiController.openQualityDialog(playingGroupIndex) },
                onToggleAutoCheckUpdate = { enabled ->
                    autoCheckUpdate = enabled
                    Prefs.setAutoCheckUpdateEnabled(context, enabled)
                    notifySettings(
                        if (enabled) AppStrings.tr(appLanguage, "已开启启动时自动检查更新", "Auto-check updates enabled")
                        else AppStrings.tr(appLanguage, "已关闭启动时自动检查更新", "Auto-check updates disabled")
                    )
                },
                onCheckUpdateClick = {
                    val cur = inlineUpdateState
                    if (cur !is InlineUpdateState.Checking && cur !is InlineUpdateState.Downloading) {
                        updateJob?.cancel()
                        inlineUpdateState = InlineUpdateState.Checking
                        updateJob = scope.launch {
                            val info = runCatching { UpdateChecker.checkUpdateAndGetInfo(context) }.getOrNull()
                            inlineUpdateState = when {
                                info == null -> InlineUpdateState.Failed(
                                    messageZh = "检查更新失败，请检查网络连接",
                                    messageEn = "Update check failed. Please check your network.",
                                )
                                info.hasUpdate -> InlineUpdateState.Available(info)
                                else -> InlineUpdateState.UpToDate(info.latestTag)
                            }
                        }
                    }
                },
                onDownloadUpdateClick = {
                    val info = when (val cur = inlineUpdateState) {
                        is InlineUpdateState.Available -> cur.info
                        is InlineUpdateState.Failed -> cur.info
                        else -> null
                    }
                    if (info != null && inlineUpdateState !is InlineUpdateState.Downloading) {
                        updateJob?.cancel()
                        inlineUpdateState = InlineUpdateState.Downloading(info, 0)
                        updateJob = scope.launch {
                            var lastPct = 0
                            val file = runCatching {
                                ApkDownloader.downloadWithProgress(context, info.downloadUrl) { progress ->
                                    val pct = (progress.progress * 100f).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        scope.launch(Dispatchers.Main) {
                                            if (inlineUpdateState is InlineUpdateState.Downloading) {
                                                inlineUpdateState = InlineUpdateState.Downloading(info, pct)
                                            }
                                        }
                                    }
                                }
                            }.getOrNull()
                            if (file != null) {
                                inlineUpdateState = InlineUpdateState.Available(info)
                                ApkDownloader.installApk(context, file)
                            } else {
                                inlineUpdateState = InlineUpdateState.Failed(
                                    messageZh = "安装包下载失败，请稍后重试",
                                    messageEn = "Download failed. Please retry.",
                                    info = info,
                                )
                            }
                        }
                    }
                },
                onOpenRecent = { uiController.openRecentDialog() },
                onRefreshLogos = {
                    notifySettings(AppStrings.tr(appLanguage, "正在刷新当前组台标…", "Refreshing logos…"))
                    scope.launch {
                        LogoLoader.refreshGroup(context, filteredChannels, LogoLoader.CANONICAL_WIDTH_PX, LogoLoader.CANONICAL_HEIGHT_PX)
                        logoCacheTick++
                        notifySettings(AppStrings.tr(appLanguage, "已刷新当前组台标", "Logos refreshed"))
                    }
                },
                onClearLogoCache = {
                    notifySettings(AppStrings.tr(appLanguage, "正在清理台标缓存…", "Clearing logo cache…"))
                    scope.launch {
                        LogoLoader.clearAll(context)
                        logoCacheTick++
                        notifySettings(AppStrings.tr(appLanguage, "台标缓存已清理", "Logo cache cleared"))
                    }
                },
                onToggleLogoPrefetch = { enabled ->
                    logoPrefetchEnabled = enabled
                    Prefs.setLogoPrefetchEnabled(context, enabled)
                },
                onToggleLogoLoad = { enabled ->
                    logoLoadEnabled = enabled
                    Prefs.setLogoLoadEnabled(context, enabled)
                    LogoLoader.setLogoLoadEnabledRuntime(enabled)
                    if (!enabled) LogoLoader.clearMemory()
                },
                onToggleZapPreconnect = {
                    val next = !zapPreconnectEnabled
                    Prefs.setZapPreconnectEnabled(context, next)
                    zapPreconnectEnabled = next
                    if (!next) ZapPreconnect.cancel()
                },
                onToggleMulticastPrewarm = {
                    val next = !multicastPrewarmEnabled
                    Prefs.setMulticastPrewarmEnabled(context, next)
                    multicastPrewarmEnabled = next
                    if (!next) MulticastPrewarm.cancel()
                },
                onAudioTrackClick = {
                    if (!AudioTrackUi.canSelect(audioTracks)) {
                        notifySettings(
                            if (audioTracks.isEmpty()) {
                                AppStrings.tr(appLanguage, "当前频道没有音轨", "No audio tracks")
                            } else {
                                AppStrings.tr(appLanguage, "当前频道只有 1 条音轨", "Only 1 audio track")
                            },
                        )
                    } else {
                        uiController.openAudioDialog()
                    }
                },
                onToggleRemoteChannelKeys = {
                    val next = !RemoteBindings.deriveChannelKeysEnabled(remoteCbs.remoteBindings)
                    val updated = RemoteBindings.applyChannelKeysHabit(remoteCbs.remoteBindings, next)
                    Prefs.setRemoteChannelKeys(context, next)
                    remoteCbs.onBindingsChanged(updated)
                },
                onStartCapture = { action, longPress -> uiController.startCapture(action, longPress) },
                onClearBinding = { action, longPress ->
                    val updated = RemoteBindings.clearBinding(remoteCbs.remoteBindings, action, longPress)
                    remoteCbs.onBindingsChanged(updated)
                    uiController.cancelCapture()
                    val modeLabel = if (longPress) {
                        AppStrings.tr(appLanguage, "长按", "long press")
                    } else {
                        AppStrings.tr(appLanguage, "短按", "short press")
                    }
                    notifySettings(
                        AppStrings.tr(appLanguage, "已清除「${action.localizedLabel(appLanguage)}」$modeLabel 绑定", "Cleared \"${action.localizedLabel(appLanguage)}\" ($modeLabel)"),
                    )
                },
                onCancelCapture = { uiController.cancelCapture() },
                onResetRemoteBindings = {
                    Prefs.resetRemoteSettings(context)
                    remoteHabits = RemoteHabits.DEFAULT
                    remoteCbs.onRemoteSettingsChanged(remoteHabits)
                    remoteCbs.onBindingsChanged(RemoteBindings.DEFAULT)
                    uiController.cancelCapture()
                    notifySettings(AppStrings.tr(appLanguage, "已恢复默认遥控配置", "Restored default remote bindings"))
                },
                onRefreshPlaylistNow = {
                    notifySettings(AppStrings.tr(appLanguage, "正在刷新播放列表…", "Refreshing playlist…"))
                    AutoUpdateScheduler.refreshNow(context, playlist = true, epg = false)
                },
                onRefreshEpgNow = {
                    notifySettings(AppStrings.tr(appLanguage, "正在刷新 EPG…", "Refreshing EPG…"))
                    requestManualEpg()
                },
                onToggleAutoStart = { enabled ->
                    Prefs.setAutoStartOnBoot(context, enabled)
                    groupConfigsVersion++
                },
                onToggleResumeLast = { enabled ->
                    Prefs.setResumeLastChannel(context, enabled)
                    groupConfigsVersion++
                },
                onToggleHomePip = { enabled ->
                    Prefs.setHomePipEnabled(context, enabled)
                    groupConfigsVersion++
                },
                onToggleDoubleBackExit = { enabled ->
                    Prefs.setDoubleBackExitEnabled(context, enabled)
                    groupConfigsVersion++
                },
                onSelectEpgRetentionDays = { days ->
                    Prefs.setEpgRetentionDays(context, days)
                    groupConfigsVersion++
                },
                onToggleEpgOnPlaylistChange = { enabled ->
                    Prefs.setEpgUpdateOnPlaylistChange(context, enabled)
                    groupConfigsVersion++
                },
                onSelectUiTransparencyPct = { pct ->
                    uiTransparencyPct = pct
                    Prefs.setUiTransparencyPct(context, pct)
                },
                onSelectInfoPanelTimeoutSec = { sec ->
                    infoPanelTimeoutSec = sec
                    Prefs.setInfoPanelTimeoutSec(context, sec)
                },
                onToggleShowInfoOnZap = { enabled ->
                    showInfoOnZap = enabled
                    Prefs.setShowInfoOnZap(context, enabled)
                },
                onToggleShowClock = { enabled ->
                    showClockInPlayer = enabled
                    Prefs.setShowClockInPlayer(context, enabled)
                },
                onToggleShowExactResolution = { enabled ->
                    showExactResolution = enabled
                    Prefs.setShowExactResolution(context, enabled)
                },
                onToggleShowFavoritesGroup = { enabled ->
                    showFavoritesGroup = enabled
                    Prefs.setShowFavoritesGroup(context, enabled)
                },
                onToggleShowAllGroup = { enabled ->
                    showAllChannelsGroup = enabled
                    Prefs.setShowAllChannelsGroup(context, enabled)
                },
                onSelectVideoDecoderMode = { mode ->
                    Prefs.setVideoDecoderMode(context, mode)
                    groupConfigsVersion++
                    notifySettings(AppStrings.tr(appLanguage, "解码器设置将在下次换台生效", "Decoder change applies on next zap"))
                },
                onToggleRebuild4kZap = { enabled ->
                    Prefs.setRebuildOn4kZap(context, enabled)
                    groupConfigsVersion++
                },
                onToggleDefaultSubtitles = { enabled ->
                    Prefs.setDefaultSubtitlesEnabled(context, enabled)
                    groupConfigsVersion++
                },
                onSelectSubtitlePreferredLang = { langCode ->
                    Prefs.setSubtitlePreferredLang(context, langCode)
                    groupConfigsVersion++
                },
                onUpdateSourceGroupConfig = { sourceId, cfg ->
                    Prefs.saveSourceGroupConfig(context, sourceId, cfg)
                    groupConfigsVersion++
                },
            )
        }

        SettingsDrawer(state = settingsState, actions = settingsActions)

        AudioTrackDialog(
            visible = audioDialogOpen,
            items = AudioTrackUi.buildItems(audioTracks, audioOverrideGroup, audioOverrideTrack, appLanguage),
            hasAudio = audioTracks.isNotEmpty(),
            onSelect = { item ->
                applyAudioOverride(item.groupIndex, item.trackIndex)
                uiController.closeAudioDialog()
                uiController.showInfoCard(focused = false, manual = true)
            },
            onClose = { uiController.closeAudioDialog() },
        )

        SubtitleTrackDialog(
            visible = subtitleDialogOpen && !playback.isInPipMode,
            items = SubtitleTrackUi.buildItems(subtitleTracks, subtitleDisabled, subtitleOverrideGroup, subtitleOverrideTrack, appLanguage),
            hasSubtitles = subtitleTracks.isNotEmpty(),
            onSelect = { item ->
                applySubtitleOverride(item)
                subtitleDialogOpen = false
                uiController.showInfoCard(focused = false, manual = true)
            },
            onClose = { subtitleDialogOpen = false },
        )

        RecentChannelsDialog(
            visible = recentChannelsOpen,
            items = recentResolved,
            onSelect = { index ->
                recentResolved.getOrNull(index)?.let { item ->
                    if (switchToRecent(item)) uiController.closeRecentDialog()
                }
            },
            onClear = {
                recentWriteJob?.cancel()
                recentWriteJob = null
                recentEntries = recentCache.clearAll(channelGroups.getOrNull(currentGroupIndex)?.key)
                Log.i("IQTV-RECENT", RecentDialogTags.clearedLog(recentEntries.size))
                Toast.makeText(context, AppStrings.tr(appLanguage, "已清空最近观看", "Cleared recent history"), Toast.LENGTH_SHORT).show()
            },
            onClose = { uiController.closeRecentDialog() },
        )

        QualitySelectorDialog(
            visible = qualityDialogOpen,
            channelTitle = qualityDialogGroup?.displayTitle.orEmpty(),
            variants = qualityVariants,
            onSelect = { variantIndex ->
                playGroupVariant(qualityDialogGroupIndex, variantIndex, armZap = true)
            },
            onClose = { uiController.closeQualityDialog() },
        )

        // 底部信息卡
        val req = catchupRequest
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            val catchupConfig = req?.let {
                CatchupConfiguration(it.startMillis, it.endMillis, catchupPositionMs)
            }
            val displayChannel = if (req != null) {
                allChannels.firstOrNull { it.url == req.liveUrl }
            } else currentChannel

            val bannerChannel = displayChannel?.let { ch ->
                val cleanTitle = channelGroups.getOrNull(playingGroupIndex)?.displayTitle
                    ?: EpgNormalize.displayName(ch.title)
                if (cleanTitle == ch.title) ch else ch.copy(title = cleanTitle)
            }
            val displayTitle = if (req != null) {
                "${req.programTitle} (${AppStrings.tr(appLanguage, "回看中", "Catchup")})"
            } else {
                if (currentLive.retainedOutsideList) null else displayChannel?.let { nowProgramByUrl[it.url]?.title }
            }
            val displayProgress = if (req != null || currentLive.retainedOutsideList) null else {
                displayChannel?.let { nowProgramByUrl[it.url]?.progress }
            }
            val displayNumber = if (req != null) {
                val idx = channelGroups.indexOfFirst { g -> g.variants.any { it.channel.url == req.liveUrl } }
                if (idx >= 0) idx + 1 else 0
            } else currentLive.number

            val bannerProgram = if (req != null) null else bannerChannel?.let { nowProgramByUrl[it.url] }
            val bannerGroup = if (req != null) {
                channelGroups.firstOrNull { g -> g.variants.any { it.channel.url == req.liveUrl } }
            } else playingGroup

            val fpsResolution = FrameRateEstimator.resolve(videoFormat?.frameRate ?: -1f, inferredFrameRate, fallback = -1f)

            ChannelInfoBanner(
                state = if (playback.isInPipMode) InfoCardPolicy.State.HIDDEN else infoCardState,
                manual = infoCardOverlay?.manual == true,
                seekOverlayVisible = isSeekOverlayVisible && !playback.isInPipMode,
                channel = bannerChannel,
                programTitle = displayTitle,
                nextProgramTitle = bannerProgram?.nextTitle,
                nextProgramStartMs = bannerProgram?.nextStartMillis ?: 0L,
                programProgress = displayProgress,
                channelNumber = displayNumber,
                videoFormat = videoFormat,
                frameRateFallback = fpsResolution.value,
                audioLabel = audioSummaryText,
                audioChannelCount = AudioTrackUi.selectedChannelCount(audioTracks, audioOverrideGroup, audioOverrideTrack),
                catchupConfiguration = catchupConfig,
                programStartMs = catchupConfig?.startMillis ?: (bannerProgram?.startMillis ?: 0L),
                programEndMs = catchupConfig?.endMillis ?: (bannerProgram?.endMillis ?: 0L),
                nowMillis = nowMillis,
                groupLabel = bannerGroup?.let { ChannelGrouper.displayGroupName(it) },
                recentCards = cardRecentItems,
                quickActions = bannerQuickActions,
                focusIndex = infoCardFocusIndex,
                onMoveFocus = { delta ->
                    val recentShownCount = if (catchupRequest == null && !isSeekOverlayVisible) cardRecentItems.size else 0
                    uiController.moveInfoCardFocus(delta, recentShownCount + bannerQuickActions.size)
                },
                onSwitchRecent = { index ->
                    InfoCardPolicy.resolveRecentTarget(cardRecentItems, index)?.let { item ->
                        uiController.noteInfoCardInteraction()
                        if (switchToRecent(item)) uiController.focusInfoCard(0)
                    }
                },
                onTriggerQuickAction = ::triggerQuickAction,
                onExitFocus = { uiController.unfocusInfoCard() },
                onFocusedChanged = { /* 单源状态已在 uiController，无需反向镜像 */ },
                uiScale = uiScale,
                uiAlpha = Prefs.uiSurfaceAlpha(uiTransparencyPct),
                showExactResolution = showExactResolution,
                animate = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun buildPrefetchLogoUrls(channels: List<Channel>, currentIndex: Int): List<String> {
    if (channels.isEmpty()) return emptyList()
    val maxUrls = 120
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
    for (i in 0 until minOf(60, channels.size)) addAt(i)
    return out
}

private fun clearAudioOverride(player: Player) {
    val params = player.trackSelectionParameters
    if (params.overrides.isEmpty() && !params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return
    player.trackSelectionParameters = params.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .build()
}

private const val ZAP_TAG = "IQTV-ZAP"
