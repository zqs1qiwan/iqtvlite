package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t27/t28：冷启 rebuffer 三实验的运行时开关（`Settings.Global`）解析。
 *
 * 关键不变量：
 * - **没设置/非法值 → 生产默认**（与旧行为逐字节相同）；
 * - `minBufferMs >= afterRebuffer` 恒成立（否则 DefaultLoadControl 启动崩：USAB2-01 / CI10077）——
 *   实验②把 afterRebuffer 抬到 3000 时，必须同时把 minBuffer 抬到 ≥3000，[PerfTuning.afterRebufferMs]
 *   还会再钳一次，任何设置都不可能破坏该不变量。
 */
class PerfTuningTest {

    @Test
    fun parseFallsBackToDefaultForMissingOrInvalidValues() {
        assertEquals(500, PerfTuning.parse(null, 500, 0..2_000))
        assertEquals(500, PerfTuning.parse("", 500, 0..2_000))
        assertEquals(500, PerfTuning.parse("abc", 500, 0..2_000))
        assertEquals(500, PerfTuning.parse("  ", 500, 0..2_000))
        assertEquals(500, PerfTuning.parse("-1", 500, 0..2_000))
        assertEquals(500, PerfTuning.parse("99999", 500, 0..2_000))
    }

    @Test
    fun parseAcceptsValuesInsideTheRangeAndTrims() {
        assertEquals(0, PerfTuning.parse("0", 120, 0..2_000))
        assertEquals(300, PerfTuning.parse("300", 120, 0..2_000))
        assertEquals(500, PerfTuning.parse(" 500 ", 120, 0..2_000))
        assertEquals(2_000, PerfTuning.parse("2000", 120, 0..2_000))
    }

    @Test
    fun productionDefaultsAreUnchanged() {
        // 未设置时：prebuffer=0（不等待）、minBuffer=2500、maxBuffer=6000、afterRebuffer=2000。
        assertEquals(0L, ColdStartPolicy.PREBUFFER_BEFORE_PLAY_MS)
        assertEquals(0, PerfTuning.parse(null, ColdStartPolicy.PREBUFFER_BEFORE_PLAY_MS.toInt(), PerfTuning.PREBUFFER_RANGE))
        assertEquals(2_500, LoadControlTuning.MIN_BUFFER_MS)
        assertEquals(2_000, LoadControlTuning.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
        assertEquals(6_000, LoadControlTuning.MAX_BUFFER_MS)
        assertEquals(6_000, PerfTuning.parse(null, LoadControlTuning.MAX_BUFFER_MS, PerfTuning.MAX_BUFFER_RANGE))
    }

    @Test
    fun afterRebufferIsAlwaysClampedByMinBuffer() {
        // 生产不变量：minBuffer(2500) >= afterRebuffer(2000)。
        assertTrue(LoadControlTuning.MIN_BUFFER_MS >= LoadControlTuning.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
        // 实验②：afterRebuffer=3000 但 minBuffer 仍 2500 → 必须被钳到 2500，绝不破坏不变量。
        val after3000 = PerfTuning.parse("3000", LoadControlTuning.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS, PerfTuning.AFTER_REBUFFER_RANGE)
        assertEquals(3_000, after3000)
        assertEquals(2_500, minOf(after3000, LoadControlTuning.MIN_BUFFER_MS))
        // 成对设置：minBuffer=3500 + afterRebuffer=3000 → 3000 可生效且不变量仍成立。
        val min3500 = PerfTuning.parse("3500", LoadControlTuning.MIN_BUFFER_MS, PerfTuning.MIN_BUFFER_RANGE)
        assertEquals(3_500, min3500)
        assertEquals(3_000, minOf(after3000, min3500))
        assertTrue(min3500 >= minOf(after3000, min3500))
        // 越界值回落默认
        assertEquals(
            LoadControlTuning.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            PerfTuning.parse("9000", LoadControlTuning.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS, PerfTuning.AFTER_REBUFFER_RANGE),
        )
    }

    @Test
    fun minAndMaxBufferRangesKeepTheInvariants() {
        assertEquals(2_500, PerfTuning.MIN_BUFFER_RANGE.first)
        assertEquals(6_000, PerfTuning.MAX_BUFFER_RANGE.first)
        assertTrue("minBuffer 不得低于生产默认 2500", PerfTuning.MIN_BUFFER_RANGE.first >= LoadControlTuning.MIN_BUFFER_MS)
        assertTrue("maxBuffer 必须大于 minBuffer", PerfTuning.MAX_BUFFER_RANGE.first > PerfTuning.MIN_BUFFER_RANGE.first)
        // 实验①：8000 可设；6000 以下回落默认
        assertEquals(8_000, PerfTuning.parse("8000", 6_000, PerfTuning.MAX_BUFFER_RANGE))
        assertEquals(6_000, PerfTuning.parse("1000", 6_000, PerfTuning.MAX_BUFFER_RANGE))
    }
}
