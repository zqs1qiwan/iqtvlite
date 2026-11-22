package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** t60：节目名清洗（抽屉残影根因是“不可见字符把首字挤成单独一行/极窄”）。 */
class TextSanitizeTest {

    @Test
    fun stripsZeroWidthAndNormalizesSpaces() {
        assertEquals("北京IPTV", TextSanitize.programTitle("北京IPTV"))
        // 全角空格 + NBSP → 普通空格
        assertEquals("新闻 联播", TextSanitize.programTitle("新闻\u3000联播"))
        assertEquals("新闻 联播", TextSanitize.programTitle("新闻\u00A0联播"))
        // 零宽字符直接删
        assertEquals("北京IPTV", TextSanitize.programTitle("北\u200B京\u200CIPTV\u200D"))
        assertEquals("北京IPTV", TextSanitize.programTitle("\uFEFF北京IPTV"))
        // 连续空白压成一个空格 + trim
        assertEquals("a b", TextSanitize.programTitle("  a \u3000\u00A0 b  "))
    }

    @Test
    fun blankOrInvisibleOnlyBecomesNull() {
        assertNull(TextSanitize.programTitle(null))
        assertNull(TextSanitize.programTitle(""))
        assertNull(TextSanitize.programTitle("\u200B\uFEFF"))
        assertNull(TextSanitize.programTitle("\u3000\u00A0 "))
    }

    @Test
    fun hasInvisibleDetectsTheCharactersWeCareAbout() {
        assertTrue(TextSanitize.hasInvisible("a\u3000b"))
        assertTrue(TextSanitize.hasInvisible("a\u00A0b"))
        assertTrue(TextSanitize.hasInvisible("a\u200Bb"))
        assertFalse(TextSanitize.hasInvisible("北京IPTV 新闻联播"))
    }
}
