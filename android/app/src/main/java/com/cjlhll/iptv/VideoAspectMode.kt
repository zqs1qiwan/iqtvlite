package com.cjlhll.iptv

/**
 * 播放器画面比例模式：
 * - [FIT] 适应屏幕（保持原始比例，不裁切）
 * - [ZOOM] 铺满裁剪（等比放大填满屏幕，消除黑边）
 * - [FILL] 拉伸全屏（铺满屏幕宽高，允许微调比例）
 *
 * `resizeModeValue` 对应 Media3 `AspectRatioFrameLayout.RESIZE_MODE_*` 常量（0 / 4 / 3）。
 */
enum class VideoAspectMode(
    val id: String,
    val labelZh: String,
    val labelEn: String,
    val shortZh: String,
    val shortEn: String,
    val resizeModeValue: Int,
) {
    FIT("fit", "适应屏幕", "Fit", "适应", "Fit", 0),
    ZOOM("zoom", "铺满裁剪", "Fill", "铺满", "Fill", 4),
    FILL("fill", "拉伸全屏", "Stretch", "拉伸", "Stretch", 3);

    fun localizedLabel(lang: AppLang): String = if (lang == AppLang.EN) labelEn else labelZh

    fun shortLabel(lang: AppLang): String = if (lang == AppLang.EN) shortEn else shortZh

    fun next(): VideoAspectMode = when (this) {
        FIT -> ZOOM
        ZOOM -> FILL
        FILL -> FIT
    }

    companion object {
        val DEFAULT = FIT

        fun parse(raw: String?): VideoAspectMode {
            val cleaned = raw?.trim().orEmpty()
            return entries.firstOrNull {
                it.id.equals(cleaned, ignoreCase = true) || it.name.equals(cleaned, ignoreCase = true)
            } ?: DEFAULT
        }
    }
}
