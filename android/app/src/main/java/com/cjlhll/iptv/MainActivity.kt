package com.cjlhll.iptv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import tv.iqtvlite.app.R
import java.io.IOException
import java.util.concurrent.TimeUnit

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
                    shape = RectangleShape,
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

    val initialActive = remember {
        PlaylistSources.activeSource(Prefs.getSources(context), Prefs.getActiveSourceId(context))
    }

    var liveSource by remember { mutableStateOf(initialActive?.url ?: Prefs.getLiveSource(context)) }
    var epgSource by remember { mutableStateOf(initialActive?.epgUrl ?: Prefs.getEpgSource(context)) }

    var isLoading by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var requestGeneration by remember { mutableLongStateOf(0L) }
    var sourceCall by remember { mutableStateOf<Call?>(null) }
    var sourceJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { sourceCall?.cancel(); sourceJob?.cancel() } }

    var lanUrl by remember { mutableStateOf<String?>(null) }
    var lanError by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    fun syncInputsFromPrefs() {
        AppStrings.set(Prefs.getAppLanguage(context))
        val active = PlaylistSources.activeSource(Prefs.getSources(context), Prefs.getActiveSourceId(context))
        if (active != null) {
            liveSource = active.url
            epgSource = active.epgUrl
        }
    }

    fun loadAndPlay(url: String, requestedEpg: String, sourceUa: String = "", save: Boolean = true) {
        val trimmedUrl = url.trim()
        val trimmedEpg = requestedEpg.trim()
        if (trimmedUrl.isBlank()) {
            Toast.makeText(context, AppStrings.tr("请输入直播源地址", "Please enter playlist URL"), Toast.LENGTH_SHORT).show()
            return
        }

        val currentList = Prefs.getSources(context)
        val activeId = Prefs.getActiveSourceId(context)
        val matched = currentList.firstOrNull { it.url == trimmedUrl }
            ?: currentList.firstOrNull { it.id == activeId }
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
        syncInputsFromPrefs()

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
                val client = NetUtil.okHttpClient.newBuilder()
                    .callTimeout(30, TimeUnit.SECONDS)
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
            } catch (e: CancellationException) {
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
            syncInputsFromPrefs()
            if (live.isNotBlank()) liveSource = live
            if (epg.isNotBlank()) epgSource = epg
            statusBanner = AppStrings.tr("已收到手机推送，正在加载…", "Received push from phone, loading…")
            Toast.makeText(context, AppStrings.tr("已从手机推送更新", "Updated from phone setup"), Toast.LENGTH_SHORT).show()
            loadAndPlay(liveSource, epgSource, save = true)
        }
    }

    val onStateChanged: () -> Unit = {
        mainHandler.post {
            syncInputsFromPrefs()
            statusBanner = AppStrings.tr("已同步手机端配置", "Synced settings from phone")
        }
    }

    val onSwitchAndPlay: (PlaylistSource) -> Unit = { src ->
        mainHandler.post {
            syncInputsFromPrefs()
            liveSource = src.url
            epgSource = src.epgUrl
            statusBanner = AppStrings.tr("正在加载「${src.name}」…", "Loading \"${src.name}\"…")
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
            lanError = e.message ?: AppStrings.tr("局域网服务启动失败", "LAN server failed")
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
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        // 顶部品牌标识与首次开机引导标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.brand_wordmark_white),
                contentDescription = "IQTV Lite",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(38.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = tr("首次使用设置", "First-Time Setup"),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = TvDesignTokens.TextPrimary,
                )
                Text(
                    text = tr("扫码或输入直播源后即可开始观看", "Scan QR code or enter a playlist URL to start watching"),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = TvDesignTokens.TextMuted,
                )
            }
        }

        Spacer(modifier = Modifier.height(26.dp))

        // 双栏引导：左扫码导入 + 右手动输入
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // 左栏：手机扫码快速导入
            Column(
                modifier = Modifier
                    .width(356.dp)
                    .fillMaxHeight()
                    .background(panelBg, RoundedCornerShape(16.dp))
                    .border(1.dp, panelBorder, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = tr("手机扫码快速导入", "Scan QR with Phone"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = TvDesignTokens.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = tr("免遥控打字 · 推送订阅后自动开播", "Skip typing · Auto-starts playback after push"),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = TvDesignTokens.TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(216.dp)
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
                            contentDescription = "First-Run Setup QR",
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> Text(
                            text = tr("正在生成二维码…", "Generating QR…"),
                            style = MaterialTheme.typography.bodySmall,
                            color = TvDesignTokens.TextSecondary,
                        )
                    }
                }

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
                            "1. 手机与电视连接同一 Wi-Fi\n2. 扫码进入网页填写播放源与 EPG\n3. 保存推送后，电视自动加载开播",
                            "1. Connect phone & TV to same Wi-Fi\n2. Scan QR code to enter playlist & EPG URL\n3. Push from phone — TV starts playing automatically",
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

            // 右栏：手动输入播放源与 EPG 地址
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(panelBg, RoundedCornerShape(16.dp))
                    .border(1.dp, panelBorder, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = tr("手动输入播放源", "Enter Source Manually"),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.5.sp,
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

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = tr(
                            "填写 M3U / TXT 直播源地址即可开播；进入播放器后可在「设置 → 通用」随时扫码管理多源与备份。",
                            "Enter any M3U or TXT playlist URL to begin. You can manage multi-source lists and backups anytime in Settings → General.",
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                        ),
                        color = TvDesignTokens.TextMuted,
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    SourceInputField(
                        value = liveSource,
                        onValueChange = { liveSource = it },
                        label = tr("直播源地址（M3U / TXT）", "Playlist URL (M3U / TXT)"),
                        enabled = !isLoading,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    SourceInputField(
                        value = epgSource,
                        onValueChange = { epgSource = it },
                        label = tr("EPG 节目单地址（可选，留空自动识别 x-tvg-url）", "EPG XMLTV URL (optional, auto-detected)"),
                        enabled = !isLoading,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TvActionButton(
                        text = if (isLoading) tr("正在加载…", "Loading…") else tr("保存并开始播放", "Save & Start Watching"),
                        primary = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1.4f),
                        onClick = { loadAndPlay(liveSource, epgSource, save = true) },
                    )
                    TvActionButton(
                        text = tr("退出应用", "Exit App"),
                        primary = false,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = { (context as? Activity)?.finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }

    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.5.sp) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) editing = false }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    if (!editing) {
                        editing = true
                        keyboardController?.show()
                    }
                }
                false
            },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = BrandFocus.FocusBgColor,
            unfocusedContainerColor = Color(0xFF1A2230),
            focusedTextColor = Color.White,
            unfocusedTextColor = TvDesignTokens.TextPrimary,
            focusedLabelColor = BrandFocus.BlueColor,
            unfocusedLabelColor = TvDesignTokens.TextMuted,
            cursorColor = BrandFocus.BlueColor,
            focusedIndicatorColor = BrandFocus.BlueColor,
            unfocusedIndicatorColor = Color(0x26FFFFFF),
        ),
        enabled = enabled,
        readOnly = !editing,
    )
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
            .height(46.dp)
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
