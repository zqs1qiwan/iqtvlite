package com.cjlhll.iptv

import android.content.Context
import android.os.Build
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import tv.iqtvlite.app.BuildConfig
import tv.iqtvlite.app.R
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

class SourceConfigLanServer(
    private val appContext: Context,
    private val onPush: (liveSource: String, epgSource: String) -> Unit,
    private val onStateChanged: () -> Unit = {},
    private val onSwitchAndPlay: ((PlaylistSource) -> Unit)? = null,
) {
    data class Info(
        val ip: String,
        val port: Int,
        val url: String,
    )

    private var server: NanoHTTPD? = null
    private var info: Info? = null

    fun start(): Info {
        val localIp = findLocalIpv4() ?: "127.0.0.1"
        val preferredPorts = if (BuildConfig.APPLICATION_ID.endsWith(".validation"))
            intArrayOf(28081, 28082, 28083, 28084) else intArrayOf(18080, 18081, 19090, 28080, 8080)
        var lastError: Exception? = null
        for (port in preferredPorts) {
            val s = InternalServer(
                port = port,
                appContext = appContext,
                html = SourceConfigWebPage.html(appContext),
                onPush = onPush,
                onStateChanged = onStateChanged,
                onSwitchAndPlay = onSwitchAndPlay,
            )
            try {
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                info = Info(ip = localIp, port = port, url = "http://$localIp:$port/")
                return info!!
            } catch (e: Exception) {
                s.stop()
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("LAN server start failed")
    }

    fun stop() {
        server?.stop()
        server = null
        info = null
    }

    private fun findLocalIpv4(): String? {
        val ifaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrNull().orEmpty()
        return ifaces
            .asSequence()
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .filterNot { runCatching { it.isLoopback }.getOrDefault(true) }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { !it.isNullOrBlank() && !it.startsWith("127.") }
    }

    private class InternalServer(
        port: Int,
        private val appContext: Context,
        private val html: String,
        private val onPush: (liveSource: String, epgSource: String) -> Unit,
        private val onStateChanged: () -> Unit,
        private val onSwitchAndPlay: ((PlaylistSource) -> Unit)?,
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val path = (session.uri ?: "/").lowercase(Locale.US)
            if (session.method == Method.OPTIONS) {
                return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
            }

            return when {
                session.method == Method.GET && (path == "/" || path == "/index.html") -> {
                    cors(newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html))
                }

                session.method == Method.GET && path == "/api/ping" -> {
                    val body = JSONObject().apply {
                        put("ok", true)
                        put("appName", runCatching { appContext.getString(R.string.app_name) }.getOrElse { "IQTV Lite" })
                        put("versionName", BuildConfig.VERSION_NAME)
                        put("versionCode", BuildConfig.VERSION_CODE)
                    }.toString()
                    jsonOk(body)
                }

                session.method == Method.GET && path == "/api/state" -> {
                    jsonOk(buildStateJson().toString())
                }

                session.method == Method.POST && path == "/api/push" -> {
                    val payload = parsePayload(session)
                    val live = payload.optString("liveSource", "").trim()
                    val epg = payload.optString("epgSource", "").trim()
                    val name = payload.optString("name", "").trim()
                    if (live.isNotBlank()) {
                        val current = Prefs.getSources(appContext)
                        val existing = current.firstOrNull { it.url == live }
                        val item = PlaylistSource(
                            id = existing?.id ?: PlaylistSources.newId(live),
                            name = name.ifBlank { existing?.name ?: PlaylistSources.defaultNameForUrl(live) },
                            url = live,
                            epgUrl = epg.ifBlank { existing?.epgUrl.orEmpty() },
                            enabled = true,
                            userAgent = existing?.userAgent.orEmpty(),
                        )
                        val updated = PlaylistSources.upsert(current, item)
                        Prefs.saveSources(appContext, updated, item.id)
                        onStateChanged()
                    }
                    onPush(live, epg)
                    jsonOk(JSONObject().put("ok", true).put("state", buildStateJson()).toString())
                }

                session.method == Method.POST && path == "/api/sources/save" -> {
                    handleSourcesSave(parsePayload(session))
                }

                session.method == Method.POST && path == "/api/sources/delete" -> {
                    handleSourcesDelete(parsePayload(session))
                }

                session.method == Method.POST && path == "/api/headers" -> {
                    val payload = parsePayload(session)
                    val ua = payload.optString("globalUserAgent", "").trim()
                    val rawHeaders = payload.optString("globalCustomHeaders", "")
                    val normalizedHeaders = PlaylistSources.formatCustomHeaders(
                        PlaylistSources.parseCustomHeaders(rawHeaders)
                    )
                    Prefs.setGlobalUserAgent(appContext, ua)
                    Prefs.setGlobalCustomHeaders(appContext, normalizedHeaders)
                    onStateChanged()
                    jsonOk(JSONObject().put("ok", true).put("state", buildStateJson()).toString())
                }

                session.method == Method.POST && path == "/api/language" -> {
                    val payload = parsePayload(session)
                    val nextLang = AppLang.parse(payload.optString("language", "zh"))
                    Prefs.setAppLanguage(appContext, nextLang)
                    AppStrings.set(nextLang)
                    onStateChanged()
                    jsonOk(JSONObject().put("ok", true).put("state", buildStateJson()).toString())
                }

                session.method == Method.POST && path == "/api/display" -> {
                    val payload = parsePayload(session)
                    if (payload.has("uiScale")) {
                        Prefs.setUiScale(appContext, UiScalePreset.parse(payload.optString("uiScale")))
                    }
                    if (payload.has("infoOkMode")) {
                        val mode = if (payload.optString("infoOkMode").equals("direct", ignoreCase = true)) {
                            InfoCardPolicy.InfoOkMode.DIRECT
                        } else {
                            InfoCardPolicy.InfoOkMode.TWO_STEP
                        }
                        Prefs.setInfoOkMode(appContext, mode)
                    }
                    if (payload.has("playbackBufferMin")) {
                        Prefs.setPlaybackBufferMin(
                            appContext,
                            LoadControlTuning.sanitize(payload.optInt("playbackBufferMin", LoadControlTuning.TIER_STABLE_MS))
                        )
                    }
                    if (payload.has("autoUpdatePlaylist")) {
                        AutoUpdateConfig.Interval.entries.firstOrNull {
                            it.name.equals(payload.optString("autoUpdatePlaylist"), ignoreCase = true)
                        }?.let { Prefs.setAutoUpdatePlaylist(appContext, it) }
                    }
                    if (payload.has("autoUpdateEpg")) {
                        AutoUpdateConfig.Interval.entries.firstOrNull {
                            it.name.equals(payload.optString("autoUpdateEpg"), ignoreCase = true)
                        }?.let { Prefs.setAutoUpdateEpg(appContext, it) }
                    }
                    if (payload.has("logoPrefetch")) {
                        Prefs.setLogoPrefetchEnabled(appContext, payload.optBoolean("logoPrefetch", true))
                    }
                    if (payload.has("logoLoad")) {
                        val enabled = payload.optBoolean("logoLoad", true)
                        Prefs.setLogoLoadEnabled(appContext, enabled)
                        LogoLoader.setLogoLoadEnabledRuntime(enabled)
                    }
                    onStateChanged()
                    jsonOk(JSONObject().put("ok", true).put("state", buildStateJson()).toString())
                }

                session.method == Method.POST && path == "/api/remote" -> {
                    val payload = parsePayload(session)
                    if (payload.optBoolean("reset", false)) {
                        Prefs.resetRemoteSettings(appContext)
                        Prefs.setRemoteBindings(appContext, RemoteBindings.DEFAULT)
                    } else {
                        var bindings = Prefs.getRemoteBindings(appContext)
                        if (payload.has("upDown")) {
                            val habit = RemoteHabits.UpDown.entries.firstOrNull {
                                it.name.equals(payload.optString("upDown"), ignoreCase = true)
                            }
                            if (habit != null) {
                                val chKeys = RemoteBindings.deriveChannelKeysEnabled(bindings)
                                bindings = RemoteBindings.applyUpDownHabit(bindings, habit, chKeys)
                                Prefs.setRemoteUpDown(appContext, habit)
                            }
                        }
                        if (payload.has("ok")) {
                            val habit = RemoteHabits.Ok.entries.firstOrNull {
                                it.name.equals(payload.optString("ok"), ignoreCase = true)
                            }
                            if (habit != null) {
                                bindings = RemoteBindings.applyOkHabit(bindings, habit)
                                Prefs.setRemoteOk(appContext, habit)
                            }
                        }
                        if (payload.has("channelKeys")) {
                            val enabled = payload.optBoolean("channelKeys", true)
                            bindings = RemoteBindings.applyChannelKeysHabit(bindings, enabled)
                            Prefs.setRemoteChannelKeys(appContext, enabled)
                        }
                        if (payload.has("digits")) {
                            val habit = RemoteHabits.Digits.entries.firstOrNull {
                                it.name.equals(payload.optString("digits"), ignoreCase = true)
                            }
                            if (habit != null) Prefs.setRemoteDigits(appContext, habit)
                        }
                        Prefs.setRemoteBindings(appContext, bindings)
                    }
                    onStateChanged()
                    jsonOk(JSONObject().put("ok", true).put("state", buildStateJson()).toString())
                }

                session.method == Method.POST && path == "/api/refresh" -> {
                    val payload = parsePayload(session)
                    val playlist = payload.optBoolean("playlist", true)
                    val epg = payload.optBoolean("epg", false)
                    AutoUpdateScheduler.refreshNow(appContext, playlist = playlist, epg = epg)
                    jsonOk(JSONObject().put("ok", true).toString())
                }

                else -> cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not Found"))
            }
        }

        private fun handleSourcesSave(payload: JSONObject): Response {
            val current = Prefs.getSources(appContext)
            var activeId = payload.optString("activeSourceId", Prefs.getActiveSourceId(appContext).orEmpty()).trim()
            val playNow = payload.optBoolean("playNow", false)

            val updated: List<PlaylistSource> = when {
                payload.has("source") -> {
                    val obj = payload.optJSONObject("source") ?: return jsonError("无效的播放源参数")
                    val url = obj.optString("url", "").trim()
                    if (url.isBlank()) return jsonError("播放源地址不能为空")
                    val rawId = obj.optString("id", "").trim()
                    val existing = current.firstOrNull { it.id == rawId }
                    val item = PlaylistSource(
                        id = existing?.id ?: rawId.ifBlank { PlaylistSources.newId(url) },
                        name = obj.optString("name", "").trim().ifBlank { PlaylistSources.defaultNameForUrl(url) },
                        url = url,
                        epgUrl = obj.optString("epgUrl", "").trim(),
                        enabled = obj.optBoolean("enabled", true),
                        userAgent = obj.optString("userAgent", "").trim(),
                    )
                    if (payload.optBoolean("activate", false) || playNow) {
                        activeId = item.id
                    }
                    PlaylistSources.upsert(current, item)
                }
                payload.has("sources") -> {
                    val arr = payload.optJSONArray("sources") ?: return jsonError("无效的播放源列表")
                    val parsed = buildList {
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val url = obj.optString("url", "").trim()
                            if (url.isBlank()) continue
                            val id = obj.optString("id", "").trim().ifBlank { PlaylistSources.newId("$url#$i") }
                            val name = obj.optString("name", "").trim().ifBlank { PlaylistSources.defaultNameForUrl(url) }
                            add(
                                PlaylistSource(
                                    id = id,
                                    name = name,
                                    url = url,
                                    epgUrl = obj.optString("epgUrl", "").trim(),
                                    enabled = obj.optBoolean("enabled", true),
                                    userAgent = obj.optString("userAgent", "").trim(),
                                )
                            )
                        }
                    }
                    if (parsed.isEmpty()) return jsonError("至少保留 1 个有效播放源")
                    parsed
                }
                else -> current
            }

            // 至少保留 1 个在选台列表展示的源
            val sanitized = if (updated.any { it.enabled }) updated else {
                updated.mapIndexed { idx, s -> if (idx == 0) s.copy(enabled = true) else s }
            }
            Prefs.saveSources(appContext, sanitized, activeId.ifBlank { null })
            onStateChanged()

            if (playNow) {
                val active = Prefs.getActiveSource(appContext)
                if (active != null) {
                    if (onSwitchAndPlay != null) {
                        onSwitchAndPlay.invoke(active)
                    } else {
                        onPush(active.url, active.epgUrl)
                    }
                }
            }
            return jsonOk(JSONObject().put("ok", true).put("state", buildStateJson()).toString())
        }

        private fun handleSourcesDelete(payload: JSONObject): Response {
            val id = payload.optString("id", "").trim()
            if (id.isBlank()) return jsonError("缺少播放源 ID")
            val current = Prefs.getSources(appContext)
            if (current.size <= 1) return jsonError("至少需要保留 1 个播放源")
            val remaining = PlaylistSources.remove(current, id)
            if (remaining.isEmpty()) return jsonError("至少需要保留 1 个播放源")
            val nextActive = Prefs.getActiveSourceId(appContext).takeIf { aid -> remaining.any { it.id == aid } }
                ?: remaining.first().id
            val sanitized = if (remaining.any { it.enabled }) remaining else {
                remaining.mapIndexed { idx, s -> if (idx == 0) s.copy(enabled = true) else s }
            }
            Prefs.saveSources(appContext, sanitized, nextActive)
            onStateChanged()
            return jsonOk(JSONObject().put("ok", true).put("state", buildStateJson()).toString())
        }

        private fun buildStateJson(): JSONObject {
            val sources = Prefs.getSources(appContext)
            val activeSourceId = Prefs.getActiveSourceId(appContext)
            val bindings = Prefs.getRemoteBindings(appContext)
            val remoteSettings = Prefs.getRemoteSettings(appContext)

            val sourcesArr = JSONArray().apply {
                sources.forEach { s ->
                    put(
                        JSONObject().apply {
                            put("id", s.id)
                            put("name", s.name)
                            put("url", s.url)
                            put("epgUrl", s.epgUrl)
                            put("enabled", s.enabled)
                            put("userAgent", s.userAgent)
                        }
                    )
                }
            }

            val bindingsArr = JSONArray().apply {
                val lang = Prefs.getAppLanguage(appContext)
                RemoteBindings.CONFIGURABLE_ACTIONS.forEach { action ->
                    val b = bindings.firstOrNull { it.action == action }
                    put(
                        JSONObject().apply {
                            put("action", action.name)
                            put("label", action.localizedLabel(lang))
                            put("labelZh", action.label)
                            put("labelEn", action.labelEn)
                            put("shortKey", RemoteBindings.bindingSummary(b, false, lang))
                            put("longKey", RemoteBindings.bindingSummary(b, true, lang))
                            put("shortKeyZh", RemoteBindings.bindingSummary(b, false, AppLang.ZH))
                            put("longKeyZh", RemoteBindings.bindingSummary(b, true, AppLang.ZH))
                            put("shortKeyEn", RemoteBindings.bindingSummary(b, false, AppLang.EN))
                            put("longKeyEn", RemoteBindings.bindingSummary(b, true, AppLang.EN))
                        }
                    )
                }
            }

            return JSONObject().apply {
                put("ok", true)
                put("language", Prefs.getAppLanguage(appContext).code)
                put("appName", runCatching { appContext.getString(R.string.app_name) }.getOrElse { "IQTV Lite" })
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("packageName", BuildConfig.APPLICATION_ID)
                put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("androidSdk", Build.VERSION.SDK_INT)

                put("sources", sourcesArr)
                put("activeSourceId", activeSourceId)

                put("globalUserAgent", Prefs.getGlobalUserAgent(appContext))
                put("globalCustomHeaders", Prefs.getGlobalCustomHeaders(appContext))

                put("uiScale", Prefs.getUiScale(appContext).id)
                put("infoOkMode", if (Prefs.getInfoOkMode(appContext) == InfoCardPolicy.InfoOkMode.DIRECT) "direct" else "two_step")
                put("playbackBufferMin", Prefs.getPlaybackBufferMin(appContext))
                put("autoUpdatePlaylist", Prefs.getAutoUpdatePlaylist(appContext).name)
                put("autoUpdateEpg", Prefs.getAutoUpdateEpg(appContext).name)
                put("logoPrefetch", Prefs.isLogoPrefetchEnabled(appContext))
                put("logoLoad", Prefs.isLogoLoadEnabled(appContext))

                put("remoteUpDown", RemoteBindings.deriveUpDownHabit(bindings).name)
                put("remoteOk", RemoteBindings.deriveOkHabit(bindings).name)
                put("remoteDigits", remoteSettings.digits.name)
                put("remoteChannelKeys", RemoteBindings.deriveChannelKeysEnabled(bindings))
                put("remoteBindings", bindingsArr)
            }
        }

        private fun parsePayload(session: IHTTPSession): JSONObject {
            val files = HashMap<String, String>()
            return try {
                session.parseBody(files)
                val raw = files["postData"]?.trim().orEmpty()
                if (raw.isNotEmpty() && raw.startsWith("{")) {
                    JSONObject(raw)
                } else {
                    val live = session.parameters["liveSource"]?.firstOrNull().orEmpty()
                    val epg = session.parameters["epgSource"]?.firstOrNull().orEmpty()
                    JSONObject(mapOf("liveSource" to live, "epgSource" to epg))
                }
            } catch (_: Exception) {
                JSONObject()
            }
        }

        private fun jsonOk(body: String): Response =
            cors(newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body))

        private fun jsonError(msg: String): Response =
            cors(
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json; charset=utf-8",
                    JSONObject().put("ok", false).put("error", msg).toString(),
                )
            )

        private fun cors(response: Response): Response {
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type")
            response.addHeader("Cache-Control", "no-store")
            return response
        }
    }
}
