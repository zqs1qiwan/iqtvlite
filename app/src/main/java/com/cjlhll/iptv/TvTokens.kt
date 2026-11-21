package com.cjlhll.iptv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全局 TV 设计 token（对应 docs/UI_UX_DESIGN.md 第 2 节）。
 *
 * 统一字号、颜色、间距，避免各处分散定义 typography 与低透明文字。所有尺寸为 dp/sp，
 * 面板宽度按实际可用宽度 W 计算为 min(360.dp, W / 3)，不能用物理像素替代。
 */
object TvColors {
    /** 页面背景 */
    val Page = Color(0xFF101316)

    /** 侧栏 / 面板背景（设计值 alpha 0.96，这里保留 alpha 便于叠加视频） */
    val Panel = Color(0xFF161C21)
    const val PANEL_ALPHA = 0.96f

    val TextPrimary = Color(0xFFF5F7F8)
    val TextSecondary = Color(0xFFBCC7CE)
    val TextDisabled = Color(0xFF84919A)

    /** 焦点填充 + 焦点描边 */
    val FocusFill = Color(0xFF30454D)
    val FocusBorder = Color(0xFFA8E8D4)

    /** 当前播放 / 选中 */
    val Playing = Color(0xFF76D7BB)

    /** 错误 / 成功 */
    val Error = Color(0xFFFFB4AB)
    val Success = Color(0xFF76D7BB)
}

object TvDims {
    /** 面板最大宽度与屏宽占比上限 */
    val PanelMaxWidth: Dp = 360.dp
    val PanelWidthScreenFraction: Float = 1f / 3f

    /** 间距梯度 4 / 8 / 12 / 16 / 24 / 32 / 48 */
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val xxxl: Dp = 48.dp

    /** 侧栏内容左右内边距；列表行内左右 12 */
    val PanelContentHorizontal: Dp = 24.dp
    val RowHorizontal: Dp = 12.dp

    /** 项高：设置/分组最少 56；频道最少 112；节目最少 88 */
    val SettingsRowMinHeight: Dp = 56.dp
    val ChannelRowMinHeight: Dp = 112.dp
    val ProgramRowMinHeight: Dp = 88.dp

    val CardCorner: Dp = 12.dp
}

/** 设计强调的 TV 字号（sp）。正文不小于 16sp。 */
object TvType {
    val PageTitle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    val SectionTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium)
    val Item = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 18.sp)
    val Aux = TextStyle(fontSize = 16.sp)
    val BannerTitle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    val NumberBuffer = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
}

/** 按实际可用宽度计算单容器侧栏宽度：min(360dp, W/3)。 */
fun computePanelWidth(availableWidth: Dp): Dp {
    val third = availableWidth * TvDims.PanelWidthScreenFraction
    return when {
        third < TvDims.PanelMaxWidth -> third
        else -> TvDims.PanelMaxWidth
    }
}
