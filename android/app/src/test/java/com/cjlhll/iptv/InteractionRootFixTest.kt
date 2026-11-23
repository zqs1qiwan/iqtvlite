package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t52 第二批根治的纯逻辑用例（滚动均匀 / 7s / 设置入口 / 卡片高度）。 */
class InteractionRootFixTest {

    // ── §④ 滚动均匀 ─────────────────────────────────────────────────────────
    @Test
    fun scrollLookaheadWithinOneScreenAndEdgeAware() {
        assertEquals(8, PerfPolicy.DRAWER_LOOKAHEAD_ROWS) // 8×64=512 < 一屏 672
        assertEquals(2, PerfPolicy.DRAWER_SCROLL_EDGE_ROWS)
        // 焦点在可视区中段（离两端 >2）→ CLEAR 不滚。
        assertEquals(
            PerfPolicy.ScrollDecision.CLEAR,
            PerfPolicy.drawerScrollDecision(105, 147, 147, 100, 111),
        )
        // 焦点贴下边缘（111，距 last 0）→ SCROLL 提前平滑滚。
        assertEquals(
            PerfPolicy.ScrollDecision.SCROLL,
            PerfPolicy.drawerScrollDecision(111, 147, 147, 100, 111),
        )
        // 贴上边缘（101）→ SCROLL。
        assertEquals(
            PerfPolicy.ScrollDecision.SCROLL,
            PerfPolicy.drawerScrollDecision(101, 147, 147, 100, 111),
        )
    }

    // ── §⑥ 卡片高度 ─────────────────────────────────────────────────────────
    @Test
    fun compactCardHeightWellUnderTarget() {
        val withRecent = InfoCardPolicy.infoCardHeightV4(showRecent = true)
        val without = InfoCardPolicy.infoCardHeightV4(showRecent = false)
        assertTrue("紧凑卡片高度 $withRecent 应 ≤140dp", withRecent <= 140)
        assertTrue(withRecent >= 96) // 至少主行
        assertEquals(withRecent, without) // 最近条内联，不增高
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
        // 彩键默认 OPEN_SETTINGS
        assertEquals(
            RemoteAction.OPEN_SETTINGS,
            RemoteBindings.resolveWith(RemoteBindings.DEFAULT, RemoteBindings.KEY_BUTTON_1, false, UiContext.PLAYER_ONLY),
        )
    }

    private fun assertNullSafe(v: Any?) = org.junit.Assert.assertNull(v)
}
