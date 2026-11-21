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
    fun autoHideIsThreeSecondsOnlyWhenIdle() {
        assertEquals(3000L, InfoCardPolicy.AUTO_HIDE_MS)
        assertEquals(3000L, InfoCardPolicy.autoHideMs(InfoCardPolicy.State.IDLE))
        assertNull("聚焦时不得自动隐藏", InfoCardPolicy.autoHideMs(InfoCardPolicy.State.FOCUSED))
        assertNull(InfoCardPolicy.autoHideMs(InfoCardPolicy.State.HIDDEN))
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
        assertEquals(6, InfoCardPolicy.MAX_RECENT)
    }

    @Test
    fun cardRecentItemsHandleEmptyAndAllCurrent() {
        assertTrue(InfoCardPolicy.cardRecentItems(emptyList<String>()) { false }.isEmpty())
        assertTrue("only the current channel → nothing to jump to", InfoCardPolicy.cardRecentItems(listOf("a")) { true }.isEmpty())
        assertTrue(InfoCardPolicy.cardRecentItems(listOf("a", "b"), limit = 0) { false }.isEmpty())
    }

    @Test
    fun techLineOmitsEmptyParts() {
        assertEquals("", InfoCardPolicy.techLine(null, 0, 0, -1))
        assertEquals("1920x1080", InfoCardPolicy.techLine(null, 1920, 1080, -1))
        assertEquals("1920x1080 · 3000 kbps", InfoCardPolicy.techLine(null, 1920, 1080, 3_000_000))
        assertEquals(
            "音轨 mpeg-L2 · 2ch · 1920x1080 · 3000 kbps",
            InfoCardPolicy.techLine("mpeg-L2 · 2ch", 1920, 1080, 3_000_000, channelCount = 2, sampleMimeType = "audio/mpeg-L2")
        )
    }

    @Test
    fun techLineFallsBackToCodecAndChannelsWhenNoAudioLabel() {
        assertEquals(
            "avc · 1920x1080",
            InfoCardPolicy.techLine(null, 1920, 1080, -1, channelCount = -1, sampleMimeType = "video/avc")
        )
        assertEquals(
            "avc · 2ch",
            InfoCardPolicy.techLine(null, 0, 0, -1, channelCount = 2, sampleMimeType = "video/avc")
        )
    }

    @Test
    fun entryHintMentionsBothEntries() {
        assertTrue(InfoCardPolicy.ENTRY_HINT.contains("频道列表"))
        assertTrue(InfoCardPolicy.ENTRY_HINT.contains("设置"))
        assertTrue(InfoCardPolicy.ENTRY_HINT.contains("←"))
        assertTrue(InfoCardPolicy.ENTRY_HINT.contains("→"))
    }
}
