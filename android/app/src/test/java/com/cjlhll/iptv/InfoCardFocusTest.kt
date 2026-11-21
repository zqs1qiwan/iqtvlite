package com.cjlhll.iptv

import com.cjlhll.iptv.InfoCardPolicy.FocusRecovery
import com.cjlhll.iptv.InfoCardPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t16（docs/INFO_CARD_FOCUS_FIX.md）：修 t8 复审的 F1（high）/ F2（medium）焦点缺陷。
 *
 * 这里断言的是 **Compose 层实际使用的同一份纯逻辑**（`InfoCardPolicy.focusRecoveryAction` /
 * `aggregateNodeFocus` / `shouldClearFocusOnTransition`），所以策略一旦被改回旧行为，单测先红。
 * 真机的按键行为由 acceptance(t9) 按文档 §4 复验。
 */
class InfoCardFocusTest {

    // ── F1：FOCUSED 但最近观看为 0 → 必须自愈退回 IDLE ────────────────────────
    @Test
    fun focusedWithNoRecentItemsSelfHealsToIdle() {
        assertEquals(
            FocusRecovery.EXIT_FOCUS,
            InfoCardPolicy.focusRecoveryAction(State.FOCUSED, recentCount = 0),
        )
        // 负 count 也只能退聚焦，绝不能留在 FOCUSED。
        assertEquals(FocusRecovery.EXIT_FOCUS, InfoCardPolicy.focusRecoveryAction(State.FOCUSED, -3))
    }

    @Test
    fun focusedWithRecentItemsRequestsOrRetriesNodeFocus() {
        assertEquals(FocusRecovery.REQUEST_FOCUS, InfoCardPolicy.focusRecoveryAction(State.FOCUSED, 1))
        assertEquals(FocusRecovery.REQUEST_FOCUS, InfoCardPolicy.focusRecoveryAction(State.FOCUSED, 6))
    }

    @Test
    fun onlyFocusedStateTriggersRecovery() {
        for (count in listOf(0, 1, 6)) {
            assertEquals(FocusRecovery.NONE, InfoCardPolicy.focusRecoveryAction(State.IDLE, count))
            assertEquals(FocusRecovery.NONE, InfoCardPolicy.focusRecoveryAction(State.HIDDEN, count))
        }
    }

    // ── F1：镜像必须来自“条内真有节点聚焦”，而不是 state ─────────────────────
    @Test
    fun nodeFocusMirrorIsTrueOnlyWhenSomeCardHoldsFocus() {
        assertFalse("没有任何小卡聚焦 → 镜像必须 false（Activity 不再吞键）", InfoCardPolicy.aggregateNodeFocus(-1))
        assertTrue(InfoCardPolicy.aggregateNodeFocus(0))
        assertTrue(InfoCardPolicy.aggregateNodeFocus(5))
    }

    /**
     * 回归剧本（F1）：FOCUSED + 后台刷新把最近观看清空。
     * 期望：state 立刻退回 IDLE（=EXIT_FOCUS），镜像 aggregateNodeFocus(-1)=false，
     * 于是 Activity 的 `isInfoCardFocused && infoCardKey` 守卫不再成立 —— BACK 不会被吞，
     * 也就不会落到系统 finish()。
     */
    @Test
    fun backCannotExitPlayerWhenFocusWasLostByABackgroundRefresh() {
        val recovery = InfoCardPolicy.focusRecoveryAction(State.FOCUSED, recentCount = 0)
        assertEquals(FocusRecovery.EXIT_FOCUS, recovery)
        // 自愈后的镜像：
        assertFalse(InfoCardPolicy.aggregateNodeFocus(-1))
    }

    // ── F2：只有用户主动退聚焦（FOCUSED→IDLE）才 clearFocus ─────────────────
    @Test
    fun clearFocusOnlyOnUserInitiatedUnfocus() {
        assertTrue(
            "FOCUSED→IDLE（BACK/UP/DOWN 主动退聚焦）必须清焦点",
            InfoCardPolicy.shouldClearFocusOnTransition(State.FOCUSED, State.IDLE),
        )
    }

    @Test
    fun openingAnOverlayMustNotClearTheDrawerFocus() {
        // F2 核心：MENU/方向键打开设置抽屉 → state FOCUSED→HIDDEN。
        // 绝不能在 HIDDEN 时 clearFocus，否则会把设置抽屉刚拿到的焦点清掉（DOWN 不动）。
        assertFalse(InfoCardPolicy.shouldClearFocusOnTransition(State.FOCUSED, State.HIDDEN))
        assertFalse(InfoCardPolicy.shouldClearFocusOnTransition(State.IDLE, State.HIDDEN))
        assertFalse(InfoCardPolicy.shouldClearFocusOnTransition(State.HIDDEN, State.HIDDEN))
        assertFalse(InfoCardPolicy.shouldClearFocusOnTransition(State.IDLE, State.IDLE))
        assertFalse(InfoCardPolicy.shouldClearFocusOnTransition(State.HIDDEN, State.IDLE))
        assertFalse(InfoCardPolicy.shouldClearFocusOnTransition(State.HIDDEN, State.FOCUSED))
    }
}
