package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t231: 睛彩 experiments must be strictly single-variable and default OFF. */
class JingcaiExperimentTest {

    @Test
    fun theDefaultIsOffAndUnknownValuesFallBackToOff() {
        assertEquals(JingcaiExperiment.Mode.OFF, JingcaiExperiment.parse(null))
        assertEquals(JingcaiExperiment.Mode.OFF, JingcaiExperiment.parse(""))
        assertEquals(JingcaiExperiment.Mode.OFF, JingcaiExperiment.parse("nonsense"))
        assertEquals(JingcaiExperiment.Mode.OFF, JingcaiExperiment.parse("off"))
    }

    @Test
    fun idsRoundTripAndCycleInOrder() {
        JingcaiExperiment.Mode.entries.forEach { m ->
            assertEquals(m, JingcaiExperiment.parse(m.id))
        }
        var m = JingcaiExperiment.Mode.OFF
        m = JingcaiExperiment.next(m)
        assertEquals(JingcaiExperiment.Mode.E1, m)
        m = JingcaiExperiment.next(m)
        assertEquals(JingcaiExperiment.Mode.E2, m)
        m = JingcaiExperiment.next(m)
        assertEquals(JingcaiExperiment.Mode.E3, m)
        m = JingcaiExperiment.next(m)
        assertEquals("cycle wraps back to OFF", JingcaiExperiment.Mode.OFF, m)
    }

    @Test
    fun onlyE1WidensTheTimestampSearch() {
        assertEquals(1_000_000, JingcaiExperiment.tsSearchBytes(JingcaiExperiment.Mode.OFF))
        assertEquals(4_000_000, JingcaiExperiment.tsSearchBytes(JingcaiExperiment.Mode.E1))
        assertEquals(1_000_000, JingcaiExperiment.tsSearchBytes(JingcaiExperiment.Mode.E2))
        assertEquals(1_000_000, JingcaiExperiment.tsSearchBytes(JingcaiExperiment.Mode.E3))
    }

    @Test
    fun onlyE2SwitchesTheHttpDataSource() {
        assertFalse(JingcaiExperiment.usePlatformHttpDataSource(JingcaiExperiment.Mode.OFF))
        assertFalse(JingcaiExperiment.usePlatformHttpDataSource(JingcaiExperiment.Mode.E1))
        assertTrue(JingcaiExperiment.usePlatformHttpDataSource(JingcaiExperiment.Mode.E2))
        assertFalse(JingcaiExperiment.usePlatformHttpDataSource(JingcaiExperiment.Mode.E3))
    }

    @Test
    fun onlyE3DropsTheCaptionInjection() {
        assertTrue(JingcaiExperiment.injectCaptions(JingcaiExperiment.Mode.OFF))
        assertTrue(JingcaiExperiment.injectCaptions(JingcaiExperiment.Mode.E1))
        assertTrue(JingcaiExperiment.injectCaptions(JingcaiExperiment.Mode.E2))
        assertFalse(JingcaiExperiment.injectCaptions(JingcaiExperiment.Mode.E3))
    }

    @Test
    fun everyExperimentTouchesExactlyOneKnob() {
        val modes = JingcaiExperiment.Mode.entries.filter { it != JingcaiExperiment.Mode.OFF }
        modes.forEach { m ->
            val knobs = listOf(
                JingcaiExperiment.tsSearchBytes(m) != JingcaiExperiment.TS_SEARCH_BYTES_DEFAULT,
                JingcaiExperiment.usePlatformHttpDataSource(m),
                !JingcaiExperiment.injectCaptions(m),
            ).count { it }
            assertEquals("${m.id} must change exactly one knob", 1, knobs)
        }
    }

    @Test
    fun theDiagnosticLineIsStable() {
        assertEquals(
            "mode=off bytes=0 format=none firstFrame=false state=2",
            JingcaiExperiment.diagnosticLine(JingcaiExperiment.Mode.OFF, 0L, null, false, 2)
        )
        assertEquals(
            "mode=e1 bytes=4096 format=video/avc/1920x1080@25.0 firstFrame=true state=3",
            JingcaiExperiment.diagnosticLine(
                JingcaiExperiment.Mode.E1, 4096L, "video/avc/1920x1080@25.0", true, 3
            )
        )
    }
}
