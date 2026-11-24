package com.cjlhll.iptv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 播放器覆盖层单一状态机（Single Source of Truth）。
 *
 * 彻底取代历史分散在 PlayerActivity 与 VideoPlayerScreen 之间的 10+ 独立 Boolean 双向镜像：
 * - 所有浮层互斥关系在此类型层天然保证（同一时刻只能有一个顶层 Overlay，绝不叠层）；
 * - PlayerActivity.dispatchKeyEvent 同步直读 [currentOverlay]，零 LaunchedEffect/SideEffect 延迟竞态；
 * - 纯 Kotlin（无 Android/Compose 依赖），100% 可 JVM 单测。
 */
sealed interface PlayerOverlay {
    /** 全屏无浮层播放态。 */
    data object None : PlayerOverlay

    /** 底部信息卡（IDLE 展示态 / FOCUSED 最近观看选择态）。交互时间戳独立维护，避免按键导致整屏重组。 */
    data class InfoCard(
        val focused: Boolean = false,
        val focusIndex: Int = 0,
    ) : PlayerOverlay {
        val policyState: InfoCardPolicy.State
            get() = if (focused) InfoCardPolicy.State.FOCUSED else InfoCardPolicy.State.IDLE
    }

    /** 左侧选台 + 节目单抽屉（交互时间戳独立维护，确保高频切台/翻页零 Compose 重组）。 */
    data object Drawer : PlayerOverlay

    /** 右侧设置抽屉（包含一级 / 二级页 / 三级展开选项选择器状态，彻底消除 BackHandler 副作用探针）。 */
    data class Settings(
        val page: SettingsPage? = null,
        val rootFocusIndex: Int = 0,
        val picker: String? = null,
    ) : PlayerOverlay

    /** 居中源/线路选择弹窗。 */
    data class QualityDialog(
        val groupIndex: Int,
    ) : PlayerOverlay

    /** 居中音轨选择弹窗。 */
    data object AudioDialog : PlayerOverlay

    /** 居中最近观看弹窗。 */
    data object RecentDialog : PlayerOverlay

    /** 应用更新确认弹窗（纳入顶层守卫，绝不穿透到底层切台/退出）。 */
    data object UpdateDialog : PlayerOverlay
}

/** 数字跳台 OSD 状态（与 PlayerOverlay 正交；任何全屏抽屉/弹窗打开时自动清空）。 */
data class DigitOsdState(
    val digits: String = "",
    val confirmed: Boolean = false,
) {
    val visible: Boolean get() = digits.isNotEmpty()
}

/**
 * 播放器 UI 单一状态控制器：同步原子迁移所有覆盖层、捕获态与数字 OSD 状态。
 */
class PlayerUiController(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _overlay = MutableStateFlow<PlayerOverlay>(PlayerOverlay.None)
    val overlay: StateFlow<PlayerOverlay> = _overlay.asStateFlow()

    private val _digitOsd = MutableStateFlow(DigitOsdState())
    val digitOsd: StateFlow<DigitOsdState> = _digitOsd.asStateFlow()

    /** 设置页按键捕获请求；离开设置页或关闭设置抽屉时自动取消，绝不泄漏到播放态。 */
    private val _captureRequest = MutableStateFlow<Pair<RemoteAction, Boolean>?>(null)
    val captureRequest: StateFlow<Pair<RemoteAction, Boolean>?> = _captureRequest.asStateFlow()

    /** 独立交互计时流：仅由后台自动隐藏定时器订阅，绝不触发顶层 Compose 重组。 */
    private val _drawerLastInteractionMs = MutableStateFlow(0L)
    val drawerLastInteractionMs: StateFlow<Long> = _drawerLastInteractionMs.asStateFlow()

    private val _infoCardLastInteractionMs = MutableStateFlow(0L)
    val infoCardLastInteractionMs: StateFlow<Long> = _infoCardLastInteractionMs.asStateFlow()

    val currentOverlay: PlayerOverlay get() = _overlay.value
    val currentDigits: DigitOsdState get() = _digitOsd.value
    val currentCapture: Pair<RemoteAction, Boolean>? get() = _captureRequest.value
    val currentDrawerInteractionMs: Long get() = _drawerLastInteractionMs.value
    val currentInfoCardInteractionMs: Long get() = _infoCardLastInteractionMs.value

    /** 是否存在阻断播放态快捷键的覆盖层（不含 InfoCard，InfoCard 有自己独立的按键策略）。 */
    val isBlockingOverlayOpen: Boolean
        get() = when (_overlay.value) {
            PlayerOverlay.None, is PlayerOverlay.InfoCard -> false
            else -> true
        }

    val isDrawerOpen: Boolean get() = _overlay.value is PlayerOverlay.Drawer
    val isSettingsOpen: Boolean get() = _overlay.value is PlayerOverlay.Settings
    val isQualityDialogOpen: Boolean get() = _overlay.value is PlayerOverlay.QualityDialog
    val isAudioDialogOpen: Boolean get() = _overlay.value is PlayerOverlay.AudioDialog
    val isRecentDialogOpen: Boolean get() = _overlay.value is PlayerOverlay.RecentDialog
    val isUpdateDialogOpen: Boolean get() = _overlay.value is PlayerOverlay.UpdateDialog
    val isInfoCardVisible: Boolean get() = _overlay.value is PlayerOverlay.InfoCard
    val isInfoCardFocused: Boolean get() = (_overlay.value as? PlayerOverlay.InfoCard)?.focused == true

    // ── 覆盖层迁移（全部同步原子，自动清理互斥态与捕获态） ─────────────────

    fun closeAll() {
        _captureRequest.value = null
        _overlay.value = PlayerOverlay.None
    }

    fun openDrawer() {
        clearDigits()
        _captureRequest.value = null
        _drawerLastInteractionMs.value = clockMs()
        _overlay.value = PlayerOverlay.Drawer
    }

    fun closeDrawer() {
        if (_overlay.value is PlayerOverlay.Drawer) {
            _overlay.value = PlayerOverlay.None
        }
    }

    /** 抽屉打开期间任意列（分组/频道/节目/日期）收到任何交互均刷新计时，且零重组。 */
    fun noteDrawerInteraction() {
        if (_overlay.value is PlayerOverlay.Drawer) {
            _drawerLastInteractionMs.value = clockMs()
        }
    }

    fun openSettings(rootFocusIndex: Int = 0) {
        clearDigits()
        _captureRequest.value = null
        val keepIndex = (_overlay.value as? PlayerOverlay.Settings)?.rootFocusIndex ?: rootFocusIndex
        _overlay.value = PlayerOverlay.Settings(page = null, rootFocusIndex = keepIndex, picker = null)
    }

    fun openSettingsPage(page: SettingsPage) {
        _captureRequest.value = null
        _overlay.update { cur ->
            if (cur is PlayerOverlay.Settings) {
                cur.copy(page = page, rootFocusIndex = SettingsNavPolicy.rootIndexFor(page), picker = null)
            } else {
                PlayerOverlay.Settings(page = page, rootFocusIndex = SettingsNavPolicy.rootIndexFor(page), picker = null)
            }
        }
    }

    fun openSettingsPicker(pickerId: String) {
        _captureRequest.value = null
        _overlay.update { cur ->
            if (cur is PlayerOverlay.Settings) cur.copy(picker = pickerId) else cur
        }
    }

    fun closeSettingsPicker(): Boolean {
        val cur = _overlay.value as? PlayerOverlay.Settings ?: return false
        if (cur.picker == null) return false
        _captureRequest.value = null
        _overlay.value = cur.copy(picker = null)
        return true
    }

    fun setSettingsRootFocus(index: Int) {
        _overlay.update { cur ->
            if (cur is PlayerOverlay.Settings) {
                cur.copy(rootFocusIndex = index.coerceIn(0, SettingsIA.ROOT_ORDER.lastIndex))
            } else cur
        }
    }

    /** 逐层回退：源详情 → 源列表 → 二级页 → 设置根页。 */
    fun settingsBackToRoot(): Boolean {
        val cur = _overlay.value as? PlayerOverlay.Settings ?: return false
        if (cur.picker != null) {
            _captureRequest.value = null
            val parentPicker = if (cur.picker.startsWith("source_detail:")) "sources_manage" else null
            _overlay.value = cur.copy(picker = parentPicker)
            return true
        }
        if (cur.page == null) return false
        _captureRequest.value = null
        _overlay.value = cur.copy(page = null)
        return true
    }

    fun closeSettings() {
        _captureRequest.value = null
        if (_overlay.value is PlayerOverlay.Settings) {
            _overlay.value = PlayerOverlay.None
        }
    }

    fun openQualityDialog(groupIndex: Int) {
        clearDigits()
        _captureRequest.value = null
        _overlay.value = PlayerOverlay.QualityDialog(groupIndex = groupIndex)
    }

    fun closeQualityDialog() {
        if (_overlay.value is PlayerOverlay.QualityDialog) {
            _overlay.value = PlayerOverlay.None
        }
    }

    fun openAudioDialog() {
        clearDigits()
        _captureRequest.value = null
        _overlay.value = PlayerOverlay.AudioDialog
    }

    fun closeAudioDialog() {
        if (_overlay.value is PlayerOverlay.AudioDialog) {
            _overlay.value = PlayerOverlay.None
        }
    }

    fun openRecentDialog() {
        clearDigits()
        _captureRequest.value = null
        _overlay.value = PlayerOverlay.RecentDialog
    }

    fun closeRecentDialog() {
        if (_overlay.value is PlayerOverlay.RecentDialog) {
            _overlay.value = PlayerOverlay.None
        }
    }

    fun openUpdateDialog() {
        clearDigits()
        _captureRequest.value = null
        _overlay.value = PlayerOverlay.UpdateDialog
    }

    fun closeUpdateDialog() {
        if (_overlay.value is PlayerOverlay.UpdateDialog) {
            _overlay.value = PlayerOverlay.None
        }
    }

    // ── 信息卡状态迁移 ───────────────────────────────────────────────────────

    fun showInfoCard(focused: Boolean = false) {
        // 若阻断型覆盖层打开则不抢占顶层
        if (isBlockingOverlayOpen) return
        _infoCardLastInteractionMs.value = clockMs()
        _overlay.value = PlayerOverlay.InfoCard(
            focused = focused,
            focusIndex = 0,
        )
    }

    fun hideInfoCard() {
        if (_overlay.value is PlayerOverlay.InfoCard) {
            _overlay.value = PlayerOverlay.None
        }
    }

    fun focusInfoCard(focusIndex: Int = 0) {
        _infoCardLastInteractionMs.value = clockMs()
        _overlay.update { cur ->
            if (cur is PlayerOverlay.InfoCard) {
                cur.copy(focused = true, focusIndex = focusIndex.coerceAtLeast(0))
            } else cur
        }
    }

    fun unfocusInfoCard() {
        _infoCardLastInteractionMs.value = clockMs()
        _overlay.update { cur ->
            if (cur is PlayerOverlay.InfoCard) {
                cur.copy(focused = false, focusIndex = 0)
            } else cur
        }
    }

    fun moveInfoCardFocus(delta: Int, count: Int) {
        _infoCardLastInteractionMs.value = clockMs()
        _overlay.update { cur ->
            if (cur is PlayerOverlay.InfoCard && cur.focused) {
                cur.copy(focusIndex = InfoCardPolicy.nextFocusIndex(cur.focusIndex, count, delta))
            } else cur
        }
    }

    fun noteInfoCardInteraction() {
        if (_overlay.value is PlayerOverlay.InfoCard) {
            _infoCardLastInteractionMs.value = clockMs()
        }
    }

    // ── 捕获态 ───────────────────────────────────────────────────────────────

    fun startCapture(action: RemoteAction, longPress: Boolean) {
        _captureRequest.value = action to longPress
    }

    fun cancelCapture() {
        _captureRequest.value = null
    }

    // ── 数字 OSD ─────────────────────────────────────────────────────────────

    fun appendDigit(digit: Int) {
        _digitOsd.update { cur ->
            val base = if (cur.confirmed) "" else cur.digits
            DigitOsdState(digits = DigitOsdPolicy.append(base, digit), confirmed = false)
        }
    }

    fun markDigitsConfirmed() {
        _digitOsd.update { cur ->
            if (cur.digits.isEmpty()) cur else cur.copy(confirmed = true)
        }
    }

    fun clearDigits() {
        if (_digitOsd.value.digits.isNotEmpty() || _digitOsd.value.confirmed) {
            _digitOsd.value = DigitOsdState()
        }
    }

    // ── Back 统一无副作用裁决 ────────────────────────────────────────────────

    fun decideBack(catchup: Boolean, lastBackWithin2s: Boolean): BackKeyPolicy.Action {
        val cur = _overlay.value
        return BackKeyPolicy.decide(
            audioDialogOpen = cur is PlayerOverlay.AudioDialog,
            recentOpen = cur is PlayerOverlay.RecentDialog,
            qualityDialogOpen = cur is PlayerOverlay.QualityDialog,
            drawerOpen = cur is PlayerOverlay.Drawer,
            settingsOpen = cur is PlayerOverlay.Settings,
            settingsHasSubPage = (cur as? PlayerOverlay.Settings)?.let { it.picker != null || it.page != null } == true,
            infoCardVisible = cur is PlayerOverlay.InfoCard,
            infoCardFocused = (cur as? PlayerOverlay.InfoCard)?.focused == true,
            catchup = catchup,
            lastBackWithin2s = lastBackWithin2s,
            updateDialogOpen = cur is PlayerOverlay.UpdateDialog,
        )
    }
}
