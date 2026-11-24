package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t4（docs/INFO_CARD_V2.md §7.2）：信息卡三态、自动隐藏、最近观看条可见性、焦点移动、外部换台、
 * 技术参数行。全部是纯逻辑，不需要 Compose/设备。
 */
class InfoCardPolicyTest {

    @Test
    fun autoHideIsSevenSecondsOnlyWhenIdle() {
        // t32 Bug3：主动 OK 与换台统一 7s 自动消失；聚焦不隐藏。
        assertEquals(7000L, InfoCardPolicy.AUTO_HIDE_MS)
        assertEquals(7000L, InfoCardPolicy.autoHideMs(InfoCardPolicy.State.IDLE))
        // t52 §⑦：FOCUSED 也 7s 静止自动收起（不再 null 常驻）
        assertEquals(7000L, InfoCardPolicy.autoHideMs(InfoCardPolicy.State.FOCUSED))
        assertNull(InfoCardPolicy.autoHideMs(InfoCardPolicy.State.HIDDEN))
    }

    @Test
    fun infoOkModeDefaultsToTwoStepRequiringDirectOptIn() {
        // 默认两段式（第一次 OK 展示信息卡，第二次 OK 才进入最近观看快切），可手动切 DIRECT。
        assertEquals(InfoCardPolicy.InfoOkMode.TWO_STEP, InfoCardPolicy.parseInfoOkMode(null))
        assertEquals(InfoCardPolicy.InfoOkMode.TWO_STEP, InfoCardPolicy.parseInfoOkMode("garbage"))
        assertEquals(InfoCardPolicy.InfoOkMode.DIRECT, InfoCardPolicy.parseInfoOkMode("DIRECT"))
        assertEquals(InfoCardPolicy.InfoOkMode.TWO_STEP, InfoCardPolicy.parseInfoOkMode("TWO_STEP"))
        assertTrue(InfoCardPolicy.openCardFocusesDirectly(InfoCardPolicy.InfoOkMode.DIRECT))
        assertFalse(InfoCardPolicy.openCardFocusesDirectly(InfoCardPolicy.InfoOkMode.TWO_STEP))
    }

    // ── t35：FOCUSED 态按键确定性闭环（绝不穿透到 OPEN_SETTINGS/换台） ──────
    @Test
    fun t35FocusedKeysMapToCardSemantics() {
        // RIGHT(22) → MOVE_RIGHT（选第二张卡，不打开设置）；LEFT(21) → MOVE_LEFT。
        assertEquals(
            InfoCardPolicy.FocusedKeyAction.MOVE_RIGHT,
            InfoCardPolicy.focusedKeyAction(22, isCenter = false),
        )
        assertEquals(
            InfoCardPolicy.FocusedKeyAction.MOVE_LEFT,
            InfoCardPolicy.focusedKeyAction(21, isCenter = false),
        )
        // OK/CENTER → SWITCH 切台。
        assertEquals(
            InfoCardPolicy.FocusedKeyAction.SWITCH,
            InfoCardPolicy.focusedKeyAction(23, isCenter = true),
        )
        // BACK(4)/UP(19)/DOWN(20) → 退聚焦。
        assertEquals(
            InfoCardPolicy.FocusedKeyAction.EXIT_FOCUS,
            InfoCardPolicy.focusedKeyAction(4, isCenter = false),
        )
        assertEquals(
            InfoCardPolicy.FocusedKeyAction.EXIT_FOCUS,
            InfoCardPolicy.focusedKeyAction(19, isCenter = false),
        )
        // 非信息键 → NONE（调用方据此不消费，但 FOCUSED 分支仍 return true 防穿透）。
        assertEquals(
            InfoCardPolicy.FocusedKeyAction.NONE,
            InfoCardPolicy.focusedKeyAction(82 /* MENU */, isCenter = false),
        )
    }

    @Test
    fun visibilityFollowsStateAndSeekOverlay() {
        assertTrue(InfoCardPolicy.isVisible(InfoCardPolicy.State.IDLE, seekOverlayVisible = false))
        assertTrue(InfoCardPolicy.isVisible(InfoCardPolicy.State.FOCUSED, seekOverlayVisible = false))
        assertFalse(InfoCardPolicy.isVisible(InfoCardPolicy.State.HIDDEN, seekOverlayVisible = false))
        assertTrue(
            "回看快进浮层出现时，卡片仍显示回看进度行",
            InfoCardPolicy.isVisible(InfoCardPolicy.State.HIDDEN, seekOverlayVisible = true)
        )
    }

    @Test
    fun focusRequiresANonEmptyRowAndLivePlayback() {
        assertFalse(InfoCardPolicy.canFocusRecent(recentCount = 0, catchup = false, seekOverlayVisible = false))
        assertFalse(InfoCardPolicy.canFocusRecent(recentCount = 3, catchup = true, seekOverlayVisible = false))
        assertFalse(InfoCardPolicy.canFocusRecent(recentCount = 3, catchup = false, seekOverlayVisible = true))
        assertTrue(InfoCardPolicy.canFocusRecent(recentCount = 3, catchup = false, seekOverlayVisible = false))
    }

    @Test
    fun recentRowVisibilityMatchesFocusRule() {
        val cases = listOf(
            Triple(0, false, false), Triple(6, false, false),
            Triple(3, true, false), Triple(3, false, true), Triple(0, true, true),
        )
        cases.forEach { (count, catchup, seek) ->
            assertEquals(
                "showRecentRow must agree with canFocusRecent ($count/$catchup/$seek)",
                InfoCardPolicy.canFocusRecent(count, catchup, seek),
                InfoCardPolicy.showRecentRow(count, catchup, seek),
            )
        }
    }

    @Test
    fun focusIndexClampsAndNeverWraps() {
        assertEquals("left at the head stays", 0, InfoCardPolicy.nextFocusIndex(0, 6, -1))
        assertEquals("right at the tail stays", 5, InfoCardPolicy.nextFocusIndex(5, 6, 1))
        assertEquals(3, InfoCardPolicy.nextFocusIndex(2, 6, 1))
        assertEquals(1, InfoCardPolicy.nextFocusIndex(2, 6, -1))
        assertEquals("no items → 0", 0, InfoCardPolicy.nextFocusIndex(0, 0, 1))
        assertEquals("single item", 0, InfoCardPolicy.nextFocusIndex(0, 1, 1))
        assertEquals("big delta still clamps", 5, InfoCardPolicy.nextFocusIndex(0, 6, 99))
    }

    @Test
    fun externalZapDropsFocusButKeepsHiddenHidden() {
        assertEquals(
            InfoCardPolicy.State.IDLE,
            InfoCardPolicy.onExternalZap(InfoCardPolicy.State.FOCUSED)
        )
        assertEquals(
            InfoCardPolicy.State.IDLE,
            InfoCardPolicy.onExternalZap(InfoCardPolicy.State.IDLE)
        )
        assertEquals(
            InfoCardPolicy.State.HIDDEN,
            InfoCardPolicy.onExternalZap(InfoCardPolicy.State.HIDDEN)
        )
    }

    @Test
    fun cardRecentItemsExcludeCurrentAndCapAtSix() {
        val items = (1..10).map { "ch$it" }
        val card = InfoCardPolicy.cardRecentItems(items) { it == "ch3" }
        assertEquals(InfoCardPolicy.MAX_RECENT, card.size)
        assertFalse("current channel must not appear", card.contains("ch3"))
        assertEquals("order is preserved (newest first)", "ch1", card.first())
        assertEquals(5, InfoCardPolicy.MAX_RECENT) // t69 ③：底部最近观看 5 个 logo
    }

    @Test
    fun t69FiveBigLogosStillUnderHeightCap() {
        assertTrue("卡片高度 ${InfoCardPolicy.infoCardHeightV4(true)} 应 ≤140dp",
            InfoCardPolicy.infoCardHeightV4(true) <= 140)
        assertTrue(InfoCardPolicy.INFO_CARD_COMPACT_HEIGHT_DP <= 140)
    }

    @Test
    fun cardRecentItemsHandleEmptyAndAllCurrent() {
        assertTrue(InfoCardPolicy.cardRecentItems(emptyList<String>()) { false }.isEmpty())
        assertTrue("only the current channel → nothing to jump to", InfoCardPolicy.cardRecentItems(listOf("a")) { true }.isEmpty())
        assertTrue(InfoCardPolicy.cardRecentItems(listOf("a", "b"), limit = 0) { false }.isEmpty())
    }

    @Test
    fun techLineJoinsCodecsAndDynamicRangeAndOmitsEmptyParts() {
        assertEquals("", InfoCardPolicy.techLine(null, null, null, emptyList()))
        assertEquals("H.264", InfoCardPolicy.techLine("H.264", null, null, emptyList()))
        assertEquals(
            "H.264  ·  MPEG-L2  ·  SDR",
            InfoCardPolicy.techLine("H.264", "MPEG-L2", "SDR", emptyList()),
        )
        // 溢出徽标追加到末尾，仍单行
        assertEquals(
            "H.265  ·  AAC  ·  SDR  ·  8.3Mbps",
            InfoCardPolicy.techLine("H.265", "AAC", "SDR", listOf("8.3Mbps")),
        )
    }

    @Test
    fun codecAndDynamicRangeLabelsMapKnownValuesAndHideUnknown() {
        assertEquals("H.264", InfoCardPolicy.videoCodecLabel("video/avc", "avc1.640028"))
        assertEquals("H.265", InfoCardPolicy.videoCodecLabel("video/hevc", null))
        assertEquals("AV1", InfoCardPolicy.videoCodecLabel("video/av01", null))
        assertNull(InfoCardPolicy.videoCodecLabel("video/mp4v-es", null))
        assertEquals("MPEG-L2", InfoCardPolicy.audioCodecLabel("mpeg-L2 · 2ch · 1920x1080"))
        assertEquals("AAC", InfoCardPolicy.audioCodecLabel("aac · 2ch"))
        assertEquals("AC-3", InfoCardPolicy.audioCodecLabel("ac-3 · 6ch"))
        assertNull(InfoCardPolicy.audioCodecLabel(null))
        assertEquals("SDR", InfoCardPolicy.dynamicRangeLabel(-1, false))
        assertEquals("HDR10", InfoCardPolicy.dynamicRangeLabel(6, true))
        assertEquals("HLG", InfoCardPolicy.dynamicRangeLabel(7, false))
    }

    @Test
    fun entryHintMentionsBothEntries() {
        assertTrue(InfoCardPolicy.ENTRY_HINT.contains("频道列表"))
        assertTrue(InfoCardPolicy.ENTRY_HINT.contains("设置"))
        assertTrue(InfoCardPolicy.ENTRY_HINT.contains("←"))
        assertTrue(InfoCardPolicy.ENTRY_HINT.contains("→"))
    }
}
