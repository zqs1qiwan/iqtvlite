package com.cjlhll.iptv

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colorScheme = darkColorScheme(
                primary = Color(0xFF76D7BB),
                onPrimary = Color.Black,
                background = Color(0xFF101316),
                onBackground = Color(0xFFF5F7F8),
                surface = Color(0xFF161C21),
                onSurface = Color(0xFFF5F7F8),
            )
            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape) {
                    MainScreen()
                }
            }
        }
    }
}

private enum class ConfigPhase { EDITING, LOADING, ERROR, EMPTY }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // 预填测试源但允许完全改写；不锁死默认地址
    val savedLiveSource = remember { Prefs.getLiveSource(context).ifBlank { "https://iptv.laobaitv.net" } }
    val savedEpgSource = remember { Prefs.getEpgSource(context).ifBlank { "https://epg.laobaitv.net" } }

    var liveSource by remember { mutableStateOf(savedLiveSource) }
    var epgSource by remember { mutableStateOf(savedEpgSource) }
    var isLiveSourceEditing by remember { mutableStateOf(false) }
    var isEpgSourceEditing by remember { mutableStateOf(false) }

    var phase by remember { mutableStateOf(ConfigPhase.EDITING) }
    var fieldError by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var configRequestId by remember { mutableIntStateOf(0) }
    var activeCall by remember { mutableStateOf<Call?>(null) }
    var lanRetryToken by remember { mutableIntStateOf(0) }
    var lastBackAt by remember { mutableStateOf(0L) }
    val launchedWithSource = remember { Prefs.getLiveSource(context).isNotBlank() }

    var lanUrl by remember { mutableStateOf<String?>(null) }
    var lanError by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    // 首次配置前不弹自动更新打断主路径
    LaunchedEffect(Unit) {
        if (Prefs.getLiveSource(context).isNotBlank()) {
            val info = UpdateChecker.checkUpdate(context)
            if (info != null && info.hasUpdate) { updateInfo = info; showUpdateDialog = true }
        }
    }

    if (showUpdateDialog && updateInfo != null) {
        UpdateDialog(updateInfo = updateInfo!!, onDismiss = { showUpdateDialog = false })
    }

    suspend fun startPlayer(live: String, epg: String, content: String, channels: List<Channel>) {
        // R03/R10：通过单一事务提交源与缓存指针；不在首帧前写“最近成功观看记录”
        val committed = SourceStore.commitSource(context, live, epg, content)
        if (!committed) {
            phase = ConfigPhase.ERROR
            errorMessage = "无法保存播放源缓存"
            errorDetail = "写入失败，已保留原有配置"
            return
        }
        val fileName = Prefs.getPlaylistFileName(context)

        val groups = ChannelGrouper.group(channels)
        if (groups.isEmpty()) {
            phase = ConfigPhase.EMPTY
            errorMessage = "此地址未包含有效频道"
            return
        }
        val restoreLast = AppSettings.isRestoreLastChannel(context)
        val (lastUrl, lastTitle) = Prefs.getLastChannel(context)
        val (gi, vi) = if (!restoreLast) {
            0 to groups.first().defaultVariantIndex
        } else {
            ChannelGrouper.findBestGroupVariant(groups, lastUrl, lastTitle)
        }
        val startChannel = groups[gi].variants[vi].channel

        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("VIDEO_URL", startChannel.url)
            putExtra("VIDEO_TITLE", startChannel.title)
            if (fileName != null) putExtra("PLAYLIST_FILE_NAME", fileName)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        BackgroundSourceUpdater.scheduleRefresh(context, delayMs = 3_000L)
        (context as? android.app.Activity)?.finish()
    }

    fun cancelConfigRequest() {
        // 请求代次自增，使任何旧协程结果失效；取消底层 OkHttp call（D05）。
        configRequestId++
        activeCall?.cancel()
        activeCall = null
        loadJob?.cancel()
        loadJob = null
        phase = ConfigPhase.EDITING
    }

    fun loadAndPlay(rawLive: String, rawEpg: String) {
        val live = rawLive.trim()
        val epg = rawEpg.trim()
        fieldError = null
        if (!live.startsWith("http://") && !live.startsWith("https://")) {
            // 新无效输入也终止旧请求（D05）
            configRequestId++
            activeCall?.cancel()
            activeCall = null
            loadJob?.cancel()
            fieldError = "请输入完整的http或https地址"
            phase = ConfigPhase.EDITING
            return
        }
        val myRequestId = ++configRequestId
        activeCall?.cancel()
        loadJob?.cancel()
        phase = ConfigPhase.LOADING
        errorMessage = null
        errorDetail = null
        loadJob = scope.launch {
            try {
                val client = NetUtil.ipv4OkHttpClient
                val request = okhttp3.Request.Builder().url(live).build()
                val call = client.newCall(request)
                activeCall = call
                val response = withContext(Dispatchers.IO) { call.execute() }
                if (myRequestId != configRequestId) return@launch
                if (!response.isSuccessful) {
                    phase = ConfigPhase.ERROR
                    errorMessage = "无法加载播放源"
                    errorDetail = "HTTP ${response.code}"
                    return@launch
                }
                val content = withContext(Dispatchers.IO) { response.body?.string() }
                if (myRequestId != configRequestId) return@launch
                if (content.isNullOrBlank()) {
                    phase = ConfigPhase.EMPTY
                    errorMessage = "此地址未包含有效频道"
                    errorDetail = "响应为空"
                    return@launch
                }
                val channels = M3uParser.parse(content)
                if (myRequestId != configRequestId) return@launch
                if (channels.isEmpty() || ChannelGrouper.group(channels).isEmpty()) {
                    phase = ConfigPhase.EMPTY
                    errorMessage = "此地址未包含有效频道"
                    errorDetail = "保留原输入，可修改后重试"
                    return@launch
                }
                startPlayer(live, epg, content, channels)
            } catch (e: CancellationException) {
                // 取消不再写成 ERROR（D05）
                throw e
            } catch (e: java.io.IOException) {
                if (myRequestId != configRequestId) return@launch
                phase = ConfigPhase.ERROR
                errorMessage = "无法加载播放源"
                errorDetail = e.message
            } catch (e: Exception) {
                if (myRequestId != configRequestId) return@launch
                phase = ConfigPhase.ERROR
                errorMessage = "发生错误"
                errorDetail = e.message
            } finally {
                if (myRequestId == configRequestId) activeCall = null
            }
        }
    }

    val onPush: (String?, String?) -> Unit = { live, epg ->
        Handler(Looper.getMainLooper()).post {
            // null 表示推送未包含该字段（保留草稿）；空字符串表示显式清空（D15）
            if (live != null) liveSource = live.trim()
            if (epg != null) epgSource = epg.trim()
            Toast.makeText(context, "已收到手机配置，正在加载…", Toast.LENGTH_SHORT).show()
            loadAndPlay(liveSource, epgSource)
        }
    }

    DisposableEffect(lanRetryToken) {
        val server = SourceConfigLanServer(appContext = context.applicationContext, onPush = onPush)
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
        val u = lanUrl
        qrBitmap = if (u.isNullOrBlank()) null else withContext(Dispatchers.Default) {
            runCatching { QrCode.toBitmap(u, 640) }.getOrNull()
        }
    }

    BackHandler {
        when {
            phase == ConfigPhase.LOADING -> cancelConfigRequest()
            phase == ConfigPhase.ERROR || phase == ConfigPhase.EMPTY -> phase = ConfigPhase.EDITING
            launchedWithSource -> (context as? android.app.Activity)?.finish()
            else -> {
                // 首次配置：双 Back 退出（D15）
                val now = System.currentTimeMillis()
                if (now - lastBackAt > 2000L) {
                    lastBackAt = now
                    Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show()
                } else {
                    (context as? android.app.Activity)?.finish()
                }
            }
        }
    }

    val saveRequester = remember { FocusRequester() }
    LaunchedEffect(phase) {
        if (phase == ConfigPhase.EDITING) runCatching { saveRequester.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize().background(TvColors.Page).padding(48.dp)) {
        Text("添加播放源", style = TvType.PageTitle, color = TvColors.TextPrimary, modifier = Modifier.align(Alignment.TopStart))
        Text(
            "手机与电视连接同一局域网，可扫码推送或在右侧手动填写",
            style = TvType.Aux,
            color = TvColors.TextSecondary,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 44.dp),
        )

        Row(
            modifier = Modifier.fillMaxSize().padding(top = 72.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左：二维码 + LAN 地址
            Box(modifier = Modifier.fillMaxHeight(0.9f).aspectRatio(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                when {
                    lanError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("无法启动手机配置", style = TvType.Item, color = TvColors.Error)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(lanError ?: "", style = TvType.Aux, color = TvColors.TextSecondary, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { lanRetryToken++ }, modifier = Modifier.height(48.dp)) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("重试手机配置") }
                        }
                    }
                    qrBitmap != null && !lanUrl.isNullOrBlank() -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(0.85f).aspectRatio(1f)
                                .background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp),
                        ) {
                            Image(bitmap = qrBitmap!!.asImageBitmap(), contentDescription = "手机扫码配置二维码", modifier = Modifier.fillMaxSize())
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(lanUrl!!, style = TvType.Aux, color = TvColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("1. 手机扫码打开  2. 填写地址  3. 提交后电视自动播放", style = TvType.Aux, color = TvColors.TextDisabled, textAlign = TextAlign.Center)
                    }
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = TvColors.Playing, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("正在生成二维码", style = TvType.Aux, color = TvColors.TextSecondary)
                    }
                }
            }

            Spacer(modifier = Modifier.width(48.dp))

            // 右：字段 + 主按钮 + 错误状态
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val textFieldColors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF232A30),
                    unfocusedContainerColor = Color(0xFF232A30),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = Color.LightGray,
                    unfocusedLabelColor = Color.Gray,
                    cursorColor = Color.White,
                )

                TextField(
                    value = liveSource,
                    onValueChange = { liveSource = it; fieldError = null },
                    label = { Text("直播源地址（必填）") },
                    isError = fieldError != null,
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) isLiveSourceEditing = false }
                        .onKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                                if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                    if (!isLiveSourceEditing) { isLiveSourceEditing = true; keyboardController?.show() }
                                    false
                                } else false
                            } else false
                        },
                    colors = textFieldColors,
                    enabled = phase != ConfigPhase.LOADING,
                    readOnly = !isLiveSourceEditing,
                )
                fieldError?.let { Text(it, style = TvType.Aux, color = TvColors.Error, modifier = Modifier.fillMaxWidth()) }

                TextField(
                    value = epgSource,
                    onValueChange = { epgSource = it },
                    label = { Text("EPG地址（选填）") },
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) isEpgSourceEditing = false }
                        .onKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                                if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                    if (!isEpgSourceEditing) { isEpgSourceEditing = true; keyboardController?.show() }
                                    false
                                } else false
                            } else false
                        },
                    colors = textFieldColors,
                    enabled = phase != ConfigPhase.LOADING,
                    readOnly = !isEpgSourceEditing,
                )

                when (phase) {
                    ConfigPhase.LOADING -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = TvColors.Playing, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("正在加载频道…", style = TvType.Body, color = TvColors.TextSecondary)
                        }
                        Button(onClick = { cancelConfigRequest() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("取消") }
                        }
                    }
                    ConfigPhase.ERROR, ConfigPhase.EMPTY -> {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(TvColors.Panel, RoundedCornerShape(12.dp))
                                .border(1.dp, TvColors.Error.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                        ) {
                            Text(errorMessage ?: "加载失败", style = TvType.Item, color = TvColors.Error)
                            errorDetail?.let { Text(it, style = TvType.Aux, color = TvColors.TextSecondary) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { loadAndPlay(liveSource, epgSource) }, modifier = Modifier.weight(1f).height(56.dp)) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("重试") }
                            }
                            Button(onClick = { phase = ConfigPhase.EDITING }, modifier = Modifier.weight(1f).height(56.dp)) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("修改地址") }
                            }
                        }
                    }
                    ConfigPhase.EDITING -> {
                        Button(
                            onClick = { loadAndPlay(liveSource, epgSource) },
                            modifier = Modifier.fillMaxWidth().height(56.dp).focusRequester(saveRequester),
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("保存并播放") }
                        }
                    }
                }
            }
        }
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
            if (parts.size > 1) currentTitle = parts.last().trim()
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
