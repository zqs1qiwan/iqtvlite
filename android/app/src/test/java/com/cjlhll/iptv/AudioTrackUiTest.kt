package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** t175 (F4): audio track label formatting, auto/manual selection state, no-audio degradation. */
class AudioTrackUiTest {

    private fun raw(
        group: Int = 0,
        track: Int = 0,
        language: String? = null,
        label: String? = null,
        mime: String? = "audio/mpeg-L2",
        codecs: String? = null,
        channels: Int = 2,
    ) = AudioTrackUi.Raw(group, track, language, label, mime, codecs, channels)

    @Test
    fun codecNamesAreShortened() {
        assertEquals("mpeg-L2", AudioTrackUi.formatCodec("audio/mpeg-L2", null))
        assertEquals("mpeg-L2", AudioTrackUi.formatCodec(null, "mpeg-L2"))
        assertEquals("aac", AudioTrackUi.formatCodec("audio/mp4a-latm", null))
        assertEquals("aac", AudioTrackUi.formatCodec(null, "mp4a.40.2"))
        assertEquals("ac-3", AudioTrackUi.formatCodec("audio/ac3", null))
        assertEquals("eac3", AudioTrackUi.formatCodec("audio/eac3", null))
        assertNull(AudioTrackUi.formatCodec(null, null))
        assertNull(AudioTrackUi.formatCodec("", "  "))
    }

    @Test
    fun channelAndLanguageFormatting() {
        assertEquals("2ch", AudioTrackUi.formatChannels(2))
        assertEquals("6ch", AudioTrackUi.formatChannels(6))
        assertNull(AudioTrackUi.formatChannels(0))
        assertEquals("zh", AudioTrackUi.formatLanguage("zh"))
        assertNull("und carries no information", AudioTrackUi.formatLanguage("und"))
        assertNull(AudioTrackUi.formatLanguage(null))
    }

    @Test
    fun trackLabelCombinesTheAvailableFields() {
        val label = AudioTrackUi.trackLabel(raw(language = "zh", channels = 2), 1)
        assertTrue(label.startsWith("音轨 1"))
        assertTrue(label.contains("zh"))
        assertTrue(label.contains("mpeg-L2"))
        assertTrue(label.contains("2ch"))

        // unknown fields are dropped, never rendered as "null"
        val minimal = AudioTrackUi.trackLabel(raw(language = null, channels = 0), 2)
        assertFalse(minimal.contains("null"))
        assertEquals("音轨 2 · mpeg-L2", minimal)
    }

    @Test
    fun autoIsTheDefaultSelectionAndIsListedFirst() {
        val raws = listOf(raw(), raw(track = 1, language = "en"))
        val items = AudioTrackUi.buildItems(raws)

        assertEquals(3, items.size)
        assertTrue(items[0].isAuto)
        assertTrue("auto is selected while no override is set", items[0].selected)
        assertFalse(items[1].selected)
        assertFalse(items[2].selected)
    }

    @Test
    fun manualSelectionMarksOnlyThatTrack() {
        val raws = listOf(raw(group = 1, track = 0), raw(group = 1, track = 1))
        val items = AudioTrackUi.buildItems(raws, overrideGroup = 1, overrideTrack = 1)

        assertFalse("auto is no longer selected", items[0].selected)
        assertTrue(items[1].selected.not())
        assertTrue("the chosen track is marked", items[2].selected)
        assertEquals(1, items[2].groupIndex)
        assertEquals(1, items[2].trackIndex)
    }

    @Test
    fun summaryShowsTheCurrentTrackOrNothing() {
        assertNull("no audio → nothing to show (no placeholder)", AudioTrackUi.summary(emptyList()))

        val raws = listOf(raw(language = "zh", channels = 2), raw(track = 1, language = "en"))
        val autoSummary = AudioTrackUi.summary(raws)
        assertTrue(autoSummary!!.contains("zh"))
        assertTrue("multi-track channels mention the count", autoSummary.contains("2 条音轨"))

        val manual = AudioTrackUi.summary(raws, overrideGroup = 0, overrideTrack = 1)
        assertTrue(manual!!.contains("en"))
    }

    @Test
    fun singleTrackHidesTheSelectionEntry() {
        val single = listOf(raw())
        assertFalse("a single track cannot be switched", AudioTrackUi.canSelect(single))
        assertEquals("音轨选择（仅 1 条）", AudioTrackUi.entryLabel(single))
        assertTrue(AudioTrackUi.canSelect(listOf(raw(), raw(track = 1))))
        assertEquals("音轨选择（2 条）", AudioTrackUi.entryLabel(listOf(raw(), raw(track = 1))))
        assertEquals("音轨选择（无音轨）", AudioTrackUi.entryLabel(emptyList()))
    }

    @Test
    fun emptyListDegradesGracefully() {
        assertNull(AudioTrackUi.summary(emptyList()))
        assertFalse(AudioTrackUi.canSelect(emptyList()))
        val items = AudioTrackUi.buildItems(emptyList())
        assertEquals("only the auto row is offered", 1, items.size)
        assertTrue(items[0].isAuto)
        assertTrue(items[0].selected)
    }
}
