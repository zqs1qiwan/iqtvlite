package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** JVM coverage of the TIV sidebar semantics extracted into [SidebarController] (t61). */
class SidebarControllerTest {

    private fun channel(title: String, url: String) = Channel(title = title, url = url, id = url.hashCode().toLong())

    private fun group(name: String, vararg urls: String) = ChannelGroup(
        key = name,
        displayTitle = name,
        logoUrl = null,
        group = name,
        variants = urls.map { ChannelVariant(channel(it, it), "HD") },
        defaultVariantIndex = 0,
    )

    private fun controller() = SidebarController().apply {
        setChannelGroups(listOf(group("央视频道", "a1", "a2"), group("卫视", "b1"), group("地方", "c1")))
        setSelection(0, 0)
    }

    @Test
    fun firstOpenFocusesCurrentChannelAndShowsChannelColumn() {
        val c = controller()
        c.setSelection(1, 0)
        c.openDrawer()
        assertEquals("b1", c.value.focusedChannelUrl)
        assertTrue(c.value.drawerOpen)
        assertEquals(DrawerColumn.Channels, c.value.activeColumn)
    }

    @Test
    fun reopenKeepsValidBrowsingFocus() {
        val c = controller()
        c.openDrawer()
        c.focusChannel("a2")
        c.closeDrawer()
        c.openDrawer()
        assertEquals("a2", c.value.focusedChannelUrl)
    }

    @Test
    fun channelStepWrapsAtBothEnds() {
        val c = controller()
        c.setSelection(0, 0)
        assertEquals(2, c.stepChannel(-1)) // 0 - 1 wraps to last
        assertEquals("c1", c.value.selectedChannelUrl)
        assertEquals(0, c.stepChannel(1))  // last + 1 wraps to first
        assertEquals("a1", c.value.selectedChannelUrl)
    }

    @Test
    fun singleBackClosesWholeDrawerOnce() {
        val c = controller()
        c.openDrawer()
        assertTrue(c.onBack())
        assertFalse(c.value.drawerOpen)
        assertFalse(c.onBack())
    }

    @Test
    fun groupFilterKeepsDrawerOpenAndGlobalNumbering() {
        val c = controller()
        c.openDrawer()
        c.selectGroup("卫视")
        assertTrue(c.value.drawerOpen)
        assertEquals("卫视", c.value.selectedGroup)
        assertEquals(listOf("b1"), c.filteredChannels().map { it.url })
        // Global numbering is preserved across groups (a1=1, a2=1, b1=2, c1=3).
        assertEquals(2, c.channelNumber("b1"))
        assertEquals(1, c.channelNumber("a2"))
    }

    @Test
    fun dateFilterSetsDateAndKeepsDrawerOpen() {
        val c = controller()
        c.openDrawer()
        val d = LocalDate.of(2026, 11, 20)
        c.selectEpgDate(d)
        assertEquals(d, c.value.selectedEpgDate)
        assertTrue(c.value.drawerOpen)
    }

    @Test
    fun channelOkSwitchesAndKeepsDrawerOpen() {
        val c = controller()
        c.openDrawer()
        c.activateChannel(2, 0)
        assertEquals("c1", c.value.selectedChannelUrl)
        assertTrue(c.value.drawerOpen)
    }

    @Test
    fun setChannelGroupsReResolvesSelectionByUrl() {
        val c = controller()
        c.setSelection(1, 0)
        c.setChannelGroups(listOf(group("卫视", "b1")))
        assertEquals("b1", c.value.selectedChannelUrl)
        assertEquals(0, c.value.currentGroupIndex)
    }

    @Test
    fun focusedUrlFallsBackToSelected() {
        val c = controller()
        assertNull(c.value.focusedChannelUrl)
        assertEquals("a1", c.effectiveFocusedChannelUrl())
    }

    @Test
    fun emptyListStepIsNoOp() {
        val c = SidebarController()
        assertNull(c.stepChannel(1))
        assertNull(c.channelNumber("x"))
    }

    // ── FULLFIX §2：按组记忆上次浏览焦点（切组不再回开头） ──────────────────
    @Test
    fun lastFocusedUrlIsRememberedPerGroupAcrossSwitches() {
        val c = controller()
        c.openDrawer()
        c.selectGroup("央视频道")
        c.focusChannel("a2")
        c.selectGroup("卫视")
        c.focusChannel("b1")
        // 切回原组，按组记忆各自保留。
        assertEquals("a2", c.lastFocusedUrlFor("央视频道"))
        assertEquals("b1", c.lastFocusedUrlFor("卫视"))
        assertNull(c.lastFocusedUrlFor("地方"))
        // selectGroup 不清除记忆。
        c.selectGroup("地方")
        assertEquals("a2", c.lastFocusedUrlFor("央视频道"))
    }

    @Test
    fun reopenUsesRememberedBrowseFocusNotPlayingChannel() {
        val c = controller()
        c.setSelection(0, 0) // 播放 a1
        c.openDrawer()
        c.focusChannel("a2") // 浏览到 a2
        c.closeDrawer()
        assertEquals("a2", c.value.focusedChannelUrl)
        assertEquals("a2", c.lastFocusedUrlFor("央视频道"))
        // 再次打开仍保留浏览焦点 a2（不是播放台 a1）。
        c.openDrawer()
        assertEquals("a2", c.value.focusedChannelUrl)
    }
}
