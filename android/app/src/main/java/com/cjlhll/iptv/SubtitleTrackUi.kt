package com.cjlhll.iptv

/** 字幕轨选择纯逻辑模型（与 AudioTrackUi 对称，无 Android 依赖）。 */
object SubtitleTrackUi {

    data class Raw(
        val groupIndex: Int,
        val trackIndex: Int,
        val language: String?,
        val label: String?,
        val mime: String?,
        val selected: Boolean = false,
    )

    data class Item(
        val groupIndex: Int,
        val trackIndex: Int,
        val text: String,
        val selected: Boolean,
        val isOff: Boolean = false,
    )

    fun trackLabel(raw: Raw, ordinal: Int, lang: AppLang = AppLang.ZH): String {
        val parts = buildList {
            add(if (lang == AppLang.EN) "Subtitle $ordinal" else "字幕 $ordinal")
            AudioTrackUi.formatLanguage(raw.language)?.let(::add)
            raw.label?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            raw.mime?.removePrefix("text/")?.removePrefix("application/")
                ?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        }
        return parts.joinToString(" · ")
    }

    fun buildItems(
        raws: List<Raw>,
        disabled: Boolean,
        overrideGroup: Int = -1,
        overrideTrack: Int = -1,
        lang: AppLang = AppLang.ZH,
    ): List<Item> {
        if (raws.isEmpty()) return emptyList()
        val explicitSelected = !disabled && overrideGroup >= 0 && overrideTrack >= 0
        val playerSelectedIdx = raws.indexOfFirst { it.selected }
        val offSelected = disabled || (!explicitSelected && playerSelectedIdx < 0)
        return buildList {
            add(
                Item(
                    groupIndex = -1,
                    trackIndex = -1,
                    text = if (lang == AppLang.EN) "Off" else "关闭字幕",
                    selected = offSelected,
                    isOff = true,
                )
            )
            raws.forEachIndexed { idx, raw ->
                val isSelected = when {
                    disabled -> false
                    explicitSelected -> raw.groupIndex == overrideGroup && raw.trackIndex == overrideTrack
                    else -> idx == playerSelectedIdx
                }
                add(
                    Item(
                        groupIndex = raw.groupIndex,
                        trackIndex = raw.trackIndex,
                        text = trackLabel(raw, idx + 1, lang),
                        selected = isSelected,
                    )
                )
            }
        }
    }
}
