package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t52 第二批根治的纯逻辑用例（滚动均匀 / 7s / 设置入口 / 卡片高度）。 */
class InteractionRootFixTest {

    // ── §④ 架构重构后由 firstVisibleForFocus 首帧定位（PerfPolicyTest.t91 覆盖） ──
    @Test
    fun drawerLookaheadWithinOneScreen() {
        assertEquals(8, PerfPolicy.DRAWER_LOOKAHEAD_ROWS) // 8×64=512 < 一屏 672
    }

    // ── §⑥ 卡片高度 ─────────────────────────────────────────────────────────
    @Test
    fun compactCardHeightWellUnderTarget() {
        val withRecent = InfoCardPolicy.infoCardHeightV4(showRecent = true)
        val without = InfoCardPolicy.infoCardHeightV4(showRecent = false)
        assertTrue("紧凑卡片高度 $withRecent 应 ≤140dp", withRecent <= 140)
        assertTrue("5 个 64dp 大 logo 内联后高于无最近条", withRecent > without)
        assertTrue(InfoCardPolicy.INFO_CARD_COMPACT_HEIGHT_DP <= 140)
    }

    // ── §⑦ 7s 静止自动收起 ──────────────────────────────────────────────────
    @Test
    fun focusedAutoHidesAfter7sOnlyWhenIdle() {
        assertEquals(7000L, InfoCardPolicy.autoHideMs(InfoCardPolicy.State.FOCUSED))
        assertEquals(7000L, InfoCardPolicy.autoHideMs(InfoCardPolicy.State.IDLE))
        assertNullSafe(InfoCardPolicy.autoHideMs(InfoCardPolicy.State.HIDDEN))
        val now = 20_000L
        assertTrue(
            InfoCardPolicy.shouldArmAutoHide(InfoCardPolicy.State.FOCUSED, now - 8_000, now),
        )
        assertFalse(
            InfoCardPolicy.shouldArmAutoHide(InfoCardPolicy.State.FOCUSED, now - 2_000, now),
        )
        assertFalse(
            "操作进行中不 arm",
            InfoCardPolicy.shouldArmAutoHide(InfoCardPolicy.State.FOCUSED, now - 9_000, now, interactionInFlight = true),
        )
    }

    // ── §⑧ 设置呼出 ─────────────────────────────────────────────────────────
    @Test
    fun focusedPassthroughOnlyForOpenSettingsNonDirectional() {
        assertTrue(FocusedKeyPolicy.passthrough(RemoteBindings.KEY_BUTTON_1, RemoteAction.OPEN_SETTINGS))
        assertTrue(FocusedKeyPolicy.passthrough(RemoteBindings.KEY_MENU, RemoteAction.OPEN_SETTINGS))
        assertFalse("方向键仍闭环", FocusedKeyPolicy.passthrough(22, RemoteAction.OPEN_SETTINGS))
        assertFalse("OK 仍闭环", FocusedKeyPolicy.passthrough(23, RemoteAction.SHOW_INFO))
        assertTrue("BACK 放行给 BackKeyPolicy 单一真源", FocusedKeyPolicy.passthrough(FocusedKeyPolicy.KEYCODE_BACK, null))
        assertFalse("彩键绑别的动作不放行", FocusedKeyPolicy.passthrough(RemoteBindings.KEY_BUTTON_1, RemoteAction.CHANNEL_DOWN))
    }

    @Test
    fun rightOpensSettingsInPlayerButDrawerInGroupColumn() {
        // RIGHT 在播放器（非分组列）→ OPEN_SETTINGS（默认可开设置，不再死锁）
        assertEquals(
            RemoteAction.OPEN_SETTINGS,
            RemoteBindings.resolveRight(RemoteAction.OPEN_SETTINGS, "全部", false, false),
        )
        // 分组列内 → 进频道列表（V4 功能保留）
        assertEquals(
            RemoteAction.OPEN_DRAWER,
            RemoteBindings.resolveRight(RemoteAction.OPEN_SETTINGS, "全部", true, false),
        )
        // 彩键默认未绑定（设置入口统一在 OK 菜单齿轮图标，不抢占遥控按键）
        assertNullSafe(
            RemoteBindings.resolveWith(RemoteBindings.DEFAULT, RemoteBindings.KEY_BUTTON_1, false, UiContext.PLAYER_ONLY),
        )
    }

    private fun assertNullSafe(v: Any?) = org.junit.Assert.assertNull(v)
}
