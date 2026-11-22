package com.cjlhll.iptv

/**
 * t60：节目名清洗（纯函数，无 Android import）。
 *
 * 背景：t59 的抽屉行残影在 60.卡酷少儿 / 61.北京IPTV 附近间歇出现**单个汉字**，
 * 且 `softWrap=false` 下文字被测量成极窄宽 → 更像“不可见字符把首字挤成单独一行/极窄”，不是绘制残留。
 *
 * 处理：
 * - `\u00A0`（NBSP）、`\u3000`（全角空格）→ 普通空格；
 * - 零宽字符 `\u200B..\u200D`、`\uFEFF`（BOM/ZWSP）直接删除；
 * - 连续空白压成一个空格并 trim；空白结果 → null（调用方据此不渲染副标题）。
 */
object TextSanitize {

    fun programTitle(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val sb = StringBuilder(raw.length)
        var lastWasSpace = false
        for (ch in raw) {
            when {
                ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF' -> Unit
                ch == '\u00A0' || ch == '\u3000' || ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' -> {
                    if (!lastWasSpace) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                }
                ch.code < 0x20 -> Unit
                else -> {
                    sb.append(ch)
                    lastWasSpace = false
                }
            }
        }
        val out = sb.toString().trim()
        return out.ifEmpty { null }
    }

    /** 校验用：是否包含任何“不可见字符”（测试/门禁可断言）。 */
    fun hasInvisible(raw: String): Boolean =
        raw.any { it == '\u00A0' || it == '\u3000' || it == '\u200B' || it == '\u200C' || it == '\u200D' || it == '\uFEFF' }
}
