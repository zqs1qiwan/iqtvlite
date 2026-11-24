package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t13（docs/SETTINGS_IA_V2.md §3.1/§8.3）：一级入口摘要格式化。
 *
 * 摘要必须单行、≤22 全角字符（252dp 内容宽 / 12sp），空值用 `—`，且不得因空/负值崩溃。
 */
class SettingsSummaryTest {

    @Test
    fun everySummaryIsNonEmptyForTypicalValues() {
        assertTrue(SettingsSummary.playback("稳(500ms)", anyExperimentOn = false).isNotBlank())
        assertTrue(SettingsSummary.remote("换台", "显示信息卡").isNotBlank())
        assertTrue(SettingsSummary.audio("mpeg-L2 · 2ch").isNotBlank())
        assertTrue(SettingsSummary.data("启动时", "启动时").isNotBlank())
        assertTrue(SettingsSummary.logo(96, 120).isNotBlank())
        assertTrue(SettingsSummary.about(10145).isNotBlank())
    }

    @Test
    fun exactFormatsMatchTheSpecExamples() {
        assertEquals("96 条 / 120 个", SettingsSummary.logo(96, 120))
        assertEquals("版本 10145", SettingsSummary.about(10145))
        assertEquals("—", SettingsSummary.audio(null))
        assertEquals("—", SettingsSummary.audio("   "))
        assertEquals("起播缓冲 稳(500ms)", SettingsSummary.playback("稳(500ms)", anyExperimentOn = false))
        assertEquals("起播缓冲 稳(500ms) · 实验开", SettingsSummary.playback("稳(500ms)", anyExperimentOn = true))
        assertEquals("换台 · 显示信息卡", SettingsSummary.remote("换台", "显示信息卡"))
        assertEquals("列表 启动时 · EPG 6h", SettingsSummary.data("启动时", "6h"))
        assertEquals("音轨 mpeg-L2 · 2ch", SettingsSummary.audio("mpeg-L2 · 2ch"))
    }

    @Test
    fun summariesStayWithinTheSingleLineBudget() {
        val samples = listOf(
            SettingsSummary.playback("快(300ms)", anyExperimentOn = true),
            SettingsSummary.remote("呼出频道列表", "打开频道列表"),
            SettingsSummary.audio("mpeg-L2 · 2ch · 1920x1080"),
            SettingsSummary.data("6h", "12h"),
            SettingsSummary.logo(1234, 5678),
            SettingsSummary.about(999999),
        )
        samples.forEach { text ->
            assertTrue("摘要超宽：$text = ${SettingsSummary.fullWidthLength(text)} 全角", SettingsSummary.fullWidthLength(text) <= 22)
            assertFalse("摘要不得包含换行", text.contains('\n'))
        }
    }

    @Test
    fun emptyAndNegativeValuesDoNotCrash() {
        assertEquals("—", SettingsSummary.logo(0, 0))
        assertEquals("—", SettingsSummary.logo(-3, -9))
        assertEquals("0 条 / 5 个", SettingsSummary.logo(-3, 5))
        assertEquals("—", SettingsSummary.logoFromCacheSummary(null))
        assertEquals("—", SettingsSummary.logoFromCacheSummary(""))
        assertEquals("—", SettingsSummary.logoFromCacheSummary("garbage"))
    }

    @Test
    fun logoSummaryIsDerivedFromTheExistingCacheLineWithoutExtraIo() {
        // 抽屉里已有的 "内存 N 条 · 磁盘 M 个 / K KB" 直接解析，避免再跑一次 LogoLoader.stats。
        assertEquals(
            "96 条 / 120 个",
            SettingsSummary.logoFromCacheSummary("内存 96 条 · 磁盘 120 个 / 326 KB"),
        )
        assertEquals("—", SettingsSummary.logoFromCacheSummary("内存 0 条 · 磁盘 0 个 / 0 KB"))
    }

    @Test
    fun clampTruncatesOnCharacterBoundaries() {
        // 39 个半角单位 → 超过 44 的只有更长的串；用版本名 + 额外尾巴确保触发截断。
        val long = "版本 0.1.0-iqtv-1a7697f6-beta-build" + "-额外尾巴超长字符串"
        assertTrue("样本必须超宽才能测截断", SettingsSummary.displayUnits(long) > SettingsSummary.MAX_DISPLAY_UNITS)
        val clamped = SettingsSummary.clamp(long)
        assertTrue(SettingsSummary.fullWidthLength(clamped) <= 22)
        assertTrue("截断后补省略号", clamped.endsWith("…"))
        assertFalse("不得出现半个字符导致的乱码", clamped.contains("\uFFFD"))
        // 短文本原样返回（不补省略号）。
        assertEquals("版本 10145", SettingsSummary.clamp("版本 10145"))
    }

    @Test
    fun displayUnitsCountsCjkAsTwo() {
        assertEquals(4, SettingsSummary.displayUnits("设置"))
        assertEquals(2, SettingsSummary.displayUnits("ab"))
        assertEquals(3, SettingsSummary.displayUnits("a设"))
        assertEquals(2, SettingsSummary.fullWidthLength("设置"))
        assertEquals(2, SettingsSummary.fullWidthLength("abc"))
    }
}
