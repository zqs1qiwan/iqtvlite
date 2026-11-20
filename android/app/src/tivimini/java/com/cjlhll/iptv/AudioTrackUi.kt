package com.cjlhll.iptv

/**
 * t175 (F4): pure formatting/selection logic for the audio track UI.
 *
 * Kept Android-free so the label rules, the auto/manual state and the "no audio" degradation are
 * unit-testable; the Compose dialog and the ExoPlayer wiring only consume these values.
 */
object AudioTrackUi {

    /** One audio track as reported by `player.currentTracks` (already flattened). */
    data class Raw(
        val groupIndex: Int,
        val trackIndex: Int,
        val language: String?,
        val label: String?,
        val mime: String?,
        val codecs: String?,
        val channels: Int,
    )

    /** One row of the dialog. The 自动 row is `groupIndex == -1`. */
    data class Item(
        val groupIndex: Int,
        val trackIndex: Int,
        val text: String,
        val selected: Boolean,
        val isAuto: Boolean = false,
    )

    const val AUTO_TEXT = "自动（跟随片源）"

    /** `mpeg-L2`, `aac`, `ac-3`, … or null when unknown. */
    fun formatCodec(mime: String?, codecs: String?): String? {
        val c = codecs?.trim()?.takeIf { it.isNotEmpty() }
        if (c != null) {
            // Media3 reports MP2 as "mp4a.40.2"-style or "mpeg-L2"/"mp2"; keep it short and readable.
            val lower = c.lowercase()
            return when {
                lower.contains("mpeg-l2") || lower == "mp2" || lower.contains("mpeg2") -> "mpeg-L2"
                lower.contains("aac") || lower.startsWith("mp4a.40.2") -> "aac"
                lower.contains("ac-3") || lower.contains("ac3") -> "ac-3"
                lower.contains("eac3") || lower.contains("e-ac-3") -> "eac3"
                lower.contains("opus") -> "opus"
                lower.contains("flac") -> "flac"
                lower.contains("mp3") -> "mp3"
                else -> c
            }
        }
        val m = mime?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (m.lowercase()) {
            "audio/mpeg-l2" -> "mpeg-L2"
            "audio/mpeg" -> "mp3"
            "audio/mp4a-latm", "audio/aac" -> "aac"
            "audio/ac3" -> "ac-3"
            "audio/eac3" -> "eac3"
            "audio/opus" -> "opus"
            "audio/flac" -> "flac"
            else -> m.removePrefix("audio/")
        }
    }

    /** `2ch` / `6ch` (max 8 reported), null when the count is unknown. */
    fun formatChannels(channels: Int): String? = if (channels > 0) "${channels}ch" else null

    /** Language: keep the ISO code readable, drop the useless "und". */
    fun formatLanguage(language: String?): String? {
        val l = language?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (l.equals("und", ignoreCase = true) || l.equals("unknown", ignoreCase = true)) null else l
    }

    /** `中文 · mpeg-L2 · 2ch` and similar; always non-blank for a real track. */
    fun trackLabel(raw: Raw, ordinal: Int): String {
        val parts = buildList {
            add("音轨 $ordinal")
            formatLanguage(raw.language)?.let(::add)
            raw.label?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            formatCodec(raw.mime, raw.codecs)?.let(::add)
            formatChannels(raw.channels)?.let(::add)
        }
        return parts.joinToString(" · ")
    }

    /** Rows for the dialog: 自动 first, then every track; [overrideGroup]/[overrideTrack] = -1 → auto. */
    fun buildItems(
        raws: List<Raw>,
        overrideGroup: Int = -1,
        overrideTrack: Int = -1,
    ): List<Item> {
        val manual = overrideGroup >= 0
        val rows = ArrayList<Item>(raws.size + 1)
        rows += Item(-1, -1, AUTO_TEXT, selected = !manual, isAuto = true)
        raws.forEachIndexed { index, raw ->
            rows += Item(
                groupIndex = raw.groupIndex,
                trackIndex = raw.trackIndex,
                text = trackLabel(raw, index + 1),
                selected = manual && raw.groupIndex == overrideGroup && raw.trackIndex == overrideTrack,
            )
        }
        return rows
    }

    /** The line shown in the info banner / settings row; null when there is nothing to show. */
    fun summary(raws: List<Raw>, overrideGroup: Int = -1, overrideTrack: Int = -1): String? {
        if (raws.isEmpty()) return null
        val current = if (overrideGroup >= 0) {
            raws.firstOrNull { it.groupIndex == overrideGroup && it.trackIndex == overrideTrack }
        } else {
            raws.firstOrNull()
        } ?: raws.first()
        val parts = buildList {
            formatLanguage(current.language)?.let(::add)
            formatCodec(current.mime, current.codecs)?.let(::add)
            formatChannels(current.channels)?.let(::add)
            if (raws.size > 1) add("${raws.size} 条音轨")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Settings row text: hidden entry when there is nothing selectable. */
    fun entryLabel(raws: List<Raw>): String = when {
        raws.isEmpty() -> "音轨选择（无音轨）"
        raws.size == 1 -> "音轨选择（仅 1 条）"
        else -> "音轨选择（${raws.size} 条）"
    }

    /** Only a multi-track channel can be switched meaningfully — single/absent tracks hide the action. */
    fun canSelect(raws: List<Raw>): Boolean = raws.size > 1
}
