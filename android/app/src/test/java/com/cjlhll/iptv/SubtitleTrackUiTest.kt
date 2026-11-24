package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTrackUiTest {

    @Test
    fun returnsEmptyListWhenNoSubtitleTracksPresent() {
        assertTrue(SubtitleTrackUi.buildItems(emptyList(), disabled = false).isEmpty())
    }

    @Test
    fun buildsOffRowAndSubtitleTracksWithSelection() {
        val raws = listOf(
            SubtitleTrackUi.Raw(groupIndex = 2, trackIndex = 0, language = "zh", label = "简体中文", mime = "text/vtt", selected = true),
            SubtitleTrackUi.Raw(groupIndex = 2, trackIndex = 1, language = "en", label = "English", mime = "application/x-subrip", selected = false),
        )
        val activeItems = SubtitleTrackUi.buildItems(raws, disabled = false)
        assertEquals(3, activeItems.size)
        assertFalse(activeItems[0].selected)
        assertTrue(activeItems[1].selected)
        assertEquals("字幕 1 · zh · 简体中文 · vtt", activeItems[1].text)

        val disabledItems = SubtitleTrackUi.buildItems(raws, disabled = true)
        assertTrue(disabledItems[0].isOff)
        assertTrue(disabledItems[0].selected)
        assertFalse(disabledItems[1].selected)
    }
}
