package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t13（docs/SETTINGS_IA_V2.md §5.1/§8.3）：Back/Left 层级状态机。
 *
 * 硬性要求：二级 → 一级只退一层（焦点回到"进来的那一行"），一级 → 关抽屉只退一层；
 * 二级 RIGHT 必须被消费（否则会穿透到播放器的"右开设置"）；任何一次按键都不能一次跳两级。
 */
class SettingsBackStackTest {

    private val pageTags = SettingsIA.itemTags(SettingsPage.PLAYLISTS)

    @Test
    fun rootBackClosesTheDrawer() {
        val (state, consumed) = SettingsNavPolicy.onKey(SettingsNavPolicy.State.Root(0), SettingsNavPolicy.Key.BACK)
        assertEquals(SettingsNavPolicy.State.Closed, state)
        assertTrue(consumed)
    }

    @Test
    fun rootLeftAlsoClosesTheDrawer() {
        val (state, consumed) = SettingsNavPolicy.onKey(SettingsNavPolicy.State.Root(3), SettingsNavPolicy.Key.LEFT)
        assertEquals(SettingsNavPolicy.State.Closed, state)
        assertTrue(consumed)
    }

    @Test
    fun pageBackReturnsToTheEntryRowOnlyOneLevel() {
        val (state, consumed) = SettingsNavPolicy.onKey(
            SettingsNavPolicy.State.Page(SettingsPage.GENERAL, "settings_source_config"),
            SettingsNavPolicy.Key.BACK,
        )
        assertEquals(SettingsNavPolicy.State.Root(SettingsIA.ROOT_ORDER.indexOf(SettingsPage.GENERAL)), state)
        assertTrue(consumed)
        // 一次按键不得直接关抽屉（不跳级）。
        assertTrue(state !is SettingsNavPolicy.State.Closed)
    }

    @Test
    fun pageLeftIsEquivalentToBack() {
        val page = SettingsNavPolicy.State.Page(SettingsPage.APPEARANCE, "settings_logo_clear")
        val back = SettingsNavPolicy.onKey(page, SettingsNavPolicy.Key.BACK)
        val left = SettingsNavPolicy.onKey(page, SettingsNavPolicy.Key.LEFT)
        assertEquals(back, left)
    }

    @Test
    fun pageRightIsSwallowed() {
        val page = SettingsNavPolicy.State.Page(SettingsPage.REMOTE, "settings_remote_ok")
        val (state, consumed) = SettingsNavPolicy.onKey(page, SettingsNavPolicy.Key.RIGHT)
        assertEquals("RIGHT 不得改变层级（否则会穿透到播放器重开设置）", page, state)
        assertTrue("RIGHT 必须被消费", consumed)
    }

    @Test
    fun rootOkAndRightOpenTheFocusedPage() {
        listOf(SettingsNavPolicy.Key.OK, SettingsNavPolicy.Key.RIGHT).forEach { key ->
            val (state, consumed) = SettingsNavPolicy.onKey(SettingsNavPolicy.State.Root(3), key)
            assertTrue(consumed)
            val page = state as SettingsNavPolicy.State.Page
            assertEquals(SettingsPage.APPEARANCE, page.page)
            assertEquals("进入页面时焦点落该页第一行", SettingsIA.itemTags(SettingsPage.APPEARANCE).first(), page.focusTag)
        }
    }

    @Test
    fun twoConsecutiveBacksNeverSkipALevel() {
        // 二级 → 一级
        val (root, c1) = SettingsNavPolicy.onKey(
            SettingsNavPolicy.State.Page(SettingsPage.ABOUT, "settings_check_update"),
            SettingsNavPolicy.Key.BACK,
        )
        assertTrue(c1)
        assertTrue(root is SettingsNavPolicy.State.Root)
        // 一级 → 关抽屉
        val (closed, c2) = SettingsNavPolicy.onKey(root, SettingsNavPolicy.Key.BACK)
        assertTrue(c2)
        assertEquals(SettingsNavPolicy.State.Closed, closed)
        // 已关闭后再按 BACK 不再被消费（交回播放器的双 Back 退出习惯）
        val (stillClosed, c3) = SettingsNavPolicy.onKey(closed, SettingsNavPolicy.Key.BACK)
        assertEquals(SettingsNavPolicy.State.Closed, stillClosed)
        assertFalse("抽屉已关闭时 BACK 必须交回播放器", c3)
    }

    @Test
    fun focusIndexRoundTripsThroughEveryPage() {
        SettingsIA.ROOT_ORDER.forEachIndexed { index, page ->
            val (opened, _) = SettingsNavPolicy.onKey(SettingsNavPolicy.State.Root(index), SettingsNavPolicy.Key.OK)
            val openedPage = opened as SettingsNavPolicy.State.Page
            assertEquals(page, openedPage.page)
            val (back, _) = SettingsNavPolicy.onKey(openedPage, SettingsNavPolicy.Key.BACK)
            assertEquals("返回一级时焦点回到进来的那一行", SettingsNavPolicy.State.Root(index), back)
        }
    }

    @Test
    fun upDownDoNotChangeLevels() {
        val root = SettingsNavPolicy.State.Root(2)
        val page = SettingsNavPolicy.State.Page(SettingsPage.PLAYBACK, "settings_audio_track")
        listOf(SettingsNavPolicy.Key.UP, SettingsNavPolicy.Key.DOWN).forEach { key ->
            assertEquals("页内上下移动交给焦点系统", Pair(root, false), SettingsNavPolicy.onKey(root, key))
            assertEquals("页内上下移动交给焦点系统", Pair(page, false), SettingsNavPolicy.onKey(page, key))
        }
    }

    @Test
    fun outOfRangeRootFocusIsClampedInsteadOfCrashing() {
        val (state, _) = SettingsNavPolicy.onKey(SettingsNavPolicy.State.Root(99), SettingsNavPolicy.Key.OK)
        assertEquals(SettingsPage.ABOUT, (state as SettingsNavPolicy.State.Page).page)
        val (negative, _) = SettingsNavPolicy.onKey(SettingsNavPolicy.State.Root(-5), SettingsNavPolicy.Key.RIGHT)
        assertEquals(SettingsPage.GENERAL, (negative as SettingsNavPolicy.State.Page).page)
    }

    @Test
    fun pageTagsArgumentDoesNotBreakTheLevelRules() {
        val (state, consumed) = SettingsNavPolicy.onKey(
            SettingsNavPolicy.State.Page(SettingsPage.EPG, "settings_auto_epg"),
            SettingsNavPolicy.Key.RIGHT,
            pageTags,
        )
        assertTrue(consumed)
        assertTrue(state is SettingsNavPolicy.State.Page)
    }
}
