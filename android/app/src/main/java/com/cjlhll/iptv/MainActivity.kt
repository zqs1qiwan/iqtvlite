package com.cjlhll.iptv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import tv.iqtvlite.app.R
import java.io.IOException

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStrings.set(Prefs.getAppLanguage(this))
        setContent {
            val colorScheme = TvDesignTokens.colorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val keyboardController = LocalSoftwareKeyboardController.current

    var sources by remember { mutableStateOf(Prefs.getSources(context)) }
    var activeSourceId by remember { mutableStateOf(Prefs.getActiveSourceId(context)) }
    val initialActive = remember(sources, activeSourceId) {
        PlaylistSources.activeSource(sources, activeSourceId)
    }

    var liveSource by remember { mutableStateOf(initialActive?.url ?: Prefs.getLiveSource(context)) }
    var epgSource by remember { mutableStateOf(initialActive?.epgUrl ?: Prefs.getEpgSource(context)) }
    var globalUaSummary by remember { mutableStateOf(Prefs.getGlobalUserAgent(context)) }
    var customHeaderCount by remember { mutableStateOf(PlaylistSources.parseCustomHeaders(Prefs.getGlobalCustomHeaders(context)).size) }

    var isLoading by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var requestGeneration by remember { mutableStateOf(0L) }
    var sourceCall by remember { mutableStateOf<okhttp3.Call?>(null) }
    var sourceJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { sourceCall?.cancel(); sourceJob?.cancel() } }

    var lanUrl by remember { mutableStateOf<String?>(null) }
    var lanError by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var isLiveSourceEditing by remember { mutableStateOf(false) }
    var isEpgSourceEditing by remember { mutableStateOf(false) }

    fun syncStateFromPrefs() {
        val freshLang = Prefs.getAppLanguage(context)
        AppStrings.set(freshLang)
        val freshSources = Prefs.getSources(context)
        val freshActiveId = Prefs.getActiveSourceId(context)
        val active = PlaylistSources.activeSource(freshSources, freshActiveId)
        sources = freshSources
        activeSourceId = freshActiveId
        if (active != null) {
            liveSource = active.url
            epgSource = active.epgUrl
        }
        globalUaSummary = Prefs.getGlobalUserAgent(context)
        customHeaderCount = PlaylistSources.parseCustomHeaders(Prefs.getGlobalCustomHeaders(context)).size
    }

    fun loadAndPlay(url: String, requestedEpg: String, sourceUa: String = "", save: Boolean = true) {
        val trimmedUrl = url.trim()
        val trimmedEpg = requestedEpg.trim()
        if (trimmedUrl.isBlank()) {
            Toast.makeText(context, AppStrings.tr("请输入直播源地址", "Please enter playlist URL"), Toast.LENGTH_SHORT).show()
            return
        }
        // 将当前编辑的地址同步写入多源存储
        val currentList = Prefs.getSources(context)
        val matched = currentList.firstOrNull { it.url == trimmedUrl }
            ?: currentList.firstOrNull { it.id == activeSourceId }
        val upsertItem = if (matched != null && matched.url == trimmedUrl) {
            matched.copy(epgUrl = trimmedEpg.ifBlank { matched.epgUrl })
        } else {
            PlaylistSource(
                id = PlaylistSources.newId(trimmedUrl),
                name = PlaylistSources.defaultNameForUrl(trimmedUrl),
                url = trimmedUrl,
                epgUrl = trimmedEpg,
                enabled = true,
                userAgent = sourceUa,
            )
        }
        Prefs.saveSources(context, PlaylistSources.upsert(currentList, upsertItem), upsertItem.id)
        syncStateFromPrefs()

        sourceCall?.cancel()
        sourceJob?.cancel()
        requestGeneration += 1
        val generation = requestGeneration
        isLoading = true
        statusBanner = AppStrings.tr("正在加载播放源…", "Loading playlist…")
        sourceJob = coroutineScope.launch {
            try {
                val expectedVersion = SourceStore.beginChange(context)
                if (generation != requestGeneration) return@launch
                if (save) Toast.makeText(context, AppStrings.tr("正在加载直播源...", "Loading playlist..."), Toast.LENGTH_SHORT).show()
                val client = NetUtil.ipv4OkHttpClient.newBuilder()
                    .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val ua = sourceUa.ifBlank { Prefs.getGlobalUserAgent(context) }.ifBlank { HttpStreamAddress.DEFAULT_USER_AGENT }
                val call = client.newCall(Request.Builder().url(trimmedUrl).header("User-Agent", ua).build())
                sourceCall = call
                val content = withContext(Dispatchers.IO) {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        response.body?.string() ?: throw IOException("empty response")
                    }
                }
                val detectedEpg = HttpStreamAddress.extractM3uExtras(content).headerEpgUrl.orEmpty()
                val effectiveEpg = trimmedEpg.ifBlank { detectedEpg }.ifBlank { Prefs.getEpgSource(context) }
                val groups = withContext(Dispatchers.Default) {
                    ChannelGrouper.group(M3uParser.parse(content, trimmedUrl))
                }
                if (generation != requestGeneration) return@launch
                if (groups.isEmpty()) {
                    statusBanner = AppStrings.tr("未找到有效直播频道", "No valid channels found")
                    Toast.makeText(context, AppStrings.tr("未找到有效的直播频道", "No valid channels found"), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val committed = withContext(Dispatchers.IO) {
                    SourceStore.commitSource(context, trimmedUrl, effectiveEpg, content, expectedVersion)
                }
                if (generation != requestGeneration) return@launch
                if (!committed) throw IOException("Unable to save playlist cache")
                val (lastUrl, lastTitle) = if (Prefs.isResumeLastChannel(context)) {
                    Prefs.getLastChannel(context)
                } else {
                    null to null
                }
                val (groupIndex, variantIndex) = ChannelGrouper.findBestGroupVariant(groups, lastUrl, lastTitle)
                val channel = groups[groupIndex].variants[variantIndex].channel
                Prefs.setLastChannel(context, channel.url, channel.title)
                context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                    putExtra("VIDEO_URL", channel.url)
                    putExtra("VIDEO_TITLE", channel.title)
                    putExtra("VIDEO_ID", channel.id)
                    putExtra("VIDEO_UA", channel.userAgent)
                    putExtra("VIDEO_REFERRER", channel.referrer)
                    putExtra("PLAYLIST_FILE_NAME", PlaylistCache.fileNameForSource(trimmedUrl))
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                AutoUpdateScheduler.onSourceChanged(context, playlistAlreadyFresh = true)
                (context as? Activity)?.finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == requestGeneration) {
                    statusBanner = AppStrings.tr("加载失败：", "Load failed: ") + (e.message ?: AppStrings.tr("请检查网络或源地址", "Check network or URL"))
                    Toast.makeText(context, AppStrings.tr("加载直播源失败", "Failed to load playlist"), Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (generation == requestGeneration) {
                    isLoading = false
                    sourceCall = null
                }
            }
        }
    }

    val onPush: (String, String) -> Unit = { live, epg ->
        mainHandler.post {
            syncStateFromPrefs()
            if (live.isNotBlank()) liveSource = live
            if (epg.isNotBlank()) epgSource = epg
            statusBanner = AppStrings.tr("已收到手机控制台推送", "Received update from Web Console")
            Toast.makeText(context, AppStrings.tr("已从手机推送更新", "Updated from Web Console"), Toast.LENGTH_SHORT).show()
            loadAndPlay(liveSource, epgSource, save = true)
        }
    }

    val onStateChanged: () -> Unit = {
        mainHandler.post {
            syncStateFromPrefs()
            statusBanner = AppStrings.tr("已同步手机端配置变更", "Synced Web Console settings")
        }
    }

    val onSwitchAndPlay: (PlaylistSource) -> Unit = { src ->
        mainHandler.post {
            syncStateFromPrefs()
            liveSource = src.url
            epgSource = src.epgUrl
            statusBanner = AppStrings.tr("正在切换并播放「${src.name}」…", "Switching to \"${src.name}\"…")
            loadAndPlay(src.url, src.epgUrl, src.userAgent, save = true)
        }
    }

    DisposableEffect(Unit) {
        val server = SourceConfigLanServer(
            appContext = context.applicationContext,
            onPush = onPush,
            onStateChanged = onStateChanged,
            onSwitchAndPlay = onSwitchAndPlay,
        )
        try {
            val info = server.start()
            lanUrl = info.url
            lanError = null
        } catch (e: Exception) {
            lanUrl = null
            lanError = e.message ?: "局域网服务启动失败"
        }
        onDispose { server.stop() }
    }

    LaunchedEffect(lanUrl) {
        val url = lanUrl
        qrBitmap = if (url.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching { QrCode.toBitmap(url, 640) }.getOrNull()
            }
        }
    }

    val panelBg = Color(0xFF121822)
    val panelBorder = Color(0x1AFFFFFF)
    val pageBg = Brush.verticalGradient(listOf(Color(0xFF0A0E14), Color(0xFF0E141E)))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBg)
            .padding(horizontal = 42.dp, vertical = 28.dp)
    ) {
        // ── 顶部品牌头（使用官方 IQTV Lite 横版白字标志） ─────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.brand_wordmark_white),
                    contentDescription = "IQTV Lite",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(38.dp),
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = tr("远程配置中心", "Remote Config Console"),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = TvDesignTokens.TextPrimary,
                    )
                    Text(
                        text = tr("多播放源切换 · 请求头兼容 · 手机扫码管理", "Multi-source · Headers & UA · Phone Web Console"),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = TvDesignTokens.TextMuted,
                    )
                }
            }

            // 右上角状态胶囊
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val uaTag = globalUaSummary.ifBlank { tr("自动 UA", "Auto UA") }
                val hdrTag = if (customHeaderCount > 0) " · $customHeaderCount Header" else ""
                Box(
                    modifier = Modifier
                        .background(Color(0x18FFFFFF), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = tr("${sources.size} 个播放源 · $uaTag$hdrTag", "${sources.size} Sources · $uaTag$hdrTag"),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = TvDesignTokens.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        // ── 主内容双栏：左扫码控制台卡片 + 右多源列表与快捷操作 ─────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            // 左栏：扫码控制台卡片
            Column(
                modifier = Modifier
                    .width(344.dp)
                    .fillMaxHeight()
                    .background(panelBg, RoundedCornerShape(16.dp))
                    .border(1.dp, panelBorder, RoundedCornerShape(16.dp))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = tr("手机扫码打开控制台", "Scan QR for Web Console"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = TvDesignTokens.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = tr("支持 播放源 / 请求头 / 显示播放 / 遥控系统", "Manage Sources, Headers, Display & Remote"),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = TvDesignTokens.TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }

                // 二维码区域
                Box(
                    modifier = Modifier
                        .size(214.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (qrBitmap != null) Color.White else Color(0x1AFFFFFF))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        lanError != null -> Text(
                            text = tr("局域网服务启动失败\n${lanError.orEmpty()}", "LAN Server Failed\n${lanError.orEmpty()}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB4A9),
                            textAlign = TextAlign.Center,
                        )
                        qrBitmap != null -> Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "QR Web Console",
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> Text(
                            text = tr("正在生成二维码…", "Generating QR…"),
                            style = MaterialTheme.typography.bodySmall,
                            color = TvDesignTokens.TextSecondary,
                        )
                    }
                }

                // 局域网地址 + 步骤
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!lanUrl.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0x1E2F6BFF), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x405C98FF), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = lanUrl!!,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = Color(0xFF9AC2FF),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Text(
                        text = tr(
                            "1. 手机与电视连接同一 Wi-Fi\n2. 浏览器或微信扫码进入多标签网页\n3. 修改配置后自动同步至电视端",
                            "1. Connect phone & TV to same Wi-Fi\n2. Scan QR code with camera or browser\n3. Changes sync immediately to TV",
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.5.sp,
                            lineHeight = 18.sp,
                        ),
                        color = TvDesignTokens.TextMuted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 右栏：多源切换列表 + 手动输入区
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(panelBg, RoundedCornerShape(16.dp))
                    .border(1.dp, panelBorder, RoundedCornerShape(16.dp))
                    .padding(22.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = tr("已配置播放源（按 OK 切换播放）", "Configured Sources (Press OK to Play)"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = TvDesignTokens.TextPrimary,
                    )
                    statusBanner?.let { banner ->
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = BrandFocus.BlueColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 多源列表（紧凑滚动，遥控直接选源切换）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sources.forEach { src ->
                        SourceQuickRow(
                            source = src,
                            isActive = src.id == activeSourceId,
                            enabled = !isLoading,
                            onClick = { loadAndPlay(src.url, src.epgUrl, src.userAgent, save = true) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 快捷输入区（保留遥控器直接微调地址的能力）
                val textFieldColors = TextFieldDefaults.colors(
                    focusedContainerColor = BrandFocus.FocusBgColor,
                    unfocusedContainerColor = Color(0xFF1A2230),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = TvDesignTokens.TextPrimary,
                    focusedLabelColor = BrandFocus.BlueColor,
                    unfocusedLabelColor = TvDesignTokens.TextMuted,
                    cursorColor = BrandFocus.BlueColor,
                    focusedIndicatorColor = BrandFocus.BlueColor,
                    unfocusedIndicatorColor = Color(0x26FFFFFF),
                )

                TextField(
                    value = liveSource,
                    onValueChange = { liveSource = it },
                    label = { Text(tr("快速新增或修改直播源 URL", "Quick Add or Edit Playlist URL"), fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) isLiveSourceEditing = false }
                        .onKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                                (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                if (!isLiveSourceEditing) {
                                    isLiveSourceEditing = true
                                    keyboardController?.show()
                                }
                            }
                            false
                        },
                    colors = textFieldColors,
                    enabled = !isLoading,
                    readOnly = !isLiveSourceEditing,
                )

                Spacer(modifier = Modifier.height(10.dp))

                TextField(
                    value = epgSource,
                    onValueChange = { epgSource = it },
                    label = { Text(tr("EPG 节目单地址（留空自动识别 x-tvg-url）", "EPG XMLTV URL (optional, auto-detected)"), fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) isEpgSourceEditing = false }
                        .onKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                                (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                if (!isEpgSourceEditing) {
                                    isEpgSourceEditing = true
                                    keyboardController?.show()
                                }
                            }
                            false
                        },
                    colors = textFieldColors,
                    enabled = !isLoading,
                    readOnly = !isEpgSourceEditing,
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val isRootActivity = (context as? Activity)?.isTaskRoot == true
                    val hasSavedPlaylist = liveSource.isNotBlank() && !Prefs.getPlaylistFileName(context).isNullOrBlank()
                    TvActionButton(
                        text = if (isLoading) tr("正在加载…", "Loading…") else tr("保存并播放", "Save & Play"),
                        primary = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1.3f),
                        onClick = { loadAndPlay(liveSource, epgSource, save = true) },
                    )
                    TvActionButton(
                        text = when {
                            !isRootActivity -> tr("返回播放", "Back to Player")
                            hasSavedPlaylist -> tr("进入播放", "Start Playback")
                            else -> tr("退出应用", "Exit App")
                        },
                        primary = false,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (isRootActivity && hasSavedPlaylist) {
                                val resumeLast = Prefs.isResumeLastChannel(context)
                                val (lastUrl, lastTitle) = if (resumeLast) Prefs.getLastChannel(context) else null to null
                                context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                                    putExtra("VIDEO_URL", lastUrl ?: "")
                                    putExtra("VIDEO_TITLE", lastTitle ?: "")
                                    putExtra("PLAYLIST_FILE_NAME", Prefs.getPlaylistFileName(context))
                                })
                            }
                            (context as? Activity)?.finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceQuickRow(
    source: PlaylistSource,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> BrandFocus.FocusBgColor
        isActive -> Color(0x1E2F6BFF)
        else -> Color(0x12FFFFFF)
    }
    val borderColor = when {
        focused -> Color(0x55FFFFFF)
        isActive -> Color(0x455C98FF)
        else -> Color(0x14FFFFFF)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isActive) Color(0xFF5C98FF) else Color(0x38FFFFFF))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 14.5.sp,
                        fontWeight = if (focused || isActive) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (isActive) Color(0xFF7CB0FF) else TvDesignTokens.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActive) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tr("当前", "Active"),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                        color = Color(0xFF9AC2FF),
                    )
                }
                if (!source.enabled) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tr("已隐藏", "Hidden"),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                        color = TvDesignTokens.TextMuted,
                    )
                }
            }
            Text(
                text = source.url,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = if (focused) TvDesignTokens.TextSecondary else TvDesignTokens.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = tr("播放 ›", "Play ›"),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = if (focused) TvDesignTokens.TextPrimary else TvDesignTokens.TextMuted,
        )
    }
}

@Composable
private fun TvActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        !enabled -> Color(0x22FFFFFF)
        primary && focused -> Color(0xFF4782FF)
        primary -> Color(0xFF2F6BFF)
        focused -> BrandFocus.FocusBgColor
        else -> Color(0x1CFFFFFF)
    }
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = if (focused) Color(0x88FFFFFF) else Color(0x20FFFFFF),
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = if (enabled) Color.White else TvDesignTokens.TextMuted,
        )
    }
}

fun findBestChannelIndex(channels: List<Channel>, lastUrl: String?, lastTitle: String?): Int? {
    if (channels.isEmpty()) return null
    if (!lastUrl.isNullOrBlank()) {
        val urlIndex = channels.indexOfFirst { it.url == lastUrl }
        if (urlIndex >= 0) return urlIndex
    }
    if (!lastTitle.isNullOrBlank()) {
        val titleIndex = channels.indexOfFirst { it.title == lastTitle }
        if (titleIndex >= 0) return titleIndex
    }
    return 0
}

fun findChannel(m3uContent: String, lastUrl: String?, lastTitle: String?): Pair<String, String>? {
    val lines = m3uContent.lines()
    var currentTitle: String? = null

    var firstChannel: Pair<String, String>? = null
    var titleMatch: Pair<String, String>? = null

    for (line in lines) {
        val trimmedLine = line.trim()
        if (trimmedLine.startsWith("#EXTINF:")) {
            val parts = trimmedLine.split(",")
            if (parts.size > 1) {
                currentTitle = parts.last().trim()
            }
        } else if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
            val url = trimmedLine
            val title = currentTitle ?: "Unknown"
            val channel = Pair(url, title)

            if (firstChannel == null) firstChannel = channel
            if (lastUrl != null && url == lastUrl) return channel
            if (lastTitle != null && title == lastTitle) titleMatch = channel

            currentTitle = null
        }
    }

    return titleMatch ?: firstChannel
}
