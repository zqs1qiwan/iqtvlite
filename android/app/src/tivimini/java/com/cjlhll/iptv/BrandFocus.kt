package com.cjlhll.iptv

import androidx.compose.ui.graphics.Color

/**
 * V4 §9：全局焦点/确认态统一品牌蓝 token（#2F6BFF）。
 *
 * 设计约束（用户确认）：无蓝色大方框/无硬边框 —— 只改背景色（浅蓝底 #2F6BFF22）、
 * 文字色（白色加粗）与可选 3dp 侧蓝条；圆角仍 0dp，不启用任何 border/shadow 资源。
 *
 * 所有焦点变色点统一引用本对象，避免各处散落 `surfaceVariant` 默认灰白（≈#FFFFFF14）。
 */
object BrandFocus {
    const val BLUE_LONG = 0xFF2F6BFF
    const val BLUE = 0xFF2F6BFF.toInt()
    /** 浅蓝底 #2F6BFF22（alpha 0x22）。 */
    const val BG = 0x222F6BFF.toInt()
    /** 可选 3dp 侧蓝条（= BLUE）。 */
    const val BAR = BLUE

    val BlueColor: Color get() = Color(BLUE)
    val BgColor: Color get() = Color(BG)
    val BarColor: Color get() = Color(BAR)
}
