package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t167: the semantics/testTag contract used by uiautomator and the clear log line. */
class RecentDialogTagsTest {

    @Test
    fun stableTagsAreExposed() {
        assertEquals("recent_dialog", RecentDialogTags.DIALOG)
        assertEquals("recent_list", RecentDialogTags.LIST)
        assertEquals("recent_empty", RecentDialogTags.EMPTY)
        assertEquals("recent_clear", RecentDialogTags.CLEAR)
        assertEquals("recent_close", RecentDialogTags.CLOSE)
        assertEquals("settings_recent_entry", RecentDialogTags.SETTINGS_ENTRY)
    }

    @Test
    fun rowsUseAnIndexedTag() {
        assertEquals("recent_row_0", RecentDialogTags.row(0))
        assertEquals("recent_row_7", RecentDialogTags.row(7))
        // index must be part of the id, otherwise uiautomator cannot target a single row
        assertTrue(RecentDialogTags.row(3) != RecentDialogTags.row(4))
    }

    @Test
    fun clearedLogLineMatchesTheAcceptanceGrep() {
        assertEquals("cleared=true count=0", RecentDialogTags.clearedLog(0))
        assertEquals("cleared=true count=3", RecentDialogTags.clearedLog(3))
        assertTrue(RecentDialogTags.clearedLog(0).contains("cleared=true"))
    }

    @Test
    fun emptyStateIsVisibleOnlyForAnEmptyList() {
        assertTrue(RecentDialogTags.isEmptyStateVisible(0))
        assertFalse(RecentDialogTags.isEmptyStateVisible(1))
        assertFalse(RecentDialogTags.isEmptyStateVisible(RecentChannels.MAX))
    }

    @Test
    fun tagsDoNotCollide() {
        val tags = listOf(
            RecentDialogTags.DIALOG, RecentDialogTags.LIST, RecentDialogTags.EMPTY,
            RecentDialogTags.CLEAR, RecentDialogTags.CLOSE, RecentDialogTags.SETTINGS_ENTRY,
            RecentDialogTags.row(0), RecentDialogTags.row(1),
        )
        assertEquals("all test tags must be unique", tags.size, tags.toSet().size)
    }
}
