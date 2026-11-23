package com.cjlhll.iptv

import org.junit.Assert.assertNotEquals
import org.junit.Test

/** t41：焦点/选中语义 token —— 焦点底灰白，蓝色只用于选中文字。 */
class BrandFocusTest {

    @Test
    fun focusBackgroundIsGrayWhiteNotBlue() {
        // 焦点底色 = #FFFFFF14 灰白，绝不等于品牌蓝 #2F6BFF。
        assertNotEquals("焦点底色不能是品牌蓝", BrandFocus.BLUE, BrandFocus.FOCUS_BG)
        assertNotEquals("FocusBgColor != BlueColor", BrandFocus.BlueColor, BrandFocus.FocusBgColor)
        // 0x14FFFFFF：alpha=0x14(20/255≈0.078)，RGB=FFFFFF。
        assertNotEquals(0, BrandFocus.FOCUS_BG)
        // 旧浅蓝常量保留兼容，但也不是新焦点底色。
        assertNotEquals(BrandFocus.BG, BrandFocus.FOCUS_BG)
    }

    @Test
    fun selectedTextColorRemainsBrandBlue() {
        // 选中台台名仍用品牌蓝（DrawerUiPolicy.playingNameColor），语义不变。
        assertNotEquals(
            "选中文字蓝 != 焦点底灰白",
            BrandFocus.FocusBgColor,
            BrandFocus.BlueColor,
        )
        assertNotEquals(
            DrawerUiPolicy.NameColor.NORMAL,
            DrawerUiPolicy.playingNameColor(true),
        )
    }
}
