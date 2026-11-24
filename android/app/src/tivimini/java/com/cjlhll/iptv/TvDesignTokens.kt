package com.cjlhll.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.darkColorScheme

/**
 * TiviMate 风格「文字 + 台标」全局界面缩放档位（默认中，支持小/中/大实时切换）。
 */
enum class UiScalePreset(
    val id: String,
    val label: String,
    val description: String,
    val textScale: Float,
    val logoScale: Float,
) {
    SMALL("small", "小", "紧凑排布，单屏展示更多频道与节目", 0.88f, 0.84f),
    MEDIUM("medium", "中", "默认推荐，兼顾清晰度与信息密度", 1.00f, 1.00f),
    LARGE("large", "大", "大字与大台标，客厅远距观看更省力", 1.15f, 1.18f);

    companion object {
        val DEFAULT = MEDIUM
        fun parse(raw: String?): UiScalePreset =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * 统一极简高级 Android TV 视觉设计系统（对标 TiviMate 沉静暗色建筑感）：
 * - 深曜石面板底色（#0F131A），统一静默层叠透明度（主抽屉/信息卡 0.92，EPG 区分区 0.62，弹窗 0.96）；
 * - 清晰高对比度灰白磨砂焦点底（#24FFFFFF），零廉价外边框/阴影/外发光；
 * - 克制的品牌蓝（#2F6BFF）仅用于「当前正在播放」与「当前直播进度/状态」；
 * - 面板与行统一 0dp 建筑利落直角（零圆角离屏裁剪开销，兼顾弱机 60fps 与现代极简美感），
 *   居中模态窗与输入控件统一 2dp 微倒角。
 */
object TvDesignTokens {
    // ── 核心表面色 ───────────────────────────────────────────────────────────
    val SurfaceObsidian = Color(0xFF0F131A)
    val SurfaceElevated = Color(0xFF171D27)
    val BackgroundDeep = Color(0xFF0A0D12)
    val ModalScrim = Color(0x8C000000) // 55% 沉静暗幕，突出模态焦点

    // ── 文本与层级 ───────────────────────────────────────────────────────────
    val TextPrimary = Color(0xFFF3F6FA)
    val TextSecondary = Color(0xFFA8B3C4)
    val TextMuted = Color(0xFF6E7A8D)

    // ── 焦点与强调 ───────────────────────────────────────────────────────────
    /** 清晰可读的对称灰白磨砂焦点底（全局纯高亮表达焦点，不附带多余蓝色聚焦竖标）。 */
    val FocusSurface = BrandFocus.FocusBgColor
    /** 品牌强调蓝：仅用于正在播放的频道名、当前直播节目与进度条。 */
    val AccentPlaying = BrandFocus.BlueColor

    // ── 面板透明度（配合静态 graphicsLayer，单次 GPU 合成） ──────────────────
    const val ALPHA_DRAWER = 0.92f
    const val ALPHA_EPG_PANE = 0.62f
    const val ALPHA_SETTINGS = 0.94f
    const val ALPHA_INFO_CARD = 0.92f
    const val ALPHA_MODAL = 0.96f

    // ── 统一圆角 ─────────────────────────────────────────────────────────────
    val ShapeFlat = RoundedCornerShape(0.dp)
    val ShapeModal = RoundedCornerShape(2.dp)

    @OptIn(ExperimentalTvMaterial3Api::class)
    fun colorScheme() = darkColorScheme(
        primary = AccentPlaying,
        onPrimary = Color.White,
        secondary = TextSecondary,
        onSecondary = Color.Black,
        tertiary = TextMuted,
        onTertiary = Color.White,
        background = BackgroundDeep,
        onBackground = TextPrimary,
        surface = SurfaceObsidian,
        onSurface = TextPrimary,
        surfaceVariant = FocusSurface,
        onSurfaceVariant = TextPrimary,
    )
}

/**
 * 安全的 TV 焦点请求：等待至少 1 帧布局挂载完成，并做异常兜底重试，
 * 彻底消除 AnimatedVisibility 首帧未 attach 导致的 IllegalStateException 崩溃或焦点丢失。
 */
@Composable
fun SafeInitialFocus(
    active: Boolean,
    vararg keys: Any?,
    requesterProvider: () -> FocusRequester?,
) {
    LaunchedEffect(active, *keys) {
        if (!active) return@LaunchedEffect
        repeat(6) {
            withFrameNanos { }
            val req = requesterProvider() ?: return@LaunchedEffect
            if (runCatching { req.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/**
 * 所有居中模态弹窗的统一容器（源选择 / 音轨选择 / 最近观看），保证视觉风格、暗幕、尺寸与间距完全一致。
 */
@Composable
fun TvModalContainer(
    width: Dp = 500.dp,
    maxHeight: Dp = 480.dp,
    scrimAlpha: Float = 0.55f,
    modifier: Modifier = Modifier,
    panelModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = panelModifier
                .width(width)
                .heightIn(max = maxHeight)
                .background(TvDesignTokens.SurfaceObsidian.copy(alpha = TvDesignTokens.ALPHA_MODAL), TvDesignTokens.ShapeModal)
                .padding(horizontal = 24.dp, vertical = 22.dp),
            content = content,
        )
    }
}
