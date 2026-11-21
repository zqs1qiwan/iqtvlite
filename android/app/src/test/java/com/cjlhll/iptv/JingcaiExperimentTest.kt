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
            JingcaiExperiment.Mode.D,
            JingcaiExperiment.Mode.E,
            JingcaiExperiment.Mode.F,
            JingcaiExperiment.Mode.G,
            JingcaiExperiment.Mode.H,
            JingcaiExperiment.Mode.OFF,
        )
        expected.forEach { want ->
            m = JingcaiExperiment.next(m)
            assertEquals(want, m)
        }
    }

    @Test
    fun timelineExperimentsAreTheOnlyPacketRewriters() {
        // t54：D（彻底去 PCR）/ E（PES 时间戳归零）/ G（丢前导字节）也是包级改写，全部默认关。
        assertEquals(
            setOf(
                JingcaiExperiment.Mode.A, JingcaiExperiment.Mode.B, JingcaiExperiment.Mode.AUTO,
                JingcaiExperiment.Mode.D, JingcaiExperiment.Mode.E, JingcaiExperiment.Mode.G,
            ),
            JingcaiExperiment.Mode.entries.filter { JingcaiExperiment.touchesPacketBytes(it) }.toSet(),
        )
        assertTrue(JingcaiExperiment.timebaseRebase(JingcaiExperiment.Mode.C))
        assertFalse(JingcaiExperiment.touchesPacketBytes(JingcaiExperiment.Mode.C))
    }

    @Test
    fun t54DelayedStartAndLeadingDropAreSeparateKnobs() {
        // F 只改“何时开始播”，G 只改“吐出字节的起点”，两者互斥且都不影响健康频道。
        assertEquals(true, JingcaiExperiment.delayedStart(JingcaiExperiment.Mode.F))
        assertEquals(false, JingcaiExperiment.delayedStart(JingcaiExperiment.Mode.G))
        assertEquals(true, JingcaiExperiment.dropLeadingBytes(JingcaiExperiment.Mode.G))
        assertEquals(false, JingcaiExperiment.dropLeadingBytes(JingcaiExperiment.Mode.F))
        assertEquals(1_200L, JingcaiExperiment.DELAYED_START_MS)
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
        // C = 时基归零设计占位；t54 的 F（延迟起播）/ H（新 DataSource 重建）不靠代码开关改字节/提取器，
        // 单独在下面两条断言里校验，不参与“一个旋钮”统计。
        val modes = JingcaiExperiment.Mode.entries.filter {
            it != JingcaiExperiment.Mode.OFF && it != JingcaiExperiment.Mode.C &&
                it != JingcaiExperiment.Mode.F && it != JingcaiExperiment.Mode.H
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
