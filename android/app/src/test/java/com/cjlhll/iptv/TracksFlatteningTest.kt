package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t187 (TRACKS-01): the flattening rules on plain candidates.
 *
 * The Media3 adapter cannot be exercised in a JVM test because Media3's `TrackGroup` calls
 * `android.text.TextUtils` (not mocked), so the *logic* lives in `select()` and is tested here; the
 * adapter only maps `Tracks.Group` fields onto `Candidate`.
 */
class TracksFlatteningTest {

    private fun c(
        group: Int = 0,
        track: Int = 0,
        language: String? = "zh",
        mime: String? = "audio/mp4a-latm",
        codecs: String? = "mp4a.40.2",
        channels: Int = 2,
        supported: Boolean = true,
        selected: Boolean = false,
    ) = TracksFlattening.Candidate(group, track, language, null, mime, codecs, channels, supported, selected)

    @Test
    fun onlySupportedTracksAreListedAndTheSelectedOneIsMarked() {
        val rows = TracksFlattening.select(
            listOf(
                c(track = 0, language = "zh", supported = false),
                c(track = 1, language = "en", selected = true),
            )
        )
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].trackIndex)
        assertEquals("en", rows[0].language)
        assertTrue(rows[0].selected)
    }

    @Test
    fun unsupportedOnlyProducesNoRows() {
        assertTrue(TracksFlattening.select(listOf(c(supported = false))).isEmpty())
        assertTrue(TracksFlattening.select(emptyList()).isEmpty())
    }

    @Test
    fun theSameStreamReportedByTwoRenderersYieldsOneRow() {
        // renderer copies: different groups, identical identity, one of them selected
        val rows = TracksFlattening.select(
            listOf(
                c(group = 0, track = 0, selected = true),
                c(group = 1, track = 0, selected = false),
            )
        )
        assertEquals("the duplicate stream collapses to one row", 1, rows.size)
        assertTrue(rows[0].selected)
        assertEquals(0, rows[0].groupIndex)
    }

    @Test
    fun twoRealTracksInOneGroupBothSurvive() {
        val rows = TracksFlattening.select(
            listOf(
                c(group = 0, track = 0, language = "zh", selected = true),
                c(group = 0, track = 1, language = "en"),
            )
        )
        assertEquals(2, rows.size)
        assertEquals("zh", rows[0].language)
        assertEquals("en", rows[1].language)
    }

    @Test
    fun aGenuinelyDifferentStreamInAnotherGroupIsKept() {
        val rows = TracksFlattening.select(
            listOf(
                c(group = 0, track = 0, language = "zh", channels = 2),
                c(group = 1, track = 0, language = "en", channels = 6),
            )
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun emptyAndSingleTrackChannelsReportTheRightSelectability() {
        val one = TracksFlattening.select(listOf(c(selected = true)))
        assertEquals(1, one.size)
        assertFalse("a single track cannot be switched", AudioTrackUi.canSelect(one))

        val two = TracksFlattening.select(listOf(c(track = 0), c(track = 1, language = "en")))
        assertTrue(AudioTrackUi.canSelect(two))
    }
}
