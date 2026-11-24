package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖重构后的单一覆盖层状态机 [PlayerUiController] 与遥控绑定联动逻辑。 */
class PlayerUiControllerTest {

    @Test
    fun overlaysAreMutuallyExclusiveAndClearCaptureAndDigits() {
        var now = 1_000L
        val c = PlayerUiController(clockMs = { now })

        c.appendDigit(1)
        c.appendDigit(2)
        assertTrue(c.currentDigits.visible)

        c.openSettings()
        assertFalse("打开设置必须清除数字 OSD", c.currentDigits.visible)
        assertTrue(c.isSettingsOpen)
        assertTrue(c.isBlockingOverlayOpen)

        c.startCapture(RemoteAction.OPEN_DRAWER, longPress = true)
        assertEquals(RemoteAction.OPEN_DRAWER to true, c.currentCapture)

        c.openDrawer()
        assertTrue(c.isDrawerOpen)
        assertFalse(c.isSettingsOpen)
        assertNull("切离设置必须自动取消按键捕获态", c.currentCapture)
    }

    @Test
    fun updateDialogBlocksPlaybackAndConsumesBackFirst() {
        val c = PlayerUiController()
        c.openUpdateDialog()
        assertTrue(c.isBlockingOverlayOpen)
        assertEquals(
            BackKeyPolicy.Action.CLOSE_UPDATE_DIALOG,
            c.decideBack(catchup = false, lastBackWithin2s = true),
        )
        c.closeUpdateDialog()
        assertEquals(PlayerOverlay.None, c.currentOverlay)
    }

    @Test
    fun settingsBackDecideIsPureAndDoesNotMutateBeforeExecution() {
        val c = PlayerUiController()
        c.openSettings()
        c.openSettingsPage(SettingsPage.PLAYBACK)

        // 只读裁决返回退回一级，但不提前修改状态
        assertEquals(
            BackKeyPolicy.Action.SETTINGS_BACK_TO_ROOT,
            c.decideBack(catchup = false, lastBackWithin2s = false),
        )
        assertEquals(SettingsPage.PLAYBACK, (c.currentOverlay as PlayerOverlay.Settings).page)

        // 执行退回一级后再裁决，返回关闭设置
        assertTrue(c.settingsBackToRoot())
        assertNull((c.currentOverlay as PlayerOverlay.Settings).page)
        assertEquals(
            BackKeyPolicy.Action.CLOSE_SETTINGS,
            c.decideBack(catchup = false, lastBackWithin2s = false),
        )
    }

    @Test
    fun drawerInteractionRefreshesAutoCloseTimestampWithoutReemittingOverlay() {
        var now = 10_000L
        val c = PlayerUiController(clockMs = { now })
        c.openDrawer()
        assertEquals(PlayerOverlay.Drawer, c.currentOverlay)
        assertEquals(10_000L, c.currentDrawerInteractionMs)

        now = 15_000L
        c.noteDrawerInteraction()
        assertEquals(PlayerOverlay.Drawer, c.currentOverlay)
        assertEquals(15_000L, c.currentDrawerInteractionMs)
        assertFalse(PerfPolicy.shouldAutoCloseDrawer(true, c.currentDrawerInteractionMs, 19_000L))
        assertTrue(PerfPolicy.shouldAutoCloseDrawer(true, c.currentDrawerInteractionMs, 22_000L))
    }

    @Test
    fun digitConfirmKeepsDigitsVisibleUntilCleared() {
        val c = PlayerUiController()
        c.appendDigit(1)
        c.appendDigit(1)
        c.appendDigit(2)
        c.markDigitsConfirmed()
        assertTrue(c.currentDigits.visible)
        assertTrue(c.currentDigits.confirmed)
        assertEquals("112", c.currentDigits.digits)
        c.clearDigits()
        assertFalse(c.currentDigits.visible)
    }

    @Test
    fun quickRemoteHabitsSyncDirectlyIntoBindings() {
        val b0 = RemoteBindings.DEFAULT
        assertEquals(RemoteHabits.UpDown.CHANNEL_STEP, RemoteBindings.deriveUpDownHabit(b0))
        assertEquals(RemoteHabits.Ok.CONFIRM, RemoteBindings.deriveOkHabit(b0))
        assertTrue(RemoteBindings.deriveChannelKeysEnabled(b0))

        // 切换上下键为呼出频道列表 → resolveWith 立即返回 OPEN_DRAWER
        val b1 = RemoteBindings.applyUpDownHabit(b0, RemoteHabits.UpDown.OPEN_DRAWER, true)
        assertEquals(RemoteHabits.UpDown.OPEN_DRAWER, RemoteBindings.deriveUpDownHabit(b1))
        assertEquals(
            RemoteAction.OPEN_DRAWER,
            RemoteBindings.resolveWith(b1, RemoteBindings.KEY_DPAD_UP, false, UiContext.PLAYER_ONLY),
        )

        // 切换 OK 为打开频道列表 → resolveWith 立即返回 OPEN_DRAWER
        val b2 = RemoteBindings.applyOkHabit(b1, RemoteHabits.Ok.OPEN_DRAWER)
        assertEquals(RemoteHabits.Ok.OPEN_DRAWER, RemoteBindings.deriveOkHabit(b2))
        assertEquals(
            RemoteAction.OPEN_DRAWER,
            RemoteBindings.resolveWith(b2, RemoteBindings.KEY_DPAD_CENTER, false, UiContext.PLAYER_ONLY),
        )

        // 禁用频道键 166/167 → resolveWith 返回 null
        val b3 = RemoteBindings.applyChannelKeysHabit(b0, false)
        assertFalse(RemoteBindings.deriveChannelKeysEnabled(b3))
        assertNull(RemoteBindings.resolveWith(b3, RemoteBindings.KEY_CHANNEL_UP, false, UiContext.PLAYER_ONLY))
    }

    @Test
    fun longPressBindingDetectionAllowsZeroLatencyDefaultKeys() {
        val b0 = RemoteBindings.DEFAULT
        assertFalse(RemoteBindings.hasLongPressBinding(b0, RemoteBindings.KEY_DPAD_UP, UiContext.PLAYER_ONLY))
        assertFalse(RemoteBindings.hasLongPressBinding(b0, RemoteBindings.KEY_DPAD_CENTER, UiContext.PLAYER_ONLY))

        val bLong = RemoteBindings.bind(b0, RemoteAction.OPEN_SETTINGS, RemoteBindings.KEY_DPAD_CENTER, longPress = true)
        assertTrue(RemoteBindings.hasLongPressBinding(bLong, RemoteBindings.KEY_DPAD_CENTER, UiContext.PLAYER_ONLY))
        assertEquals(
            RemoteAction.OPEN_SETTINGS,
            RemoteBindings.resolveWith(bLong, RemoteBindings.KEY_DPAD_CENTER, true, UiContext.PLAYER_ONLY),
        )
        assertEquals(
            RemoteAction.SHOW_INFO,
            RemoteBindings.resolveWith(bLong, RemoteBindings.KEY_DPAD_CENTER, false, UiContext.PLAYER_ONLY),
        )
    }

    @Test
    fun boundedCenterOffsetEliminatesTopAndBottomBlankSpaceWhileCenteringMiddle() {
        val viewportH = 980
        val rowH = 96
        val count = 147
        val idealY = (viewportH - rowH) / 2 // 442

        // 顶部前几台紧贴顶部，绝不留上方一片空白
        assertEquals(0, NativeChannelDrawerView.boundedCenterOffset(0, count, rowH, viewportH))
        assertEquals(96, NativeChannelDrawerView.boundedCenterOffset(1, count, rowH, viewportH))
        assertEquals(192, NativeChannelDrawerView.boundedCenterOffset(2, count, rowH, viewportH))

        // 中段频道精确居中
        assertEquals(idealY, NativeChannelDrawerView.boundedCenterOffset(21, count, rowH, viewportH))
        assertEquals(idealY, NativeChannelDrawerView.boundedCenterOffset(70, count, rowH, viewportH))

        // 末台紧贴视口底部，绝不留下方一片空白
        assertEquals(viewportH - rowH, NativeChannelDrawerView.boundedCenterOffset(146, count, rowH, viewportH))
    }
}
