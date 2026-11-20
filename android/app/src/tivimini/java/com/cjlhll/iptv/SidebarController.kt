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
    val epgData: EpgData? = null,
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

    fun setDrawerOpen(open: Boolean) {
        if (open) openDrawer() else closeDrawer()
    }

    /** TIV: on first open focus the currently playing channel; afterwards keep a valid browse focus. */
    fun openDrawer() = _state.update { s ->
        val existingFocus = s.focusedChannelUrl?.takeIf { url ->
            s.channelGroups.any { group -> group.variants.any { it.channel.url == url } }
        }
        s.copy(
            drawerOpen = true,
            focusedChannelUrl = existingFocus ?: s.selectedChannelUrl,
            activeColumn = DrawerColumn.Channels,
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

    fun selectGroup(name: String) = _state.update { it.copy(selectedGroup = name) }
    fun selectEpgDate(date: LocalDate?) = _state.update { it.copy(selectedEpgDate = date, showDates = true) }
    fun setEpgDates(dates: List<LocalDate>) = _state.update { it.copy(epgDates = dates) }
    fun setEpgData(data: EpgData?) = _state.update { it.copy(epgData = data) }
    fun selectProgram(key: String?) = _state.update { it.copy(selectedProgramKey = key) }

    // ---- focus / channel stepping ----

    fun focusChannel(url: String?) = _state.update { it.copy(focusedChannelUrl = url) }

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
