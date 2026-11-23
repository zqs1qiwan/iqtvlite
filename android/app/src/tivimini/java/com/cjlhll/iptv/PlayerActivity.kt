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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.flow.first
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
    private var setPlayLastChannel: (() -> Boolean)? = null
    private var setRecentChannelsOpen: ((Boolean) -> Unit)? = null
    private var setAudioDialogOpen: ((Boolean) -> Unit)? = null
    private var digits by mutableStateOf("")
    // V4 §7：确认态（OK/超时提交前置 true），OSD 数字短暂变品牌蓝加粗。
    private var digitConfirmed by mutableStateOf(false)
    private var numberJob: Job? = null
    private fun submitNumber() {
        numberJob?.cancel()
        val number = digits.toIntOrNull()
        digitConfirmed = true
        if (number == null || onNumberJump?.invoke(number) != true) {
            android.widget.Toast.makeText(this, "没有频道 $digits", android.widget.Toast.LENGTH_SHORT).show()
        }
        digits = ""
        // V4 §7：确认态保留一帧（~180ms）让用户看到蓝字，然后复位（digits 已空 OSD 自然消失）。
        lifecycleScope.launch {
            delay(180)
            digitConfirmed = false
        }
    }
    override fun onStop() {
        numberJob?.cancel(); digits = ""; digitConfirmed = false
        super.onStop()
    }
    private var onChannelStep: ((Int) -> Boolean)? = null
    private var isDrawerOpen: Boolean = false
    private val sidebarController = SidebarController()
    private var drawerActiveColumn: DrawerColumn? = null
    private var setSettingsDrawerOpen: ((Boolean) -> Unit)? = null
    private var isSettingsDrawerOpen: Boolean = false
    // t13（SETTINGS_IA_V2 §5.1）：设置二级页的 BACK 处理器（true = 已从二级退回一级）。
    private var settingsBackHandler: (() -> Boolean)? = null
    private var setQualityDialogOpen: ((Boolean) -> Unit)? = null
    private var isQualityDialogOpen: Boolean = false
    private var isRecentChannelsOpen: Boolean = false
    private var isAudioDialogOpen: Boolean = false
    // t186 (F1): remote habits. t193 (REMOTE-INSTANT-01): a settings change pushes the new value
    // straight into this field through a controller, so the NEXT key event already uses it even
    // though the SettingsDrawer lives in the same Activity and never triggers onResume().
    private var remote = RemoteHabits.DEFAULT
    // FULLFIX §7：功能→按键绑定（新旧并存；dispatchKeyEvent 读这份）。mutableStateOf 让设置页
    // 在捕获写入后立即重组显示新摘要（Activity 里已有 digits by mutableStateOf 的先例）。
    private var remoteBindings by mutableStateOf(RemoteBindings.DEFAULT)
    /** FULLFIX §7：设置页按键捕获请求；非 null 时下一个按键只被记录，不执行原动作。 */
    private var captureRequest by mutableStateOf<Pair<RemoteAction, Boolean>?>(null) // action to longPress?
    private var setInfoBannerOpen: ((Boolean) -> Unit)? = null
    private var isInfoBannerOpen: Boolean = false
    // t4 (INFO_CARD_V2 §4.2-1/2)：卡片聚焦是独立于“可见”的正交状态——
    // BACK 在 IDLE 一次关卡片，在 FOCUSED 只退聚焦，混用一个 Boolean 会双触发。
    private var setInfoCardOk: (() -> Boolean)? = null
    /** t32 Bug1：主动 OK 出卡控制器（composable 注册，按 infoOkMode 决定 DIRECT/TWO_STEP）。 */
    private var openInfoCardFromOk: (() -> Boolean)? = null
    /**
     * t35：FOCUSED 态按键确定性闭环控制器（composable 注册）。
     * (delta 或 0=switch, action) —— 用枚举参数区分移动/切换/退聚焦。
     */
    private var infoCardFocusedAction: ((InfoCardPolicy.FocusedKeyAction) -> Boolean)? = null
    private var setInfoCardFocused: ((Boolean) -> Unit)? = null
    private var isInfoCardFocused: Boolean = false
    private var isCatchupPlayback: Boolean = false
    private var onCatchupSeek: ((Long) -> Boolean)? = null
    private var onReturnToLive: (() -> Boolean)? = null
    private var lastBackPressTime: Long = 0
    private var lastBackPressTimeCatchup: Long = 0

    // t186 (F1): the habits are re-read here (and at start) so a change in settings takes effect on
    // the very next key event without restarting playback.
    override fun onResume() {
        super.onResume()
        remote = Prefs.getRemoteSettings(this)
        remoteBindings = Prefs.getRemoteBindings(this)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = Prefs.getRemoteSettings(this)
        remoteBindings = Prefs.getRemoteBindings(this)

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
        val composeOverlay = androidx.compose.ui.platform.ComposeView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        rootLayout.addView(composeOverlay, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(rootLayout)

        composeOverlay.setContent {
            val colorScheme = darkColorScheme(
                primary = BrandFocus.BlueColor,
                onPrimary = ComposeColor.White,
                secondary = ComposeColor(0xFF757575),
                onSecondary = ComposeColor.Black,
                tertiary = ComposeColor(0xFF616161),
                onTertiary = ComposeColor.White,
                background = ComposeColor(0xFF121212),
                onBackground = ComposeColor.White,
                surface = ComposeColor(0xFF1E1E1E),
                onSurface = ComposeColor.White,
                // V4 §9：焦点统一品牌蓝（浅底 + 白字），所有 surfaceVariant 焦点点自动变蓝。
                surfaceVariant = BrandFocus.FocusBgColor,
                onSurfaceVariant = ComposeColor.White
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
                        // t44：按职责聚合回调，VideoPlayerScreen 参数 35 → 9（API28 VerifyError 根修）。
                        playback = PlaybackCallbacks(
                            setOnChannelStep = { onChannelStep = it },
                            setOnNumberJump = { onNumberJump = it },
                            setOnPlayLastChannel = { setPlayLastChannel = it },
                            setOnCatchupSeek = { onCatchupSeek = it },
                            setOnReturnToLive = { onReturnToLive = it },
                            onCatchupPlaybackChanged = { isCatchupPlayback = it },
                            onBack = { finish() },
                        ),
                        overlay = OverlayCallbacks(
                            setRecentChannelsOpenController = { setRecentChannelsOpen = it },
                            onRecentChannelsOpenChanged = { isRecentChannelsOpen = it },
                            setAudioDialogOpenController = { setAudioDialogOpen = it },
                            onAudioDialogOpenChanged = { isAudioDialogOpen = it },
                            onDrawerOpenChanged = { isDrawerOpen = it },
                            onDrawerActiveColumnChanged = { drawerActiveColumn = it },
                            setSettingsDrawerOpenController = { setSettingsDrawerOpen = it },
                            onSettingsDrawerOpenChanged = { isSettingsDrawerOpen = it },
                            setSettingsBackHandlerController = { handler -> settingsBackHandler = handler },
                            setQualityDialogOpenController = { setQualityDialogOpen = it },
                            onQualityDialogOpenChanged = { isQualityDialogOpen = it },
                        ),
                        infoCard = InfoCardCallbacks(
                            setInfoBannerOpenController = { setInfoBannerOpen = it },
                            onInfoBannerOpenChanged = { isInfoBannerOpen = it },
                            setInfoCardOkController = { setInfoCardOk = it },
                            setOpenInfoCardFromOkController = { openInfoCardFromOk = it },
                            setInfoCardFocusedActionController = { infoCardFocusedAction = it },
                            onInfoCardFocusedChanged = { isInfoCardFocused = it },
                        ),
                        remoteCbs = RemoteCallbacks(
                            onRemoteSettingsChanged = { settings -> remote = settings },
                            remoteBindings = remoteBindings,
                            captureRequest = captureRequest,
                            onStartCapture = { action, longPress -> captureRequest = action to longPress },
                            onCancelCapture = { captureRequest = null },
                            onBindingsChanged = { bindings -> remoteBindings = bindings },
                        )
                    )
                    if (digits.isNotEmpty()) {
                        // t124: visible digit-entry OSD — dark panel + high-contrast brand ink.
                        // V4 §7：数字 OSD 移到右上角（TopEnd），确认态数字用品牌蓝加粗。
                        val osdDigitColor = if (digitConfirmed) BrandFocus.BlueColor else ComposeColor(0xFFF2F5FA)
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 24.dp, end = 40.dp)
                                .testTag("digit_osd")
                                .background(ComposeColor(0xE610141A.toInt()))
                                .padding(horizontal = 28.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                "请输入频道号",
                                color = ComposeColor(0xFFA9B4C7),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                digits + "_",
                                color = osdDigitColor,
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = if (digitConfirmed) androidx.compose.ui.text.font.FontWeight.Bold
                                    else androidx.compose.ui.text.font.FontWeight.Normal
                                )
                            )
                        }
                    }
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

    // ── FULLFIX §7：功能→按键中央 router ──────────────────────────────────
    /** 当前解析上下文：回看用 CATCHUP，否则 PLAYER_ONLY（覆盖层由各分支守卫先行处理）。 */
    private fun playerContext(): UiContext =
        if (isCatchupPlayback) UiContext.CATCHUP else UiContext.PLAYER_ONLY

    /** 长按判定：isLongPress 或已有 repeat（与 §7.2 一致）。 */
    private fun isLongPressKey(event: KeyEvent): Boolean =
        event.isLongPress || event.repeatCount > 0

    /** 中央解析：任何按键 → 当前绑定下的功能（覆盖层打开时由调用方守卫，不进这里）。 */
    private fun resolvePlayerAction(event: KeyEvent): RemoteAction? =
        RemoteBindings.resolveWith(remoteBindings, event.keyCode, isLongPressKey(event), playerContext())

    /**
     * t10：被 dispatch 的固定 `when(keyCode)` 分支覆盖的按键集合。
     * 通用 fallback 只对**不在本集合**的任意键（如 F1=131）生效，避免与固定分支双触发。
     */
    private fun isFixedRoutedKey(keycode: Int): Boolean = when (keycode) {
        KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> true
        else -> false
    }

    /**
     * 捕获态：下一个按键只记录绑定，不执行原动作（类似 digit OSD 守卫）。
     * @return true 表示该键被捕获消费，调用方应立即 return。
     */
    private fun handleCapture(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val req = captureRequest ?: return false
        // BACK 允许取消捕获（不写入绑定）。
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            captureRequest = null
            return true
        }
        remoteBindings = RemoteBindings.bind(remoteBindings, req.first, event.keyCode, req.second)
        Prefs.setRemoteBindings(this, remoteBindings)
        captureRequest = null
        return true
    }

    // TV Activity must route unhandled DPAD before Compose; only the inherited core bridge is restricted.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            // FULLFIX §7：捕获态优先——设置页点“绑定按键”后，下一个键只写入绑定，不执行原动作。
            if (handleCapture(event)) return true
            // t223: an open overlay must never leave the digit OSD on top of it.
            if (!DigitOsdPolicy.visibleWhen(
                    isDrawerOpen || isSettingsDrawerOpen || isQualityDialogOpen ||
                        isRecentChannelsOpen || isAudioDialogOpen
                ) && digits.isNotEmpty() && event.keyCode !in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9
            ) {
                numberJob?.cancel()
                digits = ""
            }
            if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && !isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen && !isRecentChannelsOpen && !isAudioDialogOpen) {
                if (event.repeatCount == 0) {
                    digits = DigitOsdPolicy.append(digits, event.keyCode - KeyEvent.KEYCODE_0)
                    // t223: EVERY digit (including an append) restarts the 3 s timer, in both modes.
                    numberJob?.cancel()
                    val digitMode = remote.digits
                    numberJob = lifecycleScope.launch {
                        delay(DigitOsdPolicy.TIMEOUT_MS)
                        when (DigitOsdPolicy.timeoutAction(digitMode)) {
                            // 直接跳台: submit after the timeout (unchanged behaviour)
                            DigitOsdPolicy.TimeoutAction.SUBMIT -> submitNumber()
                            // 需确认: only hide the OSD — the user committed nothing, so no jump
                            DigitOsdPolicy.TimeoutAction.CLEAR -> {
                                digits = ""
                                android.util.Log.d("IQTV-DIGITS", "osd timeout cleared")
                            }
                        }
                    }
                }
                return true
            }
            if (digits.isNotEmpty()) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) { numberJob?.cancel(); digits = ""; return true }
                if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) { submitNumber(); return true }
                numberJob?.cancel(); digits = ""
            }
            // t4 (INFO_CARD_V2 §4.2-2)：卡片聚焦时，方向键/OK/BACK 交给 Compose 焦点节点消费
            // （卡片退聚焦、左右移动、OK 切台）。数字键、166/167、上一台、MENU、音量仍走既有分支。
            // 必须放在数字 OSD 分支之后：数字 OSD 的 BACK 清空/OK 提交要优先。
            val infoCardKey = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> true
                else -> false
            }
            // t35（根源修复）：FOCUSED 态的所有信息卡按键必须**确定性闭环**，绝不穿透到下方 when。
            // 旧实现 super 返回 false 时清焦点后继续 when → RIGHT 命中 OPEN_SETTINGS → 覆盖层
            // 把卡片 hide reason=overlay 隐藏（用户实测"一按右键卡片就没了"）。
            // 新契约：先交 Compose（焦点就绪时它自己处理并 return true）；若未消费，则无论焦点节点
            // 是否就绪，都用 composable 注册的确定性控制器执行卡片语义动作，并**总是 return true**。
            if (isInfoCardFocused && infoCardKey) {
                if (super.dispatchKeyEvent(event)) return true
                // Compose 未消费：用确定性语义通道闭环（onMoveFocus/onSwitchRecent/onExitFocus 的等价物）。
                val isCenter = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                val action = InfoCardPolicy.focusedKeyAction(event.keyCode, isCenter)
                if (action != InfoCardPolicy.FocusedKeyAction.NONE) {
                    infoCardFocusedAction?.invoke(action)
                }
                // 无论控制器是否存在都消费：绝不继续 when（杜绝 OPEN_SETTINGS/换台/OSD 穿透）。
                return true
            }
            when (event.keyCode) {
                // t133: last channel — only while no overlay is open; repeats are debounced inside.
                KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    // t141: same guards as every other key — an open overlay must swallow/reject it,
                    // never switch channels underneath (KEYCODE_LAST_CHANNEL is 229 on the remote).
                    if (isDrawerOpen || isSettingsDrawerOpen || isQualityDialogOpen || isRecentChannelsOpen || isAudioDialogOpen) {
                        return false
                    }
                    if (event.repeatCount == 0 && setPlayLastChannel?.invoke() == true) return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen && !isRecentChannelsOpen && !isAudioDialogOpen) {
                        // t4 (INFO_CARD_V2 D-C2)：两段式 OK —— 卡片已可见且习惯是“显示信息卡”时，
                        // FULLFIX §7：OK 目标由中央 router 解析（默认等价旧 onOk：信息卡/打开列表）。
                        val okAction = resolvePlayerAction(event)
                        // 两段式 OK：卡片已可见且当前 OK 绑定=显示信息卡时，先进入最近观看聚焦。
                        if (isInfoBannerOpen && okAction == RemoteAction.SHOW_INFO &&
                            setInfoCardOk?.invoke() == true
                        ) return true
                        when (okAction) {
                            RemoteAction.OPEN_DRAWER -> sidebarController.openDrawer()
                            RemoteAction.SHOW_INFO -> {
                                if (isInfoBannerOpen) {
                                    // 卡片已开：第二段 OK 进入最近观看聚焦（TWO_STEP 语义，DIRECT 下也幂等）。
                                    if (setInfoCardOk?.invoke() == true) return true
                                } else {
                                    // t32 Bug1：主动 OK 出卡 —— 由 composable 注册的控制器按 infoOkMode 决定
                                    // DIRECT（直接 FOCUSED 可选最近观看）/ TWO_STEP（IDLE）。
                                    if (openInfoCardFromOk?.invoke() == true) return true
                                    setInfoBannerOpen?.invoke(true)
                                }
                            }
                            else -> Unit
                        }
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen && !isRecentChannelsOpen && !isAudioDialogOpen) {
                        // FULLFIX §7：LEFT 由 router 解析（默认 PLAYER_ONLY=打开列表 / CATCHUP=快退）。
                        when (resolvePlayerAction(event)) {
                            RemoteAction.SEEK_BACK -> {
                                val step = calculateSeekStep(event.repeatCount)
                                if (onCatchupSeek?.invoke(-step) == true) return true
                            }
                            RemoteAction.OPEN_DRAWER -> { sidebarController.openDrawer(); return true }
                            else -> Unit
                        }
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen && !isRecentChannelsOpen && !isAudioDialogOpen) {
                        // FULLFIX §7：RIGHT 由 router 解析（默认 PLAYER_ONLY=打开设置 / CATCHUP=快进）。
                        val resolved = resolvePlayerAction(event)
                        // V4 §3（A 方案）：默认绑定 + 「全部」上下文 → RIGHT 特例进频道列表；
                        // 用户自定义 RIGHT（或回看 SEEK_FORWARD）不受影响，尊重遥控全映射。
                        val action = RemoteBindings.resolveRight(
                            resolved = resolved,
                            selectedGroup = sidebarController.value.selectedGroup,
                            inGroupColumn = sidebarController.value.activeColumn == DrawerColumn.Groups,
                            rightCustomBound = RemoteBindings.isKeyCustomBound(remoteBindings, RemoteBindings.KEY_DPAD_RIGHT),
                        )
                        when (action) {
                            RemoteAction.SEEK_FORWARD -> {
                                val step = calculateSeekStep(event.repeatCount)
                                if (onCatchupSeek?.invoke(step) == true) return true
                            }
                            RemoteAction.OPEN_SETTINGS -> { setSettingsDrawerOpen?.invoke(true); return true }
                            RemoteAction.OPEN_DRAWER -> { sidebarController.openDrawer(); return true }
                            else -> Unit
                        }
                    }
                }

                KeyEvent.KEYCODE_MENU -> {
                    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen && !isRecentChannelsOpen && !isAudioDialogOpen) {
                        // FULLFIX §7：MENU 由 router 解析（默认=打开设置）。
                        if (resolvePlayerAction(event) == RemoteAction.OPEN_SETTINGS) {
                            setSettingsDrawerOpen?.invoke(true)
                            return true
                        }
                    }
                }

                KeyEvent.KEYCODE_BACK -> {
                    if (isAudioDialogOpen) {
                        setAudioDialogOpen?.invoke(false)
                        return true
                    }
                    if (isRecentChannelsOpen) {
                        setRecentChannelsOpen?.invoke(false)
                        return true
                    }
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
                        // t13：二级页先退到一级（只退一层）；一级再 BACK 才关抽屉。
                        if (settingsBackHandler?.invoke() == true) return true
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

                    // t186 (F1): 直接退出 exits on the first BACK; 关闭抽屉 keeps the historical
                    // double-press guard so a stray press never kills playback.
                    // FULLFIX §7：是否"直接退出"由绑定决定（DEFAULT 的 BACK 长按=RETURN_LIVE，
                    // 短按不绑 EXIT_APP → 保持双按退出；migrate(back=EXIT) 才立即退出）。
                    val backIsExit = resolvePlayerAction(event) == RemoteAction.EXIT_APP ||
                        (isLongPressKey(event) && RemoteBindings.resolveWith(
                            remoteBindings, event.keyCode, true, UiContext.PLAYER_ONLY
                        ) == RemoteAction.EXIT_APP)
                    if (backIsExit) {
                        // t193 (REMOTE-BACK-01): explicit finish(); the habit no longer depends on the
                        // key reaching the system back dispatch.
                        finish()
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
                    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen && !isRecentChannelsOpen && !isAudioDialogOpen) {
                        if (isCatchupPlayback) {
                            // FULLFIX §7：回看 UP → SHOW_INFO（router 的 CATCHUP 语义）。
                            if (resolvePlayerAction(event) == RemoteAction.SHOW_INFO) setInfoBannerOpen?.invoke(true)
                            return true
                        }
                        // FULLFIX §7：UP 的具体动作由 router 解析（默认换台，可重绑呼出列表）。
                        when (resolvePlayerAction(event)) {
                            RemoteAction.CHANNEL_UP -> if (onChannelStep?.invoke(-1) == true) return true
                            RemoteAction.OPEN_DRAWER -> { sidebarController.openDrawer(); return true }
                            else -> Unit
                        }
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen && !isRecentChannelsOpen && !isAudioDialogOpen) {
                        if (isCatchupPlayback) {
                            if (resolvePlayerAction(event) == RemoteAction.SHOW_INFO) setInfoBannerOpen?.invoke(true)
                            return true
                        }
                        // FULLFIX §7：DOWN 的具体动作由 router 解析（默认换台，可重绑呼出列表）。
                        when (resolvePlayerAction(event)) {
                            RemoteAction.CHANNEL_DOWN -> if (onChannelStep?.invoke(1) == true) return true
                            RemoteAction.OPEN_DRAWER -> { sidebarController.openDrawer(); return true }
                            else -> Unit
                        }
                    }
                }
            }

            // ── t10：通用 fallback —— 任意自定义键（F1=131 等）走中央 router ──────────
            // 固定 when(keyCode) 分支未覆盖的 ACTION_DOWN 键，若已被绑定则按 RemoteAction 分发。
            // 守卫：捕获态（handleCapture，最前）、数字 OSD、信息卡 FOCUSED（均已在上方处理）、
            // 遮罩打开（overlayOpen）一律不走 fallback；resolvePlayerAction 内部按 CATCHUP/PLAYER_ONLY
            // 上下文解析。固定键由上面的分支处理并 return，不会走到这里（避免双触发）。
            if (!isFixedRoutedKey(event.keyCode) &&
                !isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen &&
                !isRecentChannelsOpen && !isAudioDialogOpen &&
                !isInfoCardFocused && digits.isEmpty()
            ) {
                when (resolvePlayerAction(event)) {
                    RemoteAction.CHANNEL_UP -> if (onChannelStep?.invoke(-1) == true) return true
                    RemoteAction.CHANNEL_DOWN -> if (onChannelStep?.invoke(1) == true) return true
                    RemoteAction.OPEN_DRAWER -> { sidebarController.openDrawer(); return true }
                    RemoteAction.OPEN_SETTINGS -> { setSettingsDrawerOpen?.invoke(true); return true }
                    RemoteAction.SHOW_INFO -> { setInfoBannerOpen?.invoke(true); return true }
                    RemoteAction.EXIT_APP -> { finish(); return true }
                    RemoteAction.SEEK_BACK -> {
                        val step = calculateSeekStep(event.repeatCount)
                        if (onCatchupSeek?.invoke(-step) == true) return true
                    }
                    RemoteAction.SEEK_FORWARD -> {
                        val step = calculateSeekStep(event.repeatCount)
                        if (onCatchupSeek?.invoke(step) == true) return true
                    }
                    else -> Unit // NONE / 未绑定 / RETURN_LIVE 等 → 不消费
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
    playback: PlaybackCallbacks,
    overlay: OverlayCallbacks,
    infoCard: InfoCardCallbacks,
    remoteCbs: RemoteCallbacks
) {
    // t44：从聚合对象解包为局部 val，body 逻辑零改动（仅减少方法参数/寄存器数量）。
    val setOnChannelStep = playback.setOnChannelStep
    val setOnNumberJump = playback.setOnNumberJump
    val setOnPlayLastChannel = playback.setOnPlayLastChannel
    val setOnCatchupSeek = playback.setOnCatchupSeek
    val setOnReturnToLive = playback.setOnReturnToLive
    val onCatchupPlaybackChanged = playback.onCatchupPlaybackChanged
    val onBack = playback.onBack

    val setRecentChannelsOpenController = overlay.setRecentChannelsOpenController
    val onRecentChannelsOpenChanged = overlay.onRecentChannelsOpenChanged
    val setAudioDialogOpenController = overlay.setAudioDialogOpenController
    val onAudioDialogOpenChanged = overlay.onAudioDialogOpenChanged
    val onDrawerOpenChanged = overlay.onDrawerOpenChanged
    val onDrawerActiveColumnChanged = overlay.onDrawerActiveColumnChanged
    val setSettingsDrawerOpenController = overlay.setSettingsDrawerOpenController
    val onSettingsDrawerOpenChanged = overlay.onSettingsDrawerOpenChanged
    val setSettingsBackHandlerController = overlay.setSettingsBackHandlerController
    val setQualityDialogOpenController = overlay.setQualityDialogOpenController
    val onQualityDialogOpenChanged = overlay.onQualityDialogOpenChanged

    val setInfoBannerOpenController = infoCard.setInfoBannerOpenController
    val onInfoBannerOpenChanged = infoCard.onInfoBannerOpenChanged
    val setInfoCardOkController = infoCard.setInfoCardOkController
    val setOpenInfoCardFromOkController = infoCard.setOpenInfoCardFromOkController
    val setInfoCardFocusedActionController = infoCard.setInfoCardFocusedActionController
    val onInfoCardFocusedChanged = infoCard.onInfoCardFocusedChanged

    val onRemoteSettingsChanged = remoteCbs.onRemoteSettingsChanged
    val remoteBindings = remoteCbs.remoteBindings
    val captureRequest = remoteCbs.captureRequest
    val onStartCapture = remoteCbs.onStartCapture
    val onCancelCapture = remoteCbs.onCancelCapture
    val onBindingsChanged = remoteCbs.onBindingsChanged

    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    // t234: the last prepared request (replayed by the rebuild ladder) and its debounce flag.
    val rebuildState = remember { arrayOfNulls<MediaPlaybackRequest>(1) }
    val rebuildBusy = remember { booleanArrayOf(false) }

    val sidebarState by sidebarController.state.collectAsState()
    val drawerOpen = sidebarState.drawerOpen
    val drawerActiveColumn = sidebarState.activeColumn
    var settingsDrawerOpen by remember { mutableStateOf(false) }
    var qualityDialogOpen by remember { mutableStateOf(false) }
    // t133: recent-channels list + last-channel debounce bookkeeping.
    var recentChannelsOpen by remember { mutableStateOf(false) }
    // t159 (Back 双退): publish the open flag to the Activity *synchronously* with the state change.
    // A LaunchedEffect-only mirror leaves a window where BACK still takes the drawer/finish path.
    fun setRecentOpen(open: Boolean) {
        recentChannelsOpen = open
        onRecentChannelsOpenChanged(open)
    }
    LaunchedEffect(recentChannelsOpen) { onRecentChannelsOpenChanged(recentChannelsOpen) }
    var recentTick by remember { mutableIntStateOf(0) }
    var lastChannelSwitchAt by remember { mutableLongStateOf(0L) }
    // PERF-01/DEBOUNCE-01/t159: the list lives in memory (source of truth for the UI and 上一台);
    // the debounced write only mirrors it to Prefs, and a clear is durable (see RecentChannelsCache).
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
    // t154: perf-isolation switches (persisted; default on).
    var logoPrefetchEnabled by remember { mutableStateOf(Prefs.isLogoPrefetchEnabled(context)) }
    var logoLoadEnabled by remember { mutableStateOf(Prefs.isLogoLoadEnabled(context)) }
    // t189 (MANUAL-POLICY-01): every manual EPG request funnels through this single flow, which the
    // EPG effect consumes once — the only path to AutoUpdateScheduler.requestManualEpg.
    val manualEpgRequests = remember { kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val requestManualEpg: () -> Unit = { manualEpgRequests.tryEmit(Unit) }
    // t175 (F4): audio tracks of the current item + manual override (-1 = 自动).
    var audioTracks by remember { mutableStateOf<List<AudioTrackUi.Raw>>(emptyList()) }
    var audioOverrideGroup by remember { mutableIntStateOf(-1) }
    var audioOverrideTrack by remember { mutableIntStateOf(-1) }
    var audioDialogOpen by remember { mutableStateOf(false) }
    // t197 (B): EPG cache is loaded only after the first frame is on screen.
    var firstFrameRendered by remember { mutableStateOf(false) }
    // t179 (AUDIO-BACK-01): mirror the audio dialog state so the Activity can guard every key.
    // t187 (BACK-03): SideEffect pushes the state on every recomposition instead of relying on a
    // LaunchedEffect keyed only by the value (which could be skipped while settling).
    SideEffect { onAudioDialogOpenChanged(audioDialogOpen) }
    // t172 (F3): automatic playlist/EPG update policy (persisted; default 启动时).
    var autoUpdatePlaylist by remember { mutableStateOf(Prefs.getAutoUpdatePlaylist(context)) }
    // t186 (F1): remote habits (persisted; the Activity re-reads them on resume).
    var remoteHabits by remember { mutableStateOf(Prefs.getRemoteSettings(context)) }
    // t205: start-up buffer tier (applies on the next player creation / channel switch).
    var playbackBufferMin by remember { mutableStateOf(Prefs.getPlaybackBufferMin(context)) }
    // t218: neighbour pre-connect experiment (default off; opt-in from 播放与显示).
    var zapPreconnectEnabled by remember { mutableStateOf(Prefs.getZapPreconnectEnabled(context)) }
    // t220: multicast pre-warm experiment (default off).
    var multicastPrewarmEnabled by remember { mutableStateOf(Prefs.getMulticastPrewarmEnabled(context)) }
    // t231: 睛彩 diagnosis experiment (default off).
    var jingcaiExperiment by remember { mutableStateOf(JingcaiExperiment.parse(Prefs.getJingcaiExperiment(context))) }
    // t193 (REMOTE-INSTANT-01): every habits change reaches the Activity immediately, without any
    // pause/resume cycle — the settings drawer lives in the same Activity.
    SideEffect { onRemoteSettingsChanged(remoteHabits) }
    var autoUpdateEpg by remember { mutableStateOf(Prefs.getAutoUpdateEpg(context)) }
    var autoUpdateTick by remember { mutableIntStateOf(0) }
    var qualityDialogGroupIndex by remember { mutableIntStateOf(-1) }
    // t4 (INFO_CARD_V2)：卡片三态 + 焦点索引 + 计时重开 tick（取代旧 Boolean infoBannerOpen）。
    var infoCardState by remember { mutableStateOf(InfoCardPolicy.State.HIDDEN) }
    var infoCardFocusIndex by remember { mutableIntStateOf(0) }
    var infoCardTimerTick by remember { mutableIntStateOf(0) }
    // t32 Bug1：信息卡 OK 行为模式（设置项，DIRECT 默认；改动立即生效）。
    var infoOkMode by remember { mutableStateOf(Prefs.getInfoOkMode(context)) }

    fun logInfoCard(message: String) {
        if (android.util.Log.isLoggable(INFO_CARD_TAG, android.util.Log.DEBUG)) {
            android.util.Log.d(INFO_CARD_TAG, message)
        }
    }

    // t4：进入 IDLE 并重新计时（换台/上一台/数字跳台/音轨切换/回看 UP-DOWN 共用，静默 7s 自动消失）。
    fun showInfoCard() {
        infoCardState = InfoCardPolicy.State.IDLE
        infoCardFocusIndex = 0
        infoCardTimerTick++
        logInfoCard("show state=IDLE recent=${recentEntries.size}")
    }

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
    // FULLFIX B2③：EPG 空态三分类所需的来源配置/加载失败状态（会话内，随每次加载更新）。
    val epgSourceConfigured = remember(context) { Prefs.getEpgSource(context).isNotBlank() }
    var epgLoadFailed by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    // t40：渲染帧间隔推断的真实 fps（由 StreamPlayback.Metrics 统计；播放中每秒刷新一次）。
    var inferredFrameRate by remember { mutableStateOf<Float?>(null) }
    var fpsSource by remember { mutableStateOf(FrameRateEstimator.Source.FALLBACK) }
    LaunchedEffect(player, firstFrameRendered) {
        if (!firstFrameRendered) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            inferredFrameRate = StreamPlayback.inferredFrameRate(player)
        }
    }
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
    fun prepareRequest(p: ExoPlayer, request: MediaPlaybackRequest): PreparedPlayback {
        rebuildState[0] = request
        return StreamPlayback.prepare(
            p, context, request, playbackIntent,
            onFailure = { playbackErrorEvents.trySend(it) },
        )
    }


    val allChannels = remember(channelGroups) {
        ChannelGrouper.allChannels(channelGroups)
    }

    val playingGroupIndex = currentLive.groupIndex
    val playingGroup = channelGroups.getOrNull(playingGroupIndex)
    val playingVariantIndex = currentLive.variantIndex.coerceAtLeast(0)
    val epgLoadTag = remember { "EPG" }

    var heavyWorkEnabled by remember { mutableStateOf(false) }
    var firstDrawerOpenSeen by remember { mutableStateOf(false) }

    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        delay(1500)
        heavyWorkEnabled = true
    }

    fun initializePlayer(softVideoOverride: Boolean = false, prepareNow: Boolean = true) {
        if (player != null) return

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                // t157 (A/B proven): 1000/6000/300/1000 kept the loader awake on a live progressive-TS
                // feed — Loader 5.0%→16.9%, Playback 11.6%→18.1%, app CPU 31.2%→44.2% (CI10093 vs
                // CI10101, same channel, 60 s steady state, rebuffers=0).
                // 2500/6000/500/2000 gives the loader a margin so it fills and then idles.
                // Constraint kept: minBufferMs(2500) >= bufferForPlayback(500) and
                // >= bufferForPlaybackAfterRebuffer(2000) — an 800/…/1000 set crashed at start
                // (USAB2-01 / CI10077), never set minBufferMs below afterRebuffer.
                // t197: numbers live in LoadControlTuning so an A/B arm is one constant.
                // t27/t28：min/max/afterRebuffer 支持运行时实验开关（未设置时=生产默认）。
                PerfTuning.minBufferMs(context),
                PerfTuning.maxBufferMs(context),
                // t205: start-up buffer comes from the user's tier (300/400/500, default 500).
                LoadControlTuning.playbackBufferMinMs(Prefs.getPlaybackBufferMin(context)),
                PerfTuning.afterRebufferMs(context)
            )
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // t205: prove the tier took effect in an A/B (`setprop log.tag.IQTV-ZAP DEBUG`).
        if (android.util.Log.isLoggable(ZAP_TAG, android.util.Log.DEBUG)) {
            android.util.Log.d(
                ZAP_TAG,
                "bufferMin=${LoadControlTuning.playbackBufferMinMs(Prefs.getPlaybackBufferMin(context))}"
            )
        }

        // t234: only the 睛彩 soft-fallback step prepends the FFmpeg video renderer; every other build
        // (including the first rebuild step) keeps the exact hardware-only path.
        val renderersFactory = JingcaiRenderersFactory(context, softVideo = softVideoOverride)
            .setEnableDecoderFallback(true)
            // t117: use the FFmpeg audio extension ONLY as a fallback when the platform decoder
            // can't decode the track (e.g. MP2 / audio-mpeg-L2). ON (not PREFER) keeps AVC/AAC on
            // MediaCodec so hardware decode and A/V sync are unchanged.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
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

        // t234: the rebuild path prepares the recorded request itself (no zap re-arm, no 2nd ZAP line).
        if (!prepareNow) {
            playerView.player = newPlayer
            player = newPlayer
            return
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

    /**
     * t234: the 睛彩 rebuild path. The old player is released and a fresh one is built (a new MediaCodec
     * attempt), then the SAME channel is replayed **without** re-arming the zap stopwatch, so no second
     * `IQTV-ZAP` line appears. The last ladder step builds it with the FFmpeg video renderer.
     */
    fun rebuildPlayer(softVideo: Boolean) {
        if (rebuildBusy[0]) return           // t234 debounce: one rebuild at a time
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

                override fun onRenderedFirstFrame() {
                    val now = android.os.SystemClock.elapsedRealtime()
                    ZapTrace.onFirstFrame(now)
                    // t197 (B): the EPG cache may only be read once the first frame is on screen.
                    firstFrameRendered = true
                    if (android.util.Log.isLoggable(ZAP_TAG, android.util.Log.DEBUG)) {
                        ZapTrace.summaryLineOnce()?.let { android.util.Log.d(ZAP_TAG, it) }
                    }
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    videoFormat = p.videoFormat
                    // t179 (AUDIO-OVERRIDE-01): only REFRESH the list here — the override is reset on
                    // a media-item transition, otherwise applying it would immediately fall back to
                    // 自动 and lose the ▶ marker.
                    audioTracks = TracksFlattening.audioTracks(tracks)
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    // t187 (AUDIO-OVERRIDE-02): Media3 delivers this on the application looper, so we
                    // may touch the player here. Clear the actual override — resetting only the UI
                    // state left the old TrackSelectionOverride applied on the next channel.
                    audioOverrideGroup = -1
                    audioOverrideTrack = -1
                    clearAudioOverride(p)
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
        setSettingsDrawerOpenController { settingsDrawerOpen = it }
        // t13：抽屉自己通过 onBackHandlerReady 注册二级 BACK 处理器（见 SettingsDrawer 调用点）。
        setQualityDialogOpenController { qualityDialogOpen = it }
        setInfoBannerOpenController { open ->
            // t4：Activity 侧的旧入口（OK 习惯 SHOW_INFO / 回看 UP-DOWN）仍然只表达“显示卡片”。
            infoCardState = if (open) InfoCardPolicy.State.IDLE else InfoCardPolicy.State.HIDDEN
            if (!open) infoCardFocusIndex = 0
            infoCardTimerTick++
        }
        onDispose {
            setSettingsDrawerOpenController(null)
            setSettingsBackHandlerController(null)
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

    // t4 (INFO_CARD_V2 §3.3)：可见性镜像用 SideEffect（每次重组都推），计时交给 InfoCardPolicy。
    // t16（F1）：**聚焦镜像不再由 state 推导**（state 仍是 FOCUSED 但 Compose 可能已无焦点节点），
    // 改由 ChannelInfoBanner 聚合“条内是否真有节点持有焦点”后回调 onInfoCardFocusedChanged。
    SideEffect {
        onInfoBannerOpenChanged(infoCardState != InfoCardPolicy.State.HIDDEN)
    }

    // 自动隐藏：IDLE 3000ms；FOCUSED 不隐藏（autoHideMs = null）；换台/计时重开都会重启本 effect。
    LaunchedEffect(infoCardState, infoCardTimerTick, currentGroupIndex, currentVariantIndex) {
        val hideMs = InfoCardPolicy.autoHideMs(infoCardState) ?: return@LaunchedEffect
        delay(hideMs)
        if (infoCardState == InfoCardPolicy.State.IDLE) {
            infoCardState = InfoCardPolicy.State.HIDDEN
            infoCardFocusIndex = 0
            logInfoCard("hide reason=timer")
        }
    }

    // 覆盖层（抽屉/设置/线路/音轨/最近观看）打开 → 卡片立即隐藏，避免叠层（§4.2-5）。
    LaunchedEffect(drawerOpen, settingsDrawerOpen, qualityDialogOpen, recentChannelsOpen, audioDialogOpen) {
        if (drawerOpen || settingsDrawerOpen || qualityDialogOpen || recentChannelsOpen || audioDialogOpen) {
            if (infoCardState != InfoCardPolicy.State.HIDDEN) logInfoCard("hide reason=overlay")
            infoCardState = InfoCardPolicy.State.HIDDEN
            infoCardFocusIndex = 0
        }
    }

    // 快进/快退浮层：卡片本体仍显示（回看进度行），但收起最近观看条并退出聚焦。
    LaunchedEffect(isSeekOverlayVisible) {
        if (isSeekOverlayVisible && infoCardState == InfoCardPolicy.State.FOCUSED) {
            infoCardState = InfoCardPolicy.State.IDLE
            infoCardFocusIndex = 0
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

    // t172: start the auto-update policy once and surface its results as Toasts.
    // t218: warm the neighbours only when the user is browsing (drawer open) or the player is idle
    // after a first frame, and only when the experiment is enabled. Failures are silent.
    LaunchedEffect(zapPreconnectEnabled, multicastPrewarmEnabled, drawerOpen, firstFrameRendered, currentGroupIndex) {
        val urls = channelGroups.map { it.variants.firstOrNull()?.channel?.url ?: "" }
        if (urls.isEmpty()) return@LaunchedEffect
        val idle = firstFrameRendered && !drawerOpen
        if (zapPreconnectEnabled) {
            ZapPreconnect.warmNeighbours(
                context = context,
                urls = urls,
                currentIndex = currentGroupIndex,
                drawerOpen = drawerOpen,
                idle = idle,
            )
        }
        if (multicastPrewarmEnabled) {
            MulticastPrewarm.warmNeighbour(
                context = context,
                urls = urls,
                currentIndex = currentGroupIndex,
                drawerOpen = drawerOpen,
                idle = idle,
            )
        }
    }

    LaunchedEffect(Unit) {
        AutoUpdateScheduler.start(context)
        AutoUpdateScheduler.events.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            autoUpdateTick++
        }
    }

    // t201 (EPG-COLLECT-01): the COLLECTORS run from the very first composition again. Both flows are
    // cold SharedFlows without replay, so parking them behind the first-frame gate silently dropped
    // events emitted before the first frame (and broke manual EPG entirely when the frame never came).
    // Only the CACHE READ is deferred — and only until the first frame, with a hard fallback.
    LaunchedEffect(Unit) {
        val epgSource = Prefs.getEpgSource(context)
        if (epgSource.isNotBlank()) {
            launch {
                BackgroundSourceUpdater.epgUpdated.collect { data ->
                    epgData = data
                    epgLoadFailed = false
                    Log.i(epgLoadTag, "background updated. programs=${data.programsByChannelId.size}")
                }
            }

            // t176 (EPG-DUAL-01): AutoUpdateScheduler is the ONLY EPG executor. This screen keeps the
            // cached data and consumes epgUpdated; it never downloads/parses EPG itself.
            // t189 (MANUAL-POLICY-01): one emission of [manualEpgRequests] = at most one download.
            launch {
                manualEpgRequests.collect {
                    when (ManualRefreshPolicy.actionFor(manualFlagRaised = true, pumpAlreadyConsumed = false)) {
                        ManualRefreshPolicy.Action.CALL_SCHEDULER -> AutoUpdateScheduler.requestManualEpg(context)
                        ManualRefreshPolicy.Action.CONSUME_FLAG,
                        ManualRefreshPolicy.Action.NONE -> Unit
                    }
                }
            }

            // t197 (B) / t201: wait for the first frame, but never longer than the fallback — if the
            // stream never renders (offline/broken), the cached schedule must still load.
            kotlinx.coroutines.withTimeoutOrNull(ColdStartPolicy.EPG_CACHE_FALLBACK_MS) {
                androidx.compose.runtime.snapshotFlow { firstFrameRendered }.first { it }
            }
            // t7（PERF-04 · 冷启 rebuffer）：再等 2s 才开始 22 秒量级的 XMLTV 解析，避免它和
            // 起播后的缓冲恢复抢 CPU（t14 基线：首帧 3197ms → 启动 rebuffer 4288ms，同时 epgCache=22095ms）。
            if (ColdStartPolicy.EPG_POST_FIRST_FRAME_DELAY_MS > 0L) {
                delay(ColdStartPolicy.EPG_POST_FIRST_FRAME_DELAY_MS)
            }
            val t0 = System.currentTimeMillis()
            // t204 (EPG-CACHE-MAIN-01): the XMLTV cache read is disk + parse work; keep it off the
            // main thread so a slow/failed cache never blocks the UI (the fallback timer included).
            val cachedEpg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                PerfTrace.section("IQTV-epg-cache-load") {
                    EpgRepository.load(context, epgSource, forceRefresh = false)
                }
            }
            // FULLFIX B2③：缓存读取返回 null = 配置了该源但加载失败（供抽屉显示"EPG 加载失败"）。
            epgLoadFailed = cachedEpg == null
            if (android.util.Log.isLoggable(ZAP_TAG, android.util.Log.DEBUG)) {
                android.util.Log.d(
                    ZAP_TAG,
                    "epgCache=${System.currentTimeMillis() - t0}ms firstFrame=$firstFrameRendered"
                )
            }
            PerfTrace.d { "epg_cache_load ms=${System.currentTimeMillis() - t0} firstFrame=$firstFrameRendered" }
            if (cachedEpg != null) {
                epgData = cachedEpg
                Log.i(epgLoadTag, "cache loaded. programs=${cachedEpg.programsByChannelId.size}")
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

    // FULLFIX B2②：信息卡节目只按实际播放台查询，不受 selectedGroup 浏览过滤影响。
    // 旧实现按 selectedGroup 过滤 groups → 抽屉浏览分组变化时 A 台的 nowProgram 消失。
    // 现在遍历**全部** channelGroups，键覆盖每个 variant URL，因此无论当前浏览/焦点在哪，
    // 信息卡用播放台 URL 查询永远能拿到它自己的节目。key 去掉 selectedGroup。
    LaunchedEffect(epgData, nowMillis, channelGroups) {
        val data = epgData
        val allGroups = channelGroups
        if (data == null || allGroups.isEmpty()) {
            nowProgramByUrl = emptyMap()
            return@LaunchedEffect
        }

        val map = withContext(Dispatchers.Default) {
            buildMap {
                for (group in allGroups) {
                    val displayChannel = group.defaultChannel
                    val t = data.nowProgramTitle(displayChannel, nowMillis)
                    if (!t.isNullOrBlank()) {
                        val current = data.nowProgram(displayChannel, nowMillis)
                        val progress = current?.let {
                            val duration = (it.endMillis - it.startMillis).toFloat()
                            if (duration <= 0f) null
                            else ((nowMillis - it.startMillis).toFloat() / duration).coerceIn(0f, 1f)
                        }
                        val ui = NowProgramUi(
                            title = t,
                            progress = progress,
                            startMillis = current?.startMillis ?: 0L,
                            endMillis = current?.endMillis ?: 0L,
                        )
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

    // ---- t133: recent channels / last channel ----
    /**
     * t159: persist the pending list. A flush scheduled for an older generation, or one that would
     * write back a list the user just cleared, is dropped — this is what makes "clear" survive
     * ON_STOP/ON_PAUSE/onDispose and a force-stop.
     */
    fun flushRecentChannels(gen: Int? = null) {
        if (recentCache.flush(gen)) recentTick++
    }

    /**
     * t175: apply a track override straight on the playback parameters — the player is never rebuilt,
     * so decoding (AAC on MediaCodec / MP2 on the FFmpeg extension) is untouched.
     */
    fun applyAudioOverride(groupIndex: Int, trackIndex: Int) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (groupIndex < 0) {
            builder.clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
            audioOverrideGroup = -1
            audioOverrideTrack = -1
        } else {
            val tracks = p.currentTracks
            val group = tracks.groups.getOrNull(groupIndex) ?: return
            if (group.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) return
            if (trackIndex !in 0 until group.length) return
            // t179: never let the UI select a track the current renderer rejects (e.g. an AAC track
            // routed to the FFmpeg extension); the picker only lists supported tracks.
            if (!group.isTrackSupported(trackIndex)) return
            builder.setOverrideForType(
                androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
            )
            audioOverrideGroup = groupIndex
            audioOverrideTrack = trackIndex
        }
        p.trackSelectionParameters = builder.build()
    }

    fun recordRecentChannel(group: ChannelGroup, number: Int, variantIndex: Int) {
        // DEBOUNCE-01: always push onto the in-memory list, never onto a stale Prefs snapshot, so
        // A→B→C keeps all three rows instead of dropping B. A real channel start also lifts the
        // "cleared" marker (the user is building a new list).
        recentEntries = recentCache.record(group, number, variantIndex)
        val gen = recentCache.generationSnapshot()
        recentWriteJob?.cancel()
        recentWriteJob = scope.launch {
            delay(400)
            flushRecentChannels(gen)
        }
        recentTick++
    }

    fun playGroupVariant(
        groupIndex: Int,
        variantIndex: Int,
        selectionTicket: Long? = null,
        // t211: only the OUTERMOST entry may arm; nested variant switches (auto probe) must not.
        armZap: Boolean = false,
    ) {
        if (selectionTicket != null && !playbackIntent.matches(selectionTicket)) return
        val group = channelGroups.getOrNull(groupIndex) ?: return
        val channel = group.variants.getOrNull(variantIndex)?.channel ?: return
        // t211: quality-variant picks and last-channel call this directly; they pass armZap = true.
        if (armZap && android.util.Log.isLoggable(ZAP_TAG, android.util.Log.DEBUG)) {
            ZapTrace.onZapRequested(ZapTrace.groupKey(groupIndex), android.os.SystemClock.elapsedRealtime())
        }
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
        // t133/t141: identity = group key + the variant actually playing.
        recordRecentChannel(group, groupIndex + 1, variantIndex)
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
    DisposableEffect(Unit) {
        StreamPlayback.setRebuildHandler { _, soft -> rebuildPlayer(soft) }
        onDispose { StreamPlayback.setRebuildHandler(null) }
    }

    fun playGroupAuto(groupIndex: Int, armZap: Boolean = true) {
        // t218/t220: a real zap always wins — drop any warm in flight so it cannot compete.
        ZapPreconnect.cancel()
        MulticastPrewarm.cancel()
        // t234: a user zap gives the new selection a fresh rebuild/soft chance.
        StreamPlayback.onUserZap()
        // t211 (ZAP-NESTED-01): arm ONCE per user selection, with the GROUP key — the fallback probe
        // may switch through selectedIndex 0/1/2, and all those variants share this key, so the probe
        // time stays inside the measurement instead of being reset away.
        if (armZap && android.util.Log.isLoggable(ZAP_TAG, android.util.Log.DEBUG)) {
            ZapTrace.onZapRequested(ZapTrace.groupKey(groupIndex), android.os.SystemClock.elapsedRealtime())
        }
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
        // t204/t208: the user zap starts here too (drawer selection / return-to-live).
        if (android.util.Log.isLoggable(ZAP_TAG, android.util.Log.DEBUG)) {
            ZapTrace.onZapRequested(ZapTrace.zapKey(channel.id, channel.url), android.os.SystemClock.elapsedRealtime())
        }
        // t126: warm this channel's logo so the next drawer open is instant.
        channel.logoUrl?.trim()?.takeIf { it.isNotBlank() }?.let { u ->
            val pxW = with(density) { 64.dp.roundToPx() }
            val pxH = with(density) { 40.dp.roundToPx() }
            scope.launch { LogoLoader.prefetch(context, listOf(u), pxW, pxH) }
        }
        channelGroups.forEachIndexed { groupIndex, group ->
            if (group.variants.any { it.channel.url == channel.url && (channel.id == 0L || it.channel.id == channel.id) }) {
                // t211: playChannel already armed; do not arm again inside.
                playGroupAuto(groupIndex, armZap = false)
                return
            }
        }
    }

    /** Switch back to the most recent channel that is not the one currently playing. */
    fun playLastChannel(): Boolean {
        if (drawerOpen || settingsDrawerOpen || qualityDialogOpen || recentChannelsOpen) return false
        val now = System.currentTimeMillis()
        if (now - lastChannelSwitchAt < 800) return false
        val currentGroupKey = channelGroups.getOrNull(currentGroupIndex)?.key
        // PLAY-01: resolve first; only a fully valid target may mutate state / consume the key.
        val target = RecentChannels.previousResolved(recentEntries, channelGroups, currentGroupKey)
            ?: return false
        if (target.groupIndex !in channelGroups.indices) return false
        if (target.variantIndex !in target.group.variants.indices) return false
        lastChannelSwitchAt = now
        playGroupVariant(target.groupIndex, target.variantIndex, armZap = true)
        showInfoCard()
        return true
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

    // DEBOUNCE-01: never lose the last zap when the screen stops or the composition is disposed.
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

    DisposableEffect(player) {
        setOnPlayLastChannel { playLastChannel() }
        setRecentChannelsOpenController { open -> recentChannelsOpen = open }
        setAudioDialogOpenController { open -> audioDialogOpen = open }
        setOnNumberJump { number ->
            val groups = channelGroupsState.value
            if (number !in 1..groups.size) false else {
                playGroupAutoState.value(number-1)
                showInfoCard()
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
            showInfoCard()
            true
        }

        onDispose {
            setOnChannelStep(null)
            setOnNumberJump(null)
            setOnPlayLastChannel(null)
            setRecentChannelsOpenController(null)
            setAudioDialogOpenController(null)
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

        PlayerDrawer(
            visible = drawerOpen,
            groups = groups,
            selectedGroup = selectedGroup,
            channels = filteredChannels,
            channelNumbers = remember(channelGroups) {
                buildMap { channelGroups.forEachIndexed { index, group ->
                    group.variants.forEach { put(it.channel.url, index+1) }
                } }
            },
            selectedChannelUrl = if (currentLive.retainedOutsideList) null else channelGroups.getOrNull(currentGroupIndex)?.defaultChannel?.url,
            nowProgramByChannelUrl = nowProgramByUrl,
            epgData = epgData,
            nowMillis = nowMillis,
            // FULLFIX B2③：节目单空态三分类。
            epgSourceConfigured = epgSourceConfigured,
            epgLoadFailed = epgLoadFailed,
            onSelectGroup = { sidebarController.selectGroup(it) },
            // V4 §2：分组列聚焦/点击 → 清该组记忆、从第一台开始。
            onSelectGroupReset = { sidebarController.selectGroup(it, resetFocus = true) },
            onSelectChannel = { channel ->
                playChannel(channel)
            },
            onPlayProgram = { req ->
                val bound = PlaybackIntent.catchup(ChannelGrouper.allChannels(channelGroups), req, playbackIntent.request)
                if (bound != null) {
                    sourceSelectGeneration += 1
                    sourceSelectJob?.cancel()
                    playbackIntent.beginSelection()
                    catchupRequest = req
                    startPlayback(bound)
                    sidebarController.closeDrawer()
                }
            },
            onClose = { sidebarController.closeDrawer() },
            onActiveColumnChanged = { sidebarController.setActiveColumn(it) },
            requestedActiveColumn = drawerActiveColumn,
            // FULLFIX §2：把该组上次浏览焦点传给抽屉（不回开头），并接收抽屉内真实浏览焦点回写。
            initialFocusUrl = sidebarController.lastFocusedUrlFor(selectedGroup),
            onFocusedChannelChanged = { url -> sidebarController.focusChannel(url) }
        )

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

        var logoCacheTick by remember { mutableIntStateOf(0) }
        // t133: recent channels resolved against the current playlist (missing ids are skipped).
        val recentResolved = remember(recentEntries, channelGroups, currentGroupIndex) {
            val currentGroupKey = channelGroups.getOrNull(currentGroupIndex)?.key
            RecentChannels.resolvedDistinct(recentEntries, channelGroups).map { resolved ->
                RecentChannelUi(
                    title = resolved.group.displayTitle,
                    number = resolved.groupIndex + 1,
                    logoUrl = resolved.group.logoUrl ?: resolved.channel.logoUrl,
                    selected = resolved.group.key == currentGroupKey,
                    // t4：切台用索引（信息卡小卡与最近观看对话框共用，避免按 url 二次解析）。
                    groupIndex = resolved.groupIndex,
                    variantIndex = resolved.variantIndex,
                ) to (resolved.group to resolved.channel)
            }
        }
        val recentItems = recentResolved.map { it.first }
        // t4 (INFO_CARD_V2 §5.1)：卡片底部只放「非当前台、最多 6 张、最新在前」——
        // 排除当前台后连续 OK 才能在 A↔B 之间快跳（D-C3）。
        val cardRecentItems = remember(recentResolved, currentGroupIndex) {
            InfoCardPolicy.cardRecentItems(recentItems) { it.selected }
        }

        // t4 (§5.2)：统一切台助手（对话框与卡片共用同一份解析）。
        fun switchToRecent(item: RecentChannelUi): Boolean {
            if (item.groupIndex !in channelGroups.indices) return false
            val group = channelGroups[item.groupIndex]
            if (group.variants.isEmpty()) return false
            val variant = item.variantIndex.takeIf { it in group.variants.indices } ?: 0
            sidebarController.closeDrawer()
            playGroupVariant(item.groupIndex, variant, armZap = true)
            return true
        }

        // t32 Bug1：主动 OK 出卡控制器 —— DIRECT 模式一次 OK 即 FOCUSED 可选最近观看；
        // TWO_STEP / 空 / 1 张 / 回看 / 快进 → IDLE（7s 自动消失）。由 Activity dispatch 调用。
        DisposableEffect(cardRecentItems, catchupRequest, isSeekOverlayVisible, infoOkMode) {
            val openHandler: () -> Boolean = {
                val canFocus = InfoCardPolicy.canFocusRecent(
                    recentCount = cardRecentItems.size,
                    catchup = catchupRequest != null,
                    seekOverlayVisible = isSeekOverlayVisible,
                )
                if (InfoCardPolicy.openCardFocusesDirectly(infoOkMode) && canFocus) {
                    infoCardFocusIndex = 0
                    infoCardState = InfoCardPolicy.State.FOCUSED
                    logInfoCard("ok-open DIRECT focus recent=${cardRecentItems.size}")
                } else {
                    infoCardState = InfoCardPolicy.State.IDLE
                    infoCardFocusIndex = 0
                    logInfoCard("ok-open ${infoOkMode.name} state=IDLE")
                }
                infoCardTimerTick++
                true
            }
            setOpenInfoCardFromOkController(openHandler)
            onDispose { setOpenInfoCardFromOkController(null) }
        }

        // t35：FOCUSED 态按键确定性闭环控制器 —— Compose 焦点未就绪时也执行卡片语义动作，
        // 与 ChannelInfoBanner 的 onMoveFocus/onSwitchRecent/onExitFocus 完全等价，绝不穿透。
        DisposableEffect(cardRecentItems) {
            val focusedHandler: (InfoCardPolicy.FocusedKeyAction) -> Boolean = { action ->
                when (action) {
                    InfoCardPolicy.FocusedKeyAction.MOVE_LEFT,
                    InfoCardPolicy.FocusedKeyAction.MOVE_RIGHT -> {
                        val delta = if (action == InfoCardPolicy.FocusedKeyAction.MOVE_RIGHT) 1 else -1
                        infoCardFocusIndex = InfoCardPolicy.nextFocusIndex(
                            current = infoCardFocusIndex,
                            count = cardRecentItems.size,
                            delta = delta,
                        )
                        logInfoCard("focused-fallback move index=$infoCardFocusIndex")
                    }
                    InfoCardPolicy.FocusedKeyAction.SWITCH -> {
                        InfoCardPolicy.resolveRecentTarget(cardRecentItems, infoCardFocusIndex)?.let { item ->
                            logInfoCard("focused-fallback switch idx=$infoCardFocusIndex group=${item.number} ${item.title}")
                            if (switchToRecent(item)) infoCardFocusIndex = 0
                        }
                    }
                    InfoCardPolicy.FocusedKeyAction.EXIT_FOCUS -> {
                        infoCardFocusIndex = 0
                        infoCardState = InfoCardPolicy.State.IDLE
                        infoCardTimerTick++
                        logInfoCard("focused-fallback exit")
                    }
                    InfoCardPolicy.FocusedKeyAction.NONE -> Unit
                }
                true
            }
            setInfoCardFocusedActionController(focusedHandler)
            onDispose { setInfoCardFocusedActionController(null) }
        }

        // t4 (INFO_CARD_V2 §3.6 / D-C2)：第二段 OK —— IDLE→FOCUSED；条为空/回看/快进只重新计时。
        DisposableEffect(cardRecentItems, catchupRequest, isSeekOverlayVisible) {
            val handler: () -> Boolean = {
                if (infoCardState == InfoCardPolicy.State.IDLE) {
                    val canFocus = InfoCardPolicy.canFocusRecent(
                        recentCount = cardRecentItems.size,
                        catchup = catchupRequest != null,
                        seekOverlayVisible = isSeekOverlayVisible,
                    )
                    if (canFocus) {
                        infoCardFocusIndex = 0
                        infoCardState = InfoCardPolicy.State.FOCUSED
                        logInfoCard("focus index=0")
                    } else {
                        infoCardTimerTick++
                    }
                    true
                } else {
                    false
                }
            }
            setInfoCardOkController(handler)
            onDispose { setInfoCardOkController(null) }
        }

        // 焦点清理由 ChannelInfoBanner 自己负责（只在“曾聚焦过”时清，避免抢覆盖层焦点）。
        // t176: the settings row count must reflect the stored list immediately (a clear leaves
        // recentEntries empty in the same frame, while the resolved list depends on the playlist).
        val recentCount = recentEntries.size
        // CACHE-01: stats does disk IO on Dispatchers.IO inside LogoLoader; only compute when settings shown.
        val logoCacheSummary by produceState(initialValue = "", settingsDrawerOpen, logoCacheTick) {
            value = if (settingsDrawerOpen) {
                val s = LogoLoader.stats(context)
                "内存 ${s.memoryEntries} 条 · 磁盘 ${s.diskFiles} 个 / ${s.diskBytes / 1024} KB"
            } else {
                ""
            }
        }
        SettingsDrawer(
            visible = settingsDrawerOpen,
            onSourceConfigClick = {
                // t182 (STARTUP-RESET-02): NO reset here — opening (and possibly cancelling) the
                // config screen must not trigger a refresh. The reset happens in
                // AutoUpdateScheduler.onSourceChanged() once the user actually saved.
                val intent = Intent(context, MainActivity::class.java)
                context.startActivity(intent)
            },
            // t16（F4）：删除死参数 onEpgSettingsClick —— 合并行已由 onRefreshEpgNow 承载
            // （同一 requestManualEpg），旧 lambda（Toast + requestManualEpg）永不执行。
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
            onRefreshLogos = {
                val pxW = with(density) { 64.dp.roundToPx() }
                val pxH = with(density) { 40.dp.roundToPx() }
                android.widget.Toast.makeText(context, "正在刷新当前组台标…", android.widget.Toast.LENGTH_SHORT).show()
                scope.launch {
                    LogoLoader.refreshGroup(context, filteredChannels, pxW, pxH)
                    logoCacheTick++
                    android.widget.Toast.makeText(context, "已刷新当前组台标", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onOpenRecent = {
                settingsDrawerOpen = false
                setRecentOpen(true)
            },
            recentCount = recentCount,
            onClearLogoCache = {
                android.widget.Toast.makeText(context, "正在清理台标缓存…", android.widget.Toast.LENGTH_SHORT).show()
                scope.launch {
                    LogoLoader.clearAll(context)
                    logoCacheTick++
                    android.widget.Toast.makeText(context, "台标缓存已清理", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            logoCacheSummary = logoCacheSummary,
            logoPrefetchEnabled = logoPrefetchEnabled,
            logoLoadEnabled = logoLoadEnabled,
            remoteUpDownLabel = remoteHabits.upDown.label,
            remoteOkLabel = remoteHabits.ok.label,
            remoteBackLabel = remoteHabits.back.label,
            remoteDigitsLabel = remoteHabits.digits.label,
            remoteChannelKeysEnabled = remoteHabits.channelKeysEnabled,
            onCycleRemoteUpDown = {
                val next = RemoteHabits.nextUpDown(remoteHabits.upDown)
                Prefs.setRemoteUpDown(context, next)
                remoteHabits = remoteHabits.copy(upDown = next)
            },
            onCycleRemoteOk = {
                val next = RemoteHabits.nextOk(remoteHabits.ok)
                Prefs.setRemoteOk(context, next)
                remoteHabits = remoteHabits.copy(ok = next)
            },
            onCycleRemoteBack = {
                val next = RemoteHabits.nextBack(remoteHabits.back)
                Prefs.setRemoteBack(context, next)
                remoteHabits = remoteHabits.copy(back = next)
            },
            onCycleRemoteDigits = {
                val next = RemoteHabits.nextDigits(remoteHabits.digits)
                Prefs.setRemoteDigits(context, next)
                remoteHabits = remoteHabits.copy(digits = next)
            },
            onToggleRemoteChannelKeys = {
                val next = !remoteHabits.channelKeysEnabled
                Prefs.setRemoteChannelKeys(context, next)
                remoteHabits = remoteHabits.copy(channelKeysEnabled = next)
            },
            // FULLFIX §7：功能→按键绑定 UI（动态行 + 捕获）。
            remoteBindings = remoteBindings,
            captureRequest = captureRequest,
            onStartCapture = { action, longPress -> onStartCapture(action, longPress) },
            onCancelCapture = { onCancelCapture() },
            onResetRemoteBindings = {
                onBindingsChanged(RemoteBindings.DEFAULT)
                Prefs.setRemoteBindings(context, RemoteBindings.DEFAULT)
                onCancelCapture()
            },
            onResetRemoteHabits = {
                Prefs.resetRemoteSettings(context)
                remoteHabits = RemoteHabits.DEFAULT
                android.widget.Toast.makeText(context, "已恢复默认遥控设置", android.widget.Toast.LENGTH_SHORT).show()
            },
            audioSummary = AudioTrackUi.summary(audioTracks, audioOverrideGroup, audioOverrideTrack),
            audioEntryLabel = AudioTrackUi.entryLabel(audioTracks),
            audioSelectable = AudioTrackUi.canSelect(audioTracks),
            playbackBufferLabel = LoadControlTuning.tierLabel(playbackBufferMin),
            zapPreconnectEnabled = zapPreconnectEnabled,
            jingcaiExperimentLabel = jingcaiExperiment.label,
            onCycleJingcaiExperiment = {
                val next = JingcaiExperiment.next(jingcaiExperiment)
                Prefs.setJingcaiExperiment(context, next.id)
                jingcaiExperiment = next
                android.widget.Toast.makeText(
                    context,
                    "睛彩诊断实验：${next.label}（重新选台后生效）",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            // t32 Bug1：切换信息卡 OK 行为（DIRECT/TWO_STEP），立即生效。
            infoOkModeLabel = if (infoOkMode == InfoCardPolicy.InfoOkMode.DIRECT) "直接选最近观看" else "两段式",
            onCycleInfoOkMode = {
                val next = when (infoOkMode) {
                    InfoCardPolicy.InfoOkMode.DIRECT -> InfoCardPolicy.InfoOkMode.TWO_STEP
                    InfoCardPolicy.InfoOkMode.TWO_STEP -> InfoCardPolicy.InfoOkMode.DIRECT
                }
                Prefs.setInfoOkMode(context, next)
                infoOkMode = next
            },
            multicastPrewarmEnabled = multicastPrewarmEnabled,
            onToggleMulticastPrewarm = {
                val next = !multicastPrewarmEnabled
                Prefs.setMulticastPrewarmEnabled(context, next)
                multicastPrewarmEnabled = next
                if (!next) MulticastPrewarm.cancel()
                android.widget.Toast.makeText(
                    context,
                    if (next) "已开启组播预热（实验）" else "已关闭组播预热",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onToggleZapPreconnect = {
                val next = !zapPreconnectEnabled
                Prefs.setZapPreconnectEnabled(context, next)
                zapPreconnectEnabled = next
                if (!next) ZapPreconnect.cancel()
                android.widget.Toast.makeText(
                    context,
                    if (next) "已开启预连接相邻频道（实验）" else "已关闭预连接相邻频道",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onCyclePlaybackBuffer = {
                val next = LoadControlTuning.next(playbackBufferMin)
                Prefs.setPlaybackBufferMin(context, next)
                playbackBufferMin = next
                android.widget.Toast.makeText(
                    context,
                    "起播缓冲已设为${LoadControlTuning.tierLabel(next)}，下次切台生效",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onAudioTrackClick = {
                // t187 (AUDIO-SINGLE-02): a single-track channel has nothing to pick, so the row is
                // inert (the label already says 仅 1 条) and no empty dialog is opened.
                if (!AudioTrackUi.canSelect(audioTracks)) {
                    android.widget.Toast.makeText(
                        context,
                        if (audioTracks.isEmpty()) "当前频道没有音轨" else "当前频道只有 1 条音轨",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // t179 (AUDIO-BACK-01): one overlay at a time — otherwise BACK closes settings
                    // first and the audio dialog would be left on top with the player unguarded.
                    settingsDrawerOpen = false
                    audioDialogOpen = true
                }
            },
            autoUpdatePlaylistLabel = autoUpdatePlaylist.label,
            autoUpdateEpgLabel = autoUpdateEpg.label,
            lastUpdateSummary = run {
                val now = System.currentTimeMillis()
                val epg = AutoUpdateConfig.lastSuccessLabel(Prefs.getLastEpgUpdateTime(context), now)
                val playlist = AutoUpdateConfig.lastSuccessLabel(Prefs.getPlaylistLastSuccess(context), now)
                // autoUpdateTick keeps this line fresh after a manual refresh
                autoUpdateTick
                "上次更新：播放列表 $playlist · EPG $epg"
            },
            onCyclePlaylistInterval = {
                autoUpdatePlaylist = AutoUpdateConfig.next(autoUpdatePlaylist)
                Prefs.setAutoUpdatePlaylist(context, autoUpdatePlaylist)
                android.widget.Toast.makeText(
                    context, "自动更新播放列表：${autoUpdatePlaylist.label}", android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onCycleEpgInterval = {
                autoUpdateEpg = AutoUpdateConfig.next(autoUpdateEpg)
                Prefs.setAutoUpdateEpg(context, autoUpdateEpg)
                android.widget.Toast.makeText(
                    context, "自动更新 EPG：${autoUpdateEpg.label}", android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onRefreshPlaylistNow = {
                android.widget.Toast.makeText(context, "正在刷新播放列表…", android.widget.Toast.LENGTH_SHORT).show()
                AutoUpdateScheduler.refreshNow(context, playlist = true, epg = false)
            },
            onRefreshEpgNow = {
                // t189: ONE path — the flow → policy → AutoUpdateScheduler.requestManualEpg.
                android.widget.Toast.makeText(context, "正在刷新 EPG…", android.widget.Toast.LENGTH_SHORT).show()
                requestManualEpg()
            },
            onToggleLogoPrefetch = { enabled ->
                logoPrefetchEnabled = enabled
                Prefs.setLogoPrefetchEnabled(context, enabled)
                android.widget.Toast.makeText(
                    context,
                    if (enabled) "台标预取已开启" else "台标预取已关闭",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onToggleLogoLoad = { enabled ->
                logoLoadEnabled = enabled
                Prefs.setLogoLoadEnabled(context, enabled)
                if (!enabled) LogoLoader.clearMemory() // free the bitmaps immediately
                android.widget.Toast.makeText(
                    context,
                    if (enabled) "台标显示已开启" else "台标显示已关闭",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onClose = { settingsDrawerOpen = false },
            // t13（SETTINGS_IA_V2 §5.1）：二级页存在时，BACK 只退到一级（消费按键）；
            // 一级再 BACK 才关抽屉。Compose 内部也会消费，这里是 Activity 侧的兜底。
            onBackHandlerReady = setSettingsBackHandlerController
        )

        AudioTrackDialog(
            visible = audioDialogOpen,
            items = AudioTrackUi.buildItems(audioTracks, audioOverrideGroup, audioOverrideTrack),
            hasAudio = audioTracks.isNotEmpty(),
            onSelect = { item ->
                applyAudioOverride(item.groupIndex, item.trackIndex)
                audioDialogOpen = false
                showInfoCard()
            },
            onClose = { audioDialogOpen = false }
        )

        RecentChannelsDialog(
            visible = recentChannelsOpen,
            items = recentItems,
            onSelect = { index ->
                // t4：与信息卡小卡共用同一个切台助手（索引已在 RecentChannelUi 里解析好）。
                recentItems.getOrNull(index)?.let { item ->
                    if (switchToRecent(item)) setRecentOpen(false)
                }
            },
            onClear = {
                // t159: cancel the debounced write, then clear memory + pending + Prefs atomically.
                recentWriteJob?.cancel()
                recentWriteJob = null
                recentEntries = recentCache.clearAll(channelGroups.getOrNull(currentGroupIndex)?.key)
                recentTick++
                // t167: hard evidence for acceptance — greps for "IQTV-RECENT cleared=true count=0".
                android.util.Log.i("IQTV-RECENT", RecentDialogTags.clearedLog(recentEntries.size))
                android.widget.Toast.makeText(context, "已清空最近观看", android.widget.Toast.LENGTH_SHORT).show()
            },
            onClose = { setRecentOpen(false) }
        )

        QualitySelectorDialog(
            visible = qualityDialogOpen,
            channelTitle = qualityDialogGroup?.displayTitle.orEmpty(),
            variants = qualityVariants,
            onSelect = { variantIndex ->
                sourceSelectGeneration += 1
                sourceSelectJob?.cancel()
                val groupIndex = if (qualityDialogGroupIndex >= 0) qualityDialogGroupIndex else playingGroupIndex
                playGroupVariant(groupIndex, variantIndex, armZap = true)
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

                // t24（INFO_CARD_V3）：右侧节目行需要起止/分组小字。
                val bannerProgram = if (req != null) null else bannerChannel?.let { nowProgramByUrl[it.url] }
                val bannerProgramStart = catchupConfig?.startMillis ?: (bannerProgram?.startMillis ?: 0L)
                val bannerProgramEnd = catchupConfig?.endMillis ?: (bannerProgram?.endMillis ?: 0L)
                val bannerGroup = if (req != null) {
                    channelGroups.firstOrNull { g -> g.variants.any { it.channel.url == req.liveUrl } }
                } else {
                    playingGroup
                }
                val bannerGroupLabel = bannerGroup?.let { ChannelGrouper.displayGroupName(it) }

                // t40：帧率来源决策 —— 真实 Format.frameRate>0 优先 → 渲染推断 → 25 兜底。
                val formatFps = videoFormat?.frameRate ?: -1f
                val fpsResolution = FrameRateEstimator.resolve(formatFps, inferredFrameRate)
                fpsSource = fpsResolution.source
                if (android.util.Log.isLoggable(INFO_CARD_TAG, android.util.Log.DEBUG)) {
                    logInfoCard("fps source=${fpsResolution.source.name.lowercase()} value=${fpsResolution.value}")
                }

                ChannelInfoBanner(
                    state = infoCardState,
                    seekOverlayVisible = isSeekOverlayVisible,
                    channel = bannerChannel,
                    programTitle = displayTitle,
                    programProgress = displayProgress,
                    channelNumber = displayNumber,
                    videoFormat = videoFormat,
                    // t40：帧率回退用推断值（format>0 时 banner 内 takePositive 自然优先真实值）。
                    frameRateFallback = fpsResolution.value,
                    audioLabel = AudioTrackUi.summary(audioTracks, audioOverrideGroup, audioOverrideTrack),
                    // FULLFIX §4.2②：声道数取实际播放音轨 Format（不再是 video Format 的 channelCount）。
                    audioChannelCount = AudioTrackUi.selectedChannelCount(
                        audioTracks, audioOverrideGroup, audioOverrideTrack
                    ),
                    catchupConfiguration = catchupConfig,
                    programStartMs = bannerProgramStart,
                    programEndMs = bannerProgramEnd,
                    nowMillis = nowMillis,
                    groupLabel = bannerGroupLabel,
                    recentCards = cardRecentItems,
                    focusIndex = infoCardFocusIndex,
                    onMoveFocus = { delta ->
                        // clamp 0..n-1，首尾不环绕（InfoCardPolicy.nextFocusIndex）。
                        infoCardFocusIndex = InfoCardPolicy.nextFocusIndex(
                            current = infoCardFocusIndex,
                            count = cardRecentItems.size,
                            delta = delta,
                        )
                        logInfoCard("move index=$infoCardFocusIndex")
                    },
                    onSwitchRecent = { _ ->
                        // FULLFIX §5（U1）：OK 目标以权威 infoCardFocusIndex 为准，忽略事件来源 index
                        // （高亮/真焦点/OK 目标三者用同一真源，OK→OK→右→OK 才切到第二张卡）。
                        // InfoCardPolicy.resolveRecentTarget 对空/越界返回 null → 空/1 张不崩。
                        InfoCardPolicy.resolveRecentTarget(cardRecentItems, infoCardFocusIndex)?.let { item ->
                            logInfoCard("switch idx=$infoCardFocusIndex group=${item.number} ${item.title}")
                            if (switchToRecent(item)) {
                                // §5.2：切台后保持 FOCUSED，焦点回到第一张（刚离开的台）。
                                infoCardFocusIndex = 0
                            }
                        }
                    },
                    onExitFocus = {
                        infoCardFocusIndex = 0
                        infoCardState = InfoCardPolicy.State.IDLE
                        infoCardTimerTick++
                    },
                    onFocusedChanged = { focused ->
                        onInfoCardFocusedChanged(focused)
                    },
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
    val shape = RoundedCornerShape(0.dp)
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
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), RoundedCornerShape(0.dp))
                )

                // Progress Fill
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .align(Alignment.CenterStart)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(0.dp))
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

/**
 * t187 (AUDIO-OVERRIDE-02): drop every audio TrackSelectionOverride and write the parameters back.
 * Must run on the application looper (the player's thread) — the only call site is
 * `onMediaItemTransition`, which Media3 delivers there.
 */
private fun clearAudioOverride(player: androidx.media3.common.Player) {
    val params = player.trackSelectionParameters
    // No override at all → nothing to write back (avoids a pointless parameter assignment).
    if (params.overrides.isEmpty()) return
    player.trackSelectionParameters = params.buildUpon()
        .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
        .build()
}

/** t197: one debug tag for the TTFF stopwatch (`adb shell setprop log.tag.IQTV-ZAP DEBUG`). */
private const val ZAP_TAG = "IQTV-ZAP"
// t4 (INFO_CARD_V2 §7.3)：信息卡调试日志 tag（只在 setprop log.tag.IQTV-INFOCARD DEBUG 时输出）。
private const val INFO_CARD_TAG = "IQTV-INFOCARD"
