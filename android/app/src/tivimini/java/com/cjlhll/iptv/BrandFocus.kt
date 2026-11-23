package com.cjlhll.iptv

import androidx.compose.ui.graphics.Color

/**
 * 焦点/选中语义 token（t41 用户最终确认）：
 * - 焦点态（遥控器光标所在行）= 灰白浅底 #FFFFFF14，不用蓝色；
 * - 选中态（当前播放台）= 文字品牌蓝 #2F6BFF（DrawerUiPolicy.playingNameColor）。
 * 即蓝色只表达"选中"，不再表达"焦点"。无硬边框（圆角 0dp，不启用 border/shadow）。
 *
 * 保留 BLUE/BG 常量兼容历史引用；所有"焦点底色"改用 FocusBgColor（灰白）。
 */
object BrandFocus {
    const val BLUE_LONG = 0xFF2F6BFF
    const val BLUE = 0xFF2F6BFF.toInt()
    /** 浅蓝底 #2F6BFF22（保留兼容；焦点底色不再使用它）。 */
    const val BG = 0x222F6BFF.toInt()
    /** 灰白浅底 #FFFFFF14（t41：真正的焦点底色，原 tivimini 观感）。 */
    const val FOCUS_BG = 0x14FFFFFF.toInt()
    /** 可选 3dp 侧蓝条（= BLUE）。 */
    const val BAR = BLUE

    val BlueColor: Color get() = Color(BLUE)
    val BgColor: Color get() = Color(BG)
    /** t41：焦点底色——灰白浅底（不是蓝色）。 */
    val FocusBgColor: Color get() = Color(FOCUS_BG)
    val BarColor: Color get() = Color(BAR)
}
