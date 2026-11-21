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
        val expected = listOf(
            JingcaiExperiment.Mode.E1,
            JingcaiExperiment.Mode.E2,
            JingcaiExperiment.Mode.E3,
            JingcaiExperiment.Mode.A,
            JingcaiExperiment.Mode.B,
            JingcaiExperiment.Mode.AUTO,
            JingcaiExperiment.Mode.C,
            JingcaiExperiment.Mode.OFF,
        )
        expected.forEach { want ->
            m = JingcaiExperiment.next(m)
            assertEquals(want, m)
        }
    }

    @Test
    fun timelineExperimentsAreTheOnlyPacketRewriters() {
        assertEquals(setOf(JingcaiExperiment.Mode.A, JingcaiExperiment.Mode.B, JingcaiExperiment.Mode.AUTO),
            JingcaiExperiment.Mode.entries.filter { JingcaiExperiment.touchesPacketBytes(it) }.toSet())
        assertTrue(JingcaiExperiment.timebaseRebase(JingcaiExperiment.Mode.C))
        assertFalse(JingcaiExperiment.touchesPacketBytes(JingcaiExperiment.Mode.C))
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
        // C 是“时基归零”的设计占位（不实现字节改写），单独在 timelineExperimentsAreTheOnlyPacketRewriters 校验。
        val modes = JingcaiExperiment.Mode.entries.filter {
            it != JingcaiExperiment.Mode.OFF && it != JingcaiExperiment.Mode.C
        }
        modes.forEach { m ->
            val knobs = listOf(
                JingcaiExperiment.tsSearchBytes(m) != JingcaiExperiment.TS_SEARCH_BYTES_DEFAULT,
                JingcaiExperiment.usePlatformHttpDataSource(m),
                !JingcaiExperiment.injectCaptions(m),
                JingcaiExperiment.touchesPacketBytes(m),
            ).count { it }
            assertEquals("${m.id} must change exactly one knob", 1, knobs)
        }
    }

    @Test
    fun theDiagnosticLineIsStable() {
        assertEquals(
            "mode=off bytesRead=0 firstByteMs=-1 format=none firstFrame=false state=2 code=-",
            JingcaiExperiment.diagnosticLine(JingcaiExperiment.Mode.OFF, 0L, -1L, null, false, 2)
        )
        assertEquals(
            "mode=e1 bytesRead=4096 firstByteMs=123 format=video/avc/1920x1080@25.0 firstFrame=true state=3 code=-",
            JingcaiExperiment.diagnosticLine(
                JingcaiExperiment.Mode.E1, 4096L, 123L, "video/avc/1920x1080@25.0", true, 3
            )
        )
    }
}
