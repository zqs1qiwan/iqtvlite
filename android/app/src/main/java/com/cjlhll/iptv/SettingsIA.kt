package com.cjlhll.iptv

/**
 * t3（SETTINGS_IA_V2）：设置二级信息架构的**纯逻辑**模型。
 *
 * 这里刻意不 import 任何 Compose / Android 类型：UI 在 `SettingsDrawer.kt`，模型与导航策略
 * 在 JVM 单测里可直接验证（`SettingsIaTest` / `SettingsZeroLossTest` / `SettingsBackStackTest`
 * / `SettingsSummaryTest`）。规格见 docs/SETTINGS_IA_V2.md。
 */

/** 七个二级页（无家长控制）；[id] 同时用于 `settings_page_<id>` / `settings_root_<id>` testTag。 */
enum class SettingsPage(val id: String, val title: String, val titleEn: String) {
    GENERAL("general", "通用", "General"),
    PLAYLISTS("playlists", "节目列表", "Playlists"),
    EPG("epg", "节目预告", "EPG"),
    APPEARANCE("appearance", "外观", "Appearance"),
    PLAYBACK("playback", "播放解码", "Playback"),
    REMOTE("remote", "遥控设置", "Remote Control"),
    ABOUT("about", "关于与更新", "About & Updates");

    fun localizedTitle(lang: AppLang): String = if (lang == AppLang.EN) titleEn else title
}

/** 一级入口行：标题取 [page].title，[summary] 由 [SettingsSummary] 生成。 */
data class SettingsRootEntry(
    val page: SettingsPage,
    val tag: String,
    val summary: String,
)

object SettingsIA {
    /** 抽屉内滚动容器的 tag（既有门禁 check_settings_scroll.py 依赖）。 */
    const val LIST_TAG = "settings_list"
    /** 一级容器 / 标题 tag。 */
    const val ROOT_CONTAINER_TAG = "settings_root"
    const val ROOT_TITLE_TAG = "settings_root_title"

    /** 一级入口顺序（7 大分类，不得随意调整）。 */
    val ROOT_ORDER: List<SettingsPage> = listOf(
        SettingsPage.GENERAL,
        SettingsPage.PLAYLISTS,
        SettingsPage.EPG,
        SettingsPage.APPEARANCE,
        SettingsPage.PLAYBACK,
        SettingsPage.REMOTE,
        SettingsPage.ABOUT,
    )

    /** 每页可聚焦行的 tag，顺序即展示顺序（含条件行，UI 侧按可用性过滤）。 */
    private val PAGE_ITEMS: Map<SettingsPage, List<String>> = mapOf(
        SettingsPage.GENERAL to listOf(
            "settings_auto_start",
            "settings_resume_last",
            "settings_home_pip",
            "settings_double_back_exit",
            "settings_global_ua",
            "settings_global_headers",
            "settings_source_config",
        ),
        SettingsPage.PLAYLISTS to listOf(
            "settings_source_manage",
            "settings_refresh_playlist",
            "settings_auto_playlist",
            "settings_recent_entry",
        ),
        SettingsPage.EPG to listOf(
            "settings_epg_retention",
            "settings_auto_epg",
            "settings_epg_on_playlist_change",
            "settings_refresh_epg",
        ),
        SettingsPage.APPEARANCE to listOf(
            "settings_language",
            "settings_ui_scale",
            "settings_ui_transparency",
            "settings_info_panel_timeout",
            "settings_show_info_on_zap",
            "settings_show_clock",
            "settings_show_exact_resolution",
            "settings_show_favorites_group",
            "settings_show_all_group",
            "settings_info_ok_mode",
            "settings_logo_prefetch",
            "settings_logo_load",
            "settings_logo_refresh",
            "settings_logo_clear",
        ),
        SettingsPage.PLAYBACK to listOf(
            "settings_playback_buffer",
            "settings_video_decoder",
            "settings_rebuild_4k_zap",
            "settings_default_subtitles",
            "settings_subtitle_lang",
            "settings_audio_track",
            "settings_quality_menu",
            "settings_zap_preconnect",
            "settings_multicast_prewarm",
            "settings_jingcai_experiment",
        ),
        SettingsPage.REMOTE to listOf(
            "settings_remote_updown",
            "settings_remote_ok",
            "settings_remote_digits",
            "settings_remote_channel_keys",
            "settings_remote_reset",
        ),
        SettingsPage.ABOUT to listOf(
            "settings_auto_check_update",
            "settings_check_update",
            "settings_about_project",
        ),
    )

    fun itemTags(page: SettingsPage): List<String> = PAGE_ITEMS.getValue(page)

    /** 含动态自定义按键行在内的所有可聚焦 tag（用于焦点记忆校验）。 */
    fun focusableTags(page: SettingsPage): List<String> {
        val base = PAGE_ITEMS.getValue(page)
        if (page != SettingsPage.REMOTE) return base
        val extra = RemoteBindings.CONFIGURABLE_ACTIONS.flatMap { action ->
            listOf("settings_remote_action_${action.name}", "settings_remote_bind_long_${action.name}")
        } + "settings_remote_bindings_reset"
        return base + extra
    }

    fun rootTag(page: SettingsPage): String = "settings_root_${page.id}"

    fun pageTitleTag(page: SettingsPage): String = "settings_page_${page.id}"

    /**
     * 迁移矩阵（docs/SETTINGS_IA_V2.md §4）：旧 tag → 新页面。含已退役的
     * `settings_epg_source`（映射到播放列表及节目单页，动作由 `settings_refresh_epg` 承载）。
     */
    val LEGACY_TAG_PAGE: Map<String, SettingsPage> = buildMap {
        put("settings_source_config", SettingsPage.GENERAL)
        put("settings_epg_source", SettingsPage.EPG)
        put("settings_quality_menu", SettingsPage.PLAYBACK)
        put("settings_check_update", SettingsPage.ABOUT)
        put("settings_recent_entry", SettingsPage.PLAYLISTS)
        put("settings_playback_buffer", SettingsPage.PLAYBACK)
        put("settings_zap_preconnect", SettingsPage.PLAYBACK)
        put("settings_multicast_prewarm", SettingsPage.PLAYBACK)
        put("settings_jingcai_experiment", SettingsPage.PLAYBACK)
        put("settings_remote_updown", SettingsPage.REMOTE)
        put("settings_remote_ok", SettingsPage.REMOTE)
        put("settings_remote_back", SettingsPage.REMOTE)
        put("settings_remote_digits", SettingsPage.REMOTE)
        put("settings_remote_channel_keys", SettingsPage.REMOTE)
        put("settings_remote_reset", SettingsPage.REMOTE)
        put("settings_audio_track", SettingsPage.PLAYBACK)
        put("settings_auto_playlist", SettingsPage.PLAYLISTS)
        put("settings_refresh_playlist", SettingsPage.PLAYLISTS)
        put("settings_auto_epg", SettingsPage.EPG)
        put("settings_refresh_epg", SettingsPage.EPG)
        put("settings_logo_prefetch", SettingsPage.APPEARANCE)
        put("settings_logo_load", SettingsPage.APPEARANCE)
        put("settings_logo_refresh", SettingsPage.APPEARANCE)
        put("settings_logo_clear", SettingsPage.APPEARANCE)
    }

    /** 已合并/退役的 tag（settings_epg_source 并入 settings_refresh_epg；settings_remote_back 统一由 BackKeyPolicy 分层双按承载）。 */
    val RETIRED_TAGS: Set<String> = setOf("settings_epg_source", "settings_remote_back")

    /**
     * 迁移矩阵的基线行（**23 个旧 tag + 1 个新增 `settings_logo_refresh`，共 24 个**；
     * 其中 `settings_epg_source` 已退役，见 [RETIRED_TAGS]）。名字沿用 LEGACY 是为了保留
     * 零丢失审计的可追溯性；不要误以为“24 个全是旧 tag”（t16/F4 修正注释）。
     */
    val LEGACY_ACTION_TAGS: List<String> = listOf(
        "settings_source_config",
        "settings_epg_source",
        "settings_quality_menu",
        "settings_check_update",
        "settings_recent_entry",
        "settings_playback_buffer",
        "settings_zap_preconnect",
        "settings_multicast_prewarm",
        "settings_jingcai_experiment",
        "settings_remote_updown",
        "settings_remote_ok",
        "settings_remote_back",
        "settings_remote_digits",
        "settings_remote_channel_keys",
        "settings_remote_reset",
        "settings_audio_track",
        "settings_auto_playlist",
        "settings_refresh_playlist",
        "settings_auto_epg",
        "settings_refresh_epg",
        "settings_logo_prefetch",
        "settings_logo_load",
        "settings_logo_refresh",
        "settings_logo_clear",
    )

    /** 一级入口（含摘要），供 UI 直接渲染。 */
    fun rootEntries(summaries: Map<SettingsPage, String>): List<SettingsRootEntry> =
        ROOT_ORDER.map { page ->
            SettingsRootEntry(
                page = page,
                tag = rootTag(page),
                summary = summaries[page] ?: SettingsSummary.EMPTY,
            )
        }
}

/**
 * t3：Back/Left 层级状态机（规格 §5.1）。
 *
 * 只建模「层级跳转」这件事；页内的上下移动仍由 Compose 焦点系统处理（真机行为一致，
 * 也避免把焦点引擎复制一份）。`consumed` 表示该按键是否被消费（不能穿透到播放器）。
 */
object SettingsNavPolicy {
    sealed interface State {
        data object Closed : State
        data class Root(val focus: Int) : State
        data class Page(val page: SettingsPage, val focusTag: String) : State
    }

    enum class Key { UP, DOWN, LEFT, RIGHT, OK, BACK }

    fun rootIndexFor(page: SettingsPage): Int = SettingsIA.ROOT_ORDER.indexOf(page)

    fun firstTag(page: SettingsPage): String = SettingsIA.itemTags(page).first()

    /**
     * 计算按键后的层级状态。
     *
     * - `Root + BACK/LEFT` → `Closed`（只退一层，一次按键不会退出播放器）
     * - `Page + BACK/LEFT` → `Root(打开它的那一行)`（只退一层）
     * - `Page + RIGHT` → 状态不变但 `consumed = true`（不得穿透到播放器的“右开设置”）
     * - `Root + OK/RIGHT` → 进入焦点行对应的页，焦点落该页第一行
     */
    fun onKey(state: State, key: Key, pageTags: List<String> = emptyList()): Pair<State, Boolean> =
        when (state) {
            State.Closed -> Pair(state, false)
            is State.Root -> when (key) {
                Key.BACK, Key.LEFT -> Pair(State.Closed, true)
                Key.OK, Key.RIGHT -> {
                    val index = state.focus.coerceIn(0, SettingsIA.ROOT_ORDER.lastIndex)
                    val page = SettingsIA.ROOT_ORDER[index]
                    Pair(State.Page(page, firstTag(page)), true)
                }
                else -> Pair(state, false)
            }
            is State.Page -> when (key) {
                Key.BACK, Key.LEFT -> Pair(State.Root(rootIndexFor(state.page)), true)
                Key.RIGHT -> Pair(state, true)
                else -> Pair(state, false)
            }
        }
}

/**
 * t3：一级入口摘要（规格 §3.1）。全部纯函数，输出单行、≤ [MAX_DISPLAY_UNITS] 半角宽度
 * （≈22 全角字符），供 UI 以 `maxLines = 1 + Ellipsis` 兜底。
 */
object SettingsSummary {
    const val EMPTY = "—"

    /** 252dp / 12sp ≈ 21 全角字；留 1 字余量 → 44 半角单位（= 22 全角）。 */
    const val MAX_DISPLAY_UNITS = 44

    fun playback(bufferLabel: String, anyExperimentOn: Boolean, lang: AppLang = AppLang.ZH): String {
        if (lang == AppLang.EN) {
            val base = "Buffer $bufferLabel"
            return clamp(if (anyExperimentOn) "$base · Exp On" else base)
        }
        val base = "起播缓冲 $bufferLabel"
        return clamp(if (anyExperimentOn) "$base · 实验开" else base)
    }

    fun remote(upDown: String, ok: String): String = clamp("$upDown · $ok")

    fun audio(summary: String?, lang: AppLang = AppLang.ZH): String {
        if (summary.isNullOrBlank()) return EMPTY
        return if (lang == AppLang.EN) clamp("Track $summary") else clamp("音轨 $summary")
    }

    fun data(playlist: String, epg: String, lang: AppLang = AppLang.ZH): String =
        if (lang == AppLang.EN) clamp("List $playlist · EPG $epg") else clamp("列表 $playlist · EPG $epg")

    /** 台标统计：抽屉打开时才由 UI 计算；无数据（0/0 或负数）显示 [EMPTY]。 */
    fun logo(memoryEntries: Int, diskFiles: Int, lang: AppLang = AppLang.ZH): String {
        if (memoryEntries <= 0 && diskFiles <= 0) return EMPTY
        val mem = memoryEntries.coerceAtLeast(0)
        val disk = diskFiles.coerceAtLeast(0)
        return if (lang == AppLang.EN) clamp("$mem mem / $disk files") else clamp("$mem 条 / $disk 个")
    }

    /**
     * 从 PlayerActivity 既有的 `logoCacheSummary`（"内存 N 条 · 磁盘 M 个 / K KB"）派生一级摘要，
     * 避免抽屉打开时重复做一次 `LogoLoader.stats` 磁盘 IO。解析失败返回 [EMPTY]。
     */
    fun logoFromCacheSummary(cacheSummary: String?, lang: AppLang = AppLang.ZH): String {
        if (cacheSummary.isNullOrBlank()) return EMPTY
        val match = MEMORY_DISK_REGEX.find(cacheSummary) ?: return EMPTY
        val memory = match.groupValues[1].toIntOrNull() ?: return EMPTY
        val disk = match.groupValues[2].toIntOrNull() ?: return EMPTY
        return logo(memory, disk, lang)
    }

    fun about(versionName: String, lang: AppLang = AppLang.ZH): String {
        val trimmed = versionName.trim()
        val label = if (trimmed.isEmpty()) EMPTY
        else if (trimmed.startsWith("v", ignoreCase = true)) trimmed
        else "v$trimmed"
        return clamp(label)
    }

    private val MEMORY_DISK_REGEX = Regex("""内存\s*(\d+)\s*条\s*·\s*磁盘\s*(\d+)\s*个""")

    /** 半角单位宽度：ASCII 记 1，其它（CJK/全角标点）记 2。 */
    fun displayUnits(text: String): Int = text.sumOf { if (it.code < 0x80) 1 else 2 }

    /** 全角字长度（向上取整），测试用：≤22。 */
    fun fullWidthLength(text: String): Int = (displayUnits(text) + 1) / 2

    /** 超宽截断（按半角单位），末尾补省略号；不会截断到半个字符。 */
    fun clamp(text: String, maxUnits: Int = MAX_DISPLAY_UNITS): String {
        val trimmed = text.trim()
        if (displayUnits(trimmed) <= maxUnits) return trimmed
        var used = 0
        val builder = StringBuilder()
        for (ch in trimmed) {
            val unit = if (ch.code < 0x80) 1 else 2
            if (used + unit > maxUnits - 2) break // 给省略号留 2 个半角单位
            builder.append(ch)
            used += unit
        }
        return builder.toString().trimEnd() + "…"
    }
}
