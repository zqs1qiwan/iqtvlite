package com.cjlhll.iptv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/**
 * Single source of truth for the channel-browser sidebar (t61 / M1).
 *
 * Owned by `PlayerActivity` and read by Compose via `state.collectAsState()`; M2's native
 * RecyclerView sidebar will read the same state. Keeping the TIV semantics here (open/close,
 * single-Back, channel step with wrap, group/date filtering, initial focus, OK-keeps-drawer)
 * makes them unit-testable without Android and prevents a second state machine in the native layer.
 */
data class SidebarState(
    val channelGroups: List<ChannelGroup> = emptyList(),
    val currentGroupIndex: Int = 0,
    val currentVariantIndex: Int = 0,
    val selectedChannelUrl: String? = null,
    val focusedChannelUrl: String? = null,
    val drawerOpen: Boolean = false,
    val activeColumn: DrawerColumn? = null,
    val selectedGroup: String = ALL_GROUP,
    val selectedEpgDate: LocalDate? = null,
    val epgDates: List<LocalDate> = emptyList(),
    val showGroups: Boolean = false,
    val showDates: Boolean = false,
    val selectedProgramKey: String? = null,
    // FULLFIX §2：每个分组的「上次浏览焦点」URL（会话内记忆，不持久化）。
    // 与 focusedChannelUrl 正交：后者是当前焦点，这里是跨开关/切组恢复用的按组记忆。
    val lastFocusedByGroup: Map<String, String> = emptyMap(),
    // NS-03: EPG (large maps) is kept OUT of the StateFlow equality; only a version counter changes.
    val epgVersion: Int = 0,
) {
    companion object {
        const val ALL_GROUP = "全部"
    }
}

class SidebarController {

    private val _state = MutableStateFlow(SidebarState())
    val state: StateFlow<SidebarState> = _state.asStateFlow()

    /** Snapshot for non-Compose callers (tests / native view). */
    val value: SidebarState get() = _state.value

    // NS-03: not part of SidebarState equality (avoids O(programs) deep-equals on every focus move).
    @Volatile
    private var epgDataRef: EpgData? = null
    fun epgData(): EpgData? = epgDataRef

    // ---- channel list / selection sync (M2 native layer reads the same state) ----

    fun setChannelGroups(groups: List<ChannelGroup>) = _state.update { s ->
        val previousUrl = s.selectedChannelUrl ?: s.channelGroups.getOrNull(s.currentGroupIndex)?.defaultChannelOrNull()?.url
        val resolvedIndex = groups.indexOfFirst { group -> group.variants.any { it.channel.url == previousUrl } }
            .takeIf { it >= 0 }
            ?: s.currentGroupIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
        s.copy(
            channelGroups = groups,
            currentGroupIndex = resolvedIndex,
            selectedChannelUrl = previousUrl ?: groups.getOrNull(resolvedIndex)?.defaultChannelOrNull()?.url,
        )
    }

    fun setSelection(groupIndex: Int, variantIndex: Int) = _state.update { s ->
        val channel = s.channelGroups.getOrNull(groupIndex)?.variants?.getOrNull(variantIndex)?.channel
        s.copy(
            currentGroupIndex = groupIndex,
            currentVariantIndex = variantIndex,
            selectedChannelUrl = channel?.url ?: s.selectedChannelUrl,
        )
    }

    fun setSelectedChannelUrl(url: String?) = _state.update { it.copy(selectedChannelUrl = url) }

    // ---- drawer visibility / single-Back ----

    /**
     * t59 ⑤：从播放器按 LEFT/OK 打开抽屉时，以**当前播放台 URL**为定位锚点（不从头）。
     * 播放台 URL 有效则优先；否则退回仍有效的上次浏览焦点。切组/分组记忆逻辑不回退。
     */
    fun openDrawer() = _state.update { s ->
        val playing = s.selectedChannelUrl?.takeIf { url ->
            s.channelGroups.any { group -> group.variants.any { it.channel.url == url } }
        }
        val existingFocus = s.focusedChannelUrl?.takeIf { url ->
            s.channelGroups.any { group -> group.variants.any { it.channel.url == url } }
        }
        s.copy(
            drawerOpen = true,
            focusedChannelUrl = playing ?: existingFocus,
            activeColumn = DrawerColumn.Channels,
            showGroups = false,
            showDates = false,
        )
    }

    fun closeDrawer() = _state.update { it.copy(drawerOpen = false) }

    /** TIV: a single Back closes the whole drawer (from any column). */
    fun onBack(): Boolean {
        if (!_state.value.drawerOpen) return false
        closeDrawer()
        return true
    }

    // ---- columns / group + date filters (only filter, keep the drawer open) ----

    fun setActiveColumn(column: DrawerColumn?) = _state.update { it.copy(activeColumn = column) }
    fun setShowGroups(show: Boolean) = _state.update { it.copy(showGroups = show) }
    fun setShowDates(show: Boolean) = _state.update { it.copy(showDates = show) }
    fun toggleGroups() = _state.update { it.copy(showGroups = !it.showGroups) }
    fun toggleDates() = _state.update { it.copy(showDates = !it.showDates) }

    /**
     * V4 §2：幂等切组 —— 组名真变化才更新 state（focus 事件重复触发不引发 filteredChannels 重算）。
     * @param resetFocus 由分组列聚焦/点击触发时为 true：清掉该组记忆，使进入后从第一台开始。
     */
    fun selectGroup(name: String, resetFocus: Boolean = false) {
        val cur = _state.value
        if (cur.selectedGroup != name) {
            _state.update { s ->
                val nextFocus = if (resetFocus) null else s.focusedChannelUrl
                val nextMap = if (resetFocus) s.lastFocusedByGroup - name else s.lastFocusedByGroup
                s.copy(selectedGroup = name, focusedChannelUrl = nextFocus, lastFocusedByGroup = nextMap)
            }
        } else if (resetFocus) {
            // 组名相同但要"从头开始"：仍清焦点与记忆（state 变更一次）。
            _state.update { s ->
                s.copy(focusedChannelUrl = null, lastFocusedByGroup = s.lastFocusedByGroup - name)
            }
        }
    }
    fun selectEpgDate(date: LocalDate?) = _state.update { it.copy(selectedEpgDate = date, showDates = true) }
    fun setEpgDates(dates: List<LocalDate>) = _state.update { it.copy(epgDates = dates) }
    fun setEpgData(data: EpgData?) {
        epgDataRef = data
        _state.update { it.copy(epgVersion = it.epgVersion + 1) }
    }
    fun selectProgram(key: String?) = _state.update { it.copy(selectedProgramKey = key) }

    // ---- focus / channel stepping ----

    // FULLFIX §2：focusChannel 同时按当前 selectedGroup 记住浏览焦点；selectGroup 不清该 map。
    fun focusChannel(url: String?) = _state.update { s ->
        val next = if (url.isNullOrBlank()) s.lastFocusedByGroup else s.lastFocusedByGroup + (s.selectedGroup to url)
        s.copy(focusedChannelUrl = url, lastFocusedByGroup = next)
    }

    /** FULLFIX §2：查询某组上次浏览焦点（URL 在该组已不存在时由调用方回退到 0）。 */
    fun lastFocusedUrlFor(group: String): String? = _state.value.lastFocusedByGroup[group]

    fun effectiveFocusedChannelUrl(): String? = _state.value.focusedChannelUrl ?: _state.value.selectedChannelUrl

    /** Channel up/down with TIV wrap-around; returns the new group index (or null when empty). */
    fun stepChannel(delta: Int): Int? {
        val s = _state.value
        val count = s.channelGroups.size
        if (count == 0 || delta == 0) return null
        val next = Math.floorMod(s.currentGroupIndex + delta, count)
        setSelection(next, 0)
        return next
    }

    /** Channel OK: switch the channel AND keep the drawer open (TIV). */
    fun activateChannel(groupIndex: Int, variantIndex: Int) {
        setSelection(groupIndex, variantIndex)
        _state.update { it.copy(focusedChannelUrl = it.selectedChannelUrl) }
    }

    /** Global channel number (variant-aware) used by the drawer/info bar. */
    fun channelNumber(url: String?): Int? =
        url?.let { ChannelGrouper.globalNumberByUrl(_state.value.channelGroups)[it] }

    /** Channels for the selected group, preserving global numbering. */
    fun filteredChannels(): List<Channel> =
        ChannelGrouper.numberedDisplayChannelsForGroup(_state.value.channelGroups, _state.value.selectedGroup)
            .map { it.channel }
}

private fun ChannelGroup.defaultChannelOrNull(): Channel? =
    variants.getOrNull(defaultVariantIndex)?.channel ?: variants.firstOrNull()?.channel
