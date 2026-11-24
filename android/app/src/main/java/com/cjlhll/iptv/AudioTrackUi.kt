package com.cjlhll.iptv

/**
 * t175/t179 (F4): pure formatting/selection logic for the audio track UI.
 *
 * Android-free so the label rules, the auto/manual state, renderer filtering and the "no audio"
 * degradation are unit-testable; the Compose dialog and the ExoPlayer wiring only consume these.
 */
object AudioTrackUi {

    /**
     * One audio track as reported by `player.currentTracks`, already filtered to the tracks the
     * current renderer actually supports.
     */
    data class Raw(
        val groupIndex: Int,
        val trackIndex: Int,
        val language: String?,
        val label: String?,
        val mime: String?,
        val codecs: String?,
        val channels: Int,
        /** t179 (AUDIO-SUMMARY-01): true when this is the track the player is playing right now. */
        val selected: Boolean = false,
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
    const val AUTO_TEXT_EN = "Auto (Follow stream)"

    /** `mpeg-L2`, `aac`, `ac-3`, … or null when unknown. */
    fun formatCodec(mime: String?, codecs: String?): String? {
        val c = codecs?.trim()?.takeIf { it.isNotEmpty() }
        if (c != null) {
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

    /** `2ch` / `6ch`, null when the count is unknown. */
    fun formatChannels(channels: Int): String? = if (channels > 0) "${channels}ch" else null

    /** Language: keep the ISO code readable, drop the useless "und". */
    fun formatLanguage(language: String?): String? {
        val l = language?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (l.equals("und", ignoreCase = true) || l.equals("unknown", ignoreCase = true)) null else l
    }

    /** `音轨 1 · zh · mpeg-L2 · 2ch`; always non-blank for a real track. */
    fun trackLabel(raw: Raw, ordinal: Int, lang: AppLang = AppLang.ZH): String {
        val parts = buildList {
            add(if (lang == AppLang.EN) "Track $ordinal" else "音轨 $ordinal")
            formatLanguage(raw.language)?.let(::add)
            raw.label?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            formatCodec(raw.mime, raw.codecs)?.let(::add)
            formatChannels(raw.channels)?.let(::add)
        }
        return parts.joinToString(" · ")
    }

    /**
     * Rows for the dialog: 自动 first, then every track.
     * t179 (AUDIO-EMPTY-01): an empty track list yields **no rows**, so the caller shows the
     * `audio_empty` state instead of a misleading 自动 row.
     */
    fun buildItems(
        raws: List<Raw>,
        overrideGroup: Int = -1,
        overrideTrack: Int = -1,
        lang: AppLang = AppLang.ZH,
    ): List<Item> {
        if (raws.isEmpty()) return emptyList()
        val manual = overrideGroup >= 0
        val rows = ArrayList<Item>(raws.size + 1)
        rows += Item(-1, -1, if (lang == AppLang.EN) AUTO_TEXT_EN else AUTO_TEXT, selected = !manual, isAuto = true)
        raws.forEachIndexed { index, raw ->
            rows += Item(
                groupIndex = raw.groupIndex,
                trackIndex = raw.trackIndex,
                text = trackLabel(raw, index + 1, lang),
                selected = manual && raw.groupIndex == overrideGroup && raw.trackIndex == overrideTrack,
            )
        }
        return rows
    }

    /** The track the player is actually playing (t179: explicit override → selected → first). */
    fun currentTrack(
        raws: List<Raw>,
        overrideGroup: Int = -1,
        overrideTrack: Int = -1,
    ): Raw? {
        if (raws.isEmpty()) return null
        val byOverride = if (overrideGroup >= 0) {
            raws.firstOrNull { it.groupIndex == overrideGroup && it.trackIndex == overrideTrack }
        } else null
        return byOverride ?: raws.firstOrNull { it.selected } ?: raws.first()
    }

    /** FULLFIX §4.2②：实际播放音轨的声道数（从 Format 取，不再是 video Format 的 channelCount）。 */
    fun selectedChannelCount(
        raws: List<Raw>,
        overrideGroup: Int = -1,
        overrideTrack: Int = -1,
    ): Int = currentTrack(raws, overrideGroup, overrideTrack)?.channels ?: -1

    /** The line shown in the info banner / settings row; null when there is nothing to show. */
    fun summary(raws: List<Raw>, overrideGroup: Int = -1, overrideTrack: Int = -1, lang: AppLang = AppLang.ZH): String? {
        val current = currentTrack(raws, overrideGroup, overrideTrack) ?: return null
        val parts = buildList {
            formatLanguage(current.language)?.let(::add)
            formatCodec(current.mime, current.codecs)?.let(::add)
            formatChannels(current.channels)?.let(::add)
            if (raws.size > 1) add(if (lang == AppLang.EN) "${raws.size} tracks" else "${raws.size} 条音轨")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Settings row text: says explicitly when there is nothing to select. */
    fun entryLabel(raws: List<Raw>, lang: AppLang = AppLang.ZH): String = if (lang == AppLang.EN) {
        when {
            raws.isEmpty() -> "Audio tracks (None)"
            raws.size == 1 -> "Audio tracks (1 track)"
            else -> "Audio tracks (${raws.size} tracks)"
        }
    } else {
        when {
            raws.isEmpty() -> "音轨选择（无音轨）"
            raws.size == 1 -> "音轨选择（仅 1 条）"
            else -> "音轨选择（${raws.size} 条）"
        }
    }

    /** Only a multi-track channel can be switched meaningfully. */
    fun canSelect(raws: List<Raw>): Boolean = raws.size > 1

    /**
     * t187 (AUDIO-DEDUPE-01): collapse only **cross-renderer copies of the same stream**.
     *
     * Media3 reports one group per candidate renderer, so the same stream can appear twice with an
     * identical format. Two tracks inside the SAME group are always genuinely different (that is how
     * a multi-audio channel is modelled), so the group index is part of the identity and they are
     * never merged. Selected tracks are visited first, so the surviving copy is the one in use.
     */
    fun dedupe(tracks: List<Raw>): List<Raw> {
        val result = ArrayList<Raw>(tracks.size)
        val seen = HashSet<String>()
        tracks.sortedByDescending { it.selected }.forEach { raw ->
            val identity = listOf(
                formatLanguage(raw.language) ?: "",
                formatCodec(raw.mime, raw.codecs) ?: "",
                raw.channels.toString(),
            ).joinToString("|")
            // same group → distinct tracks; different groups + same identity → renderer duplicate
            val key = "${raw.groupIndex}#${raw.trackIndex}#$identity"
            val alreadyKeptForAnotherGroup = result.any { kept ->
                kept.groupIndex != raw.groupIndex && identityOf(kept) == identity
            }
            if (seen.add(key) && !alreadyKeptForAnotherGroup) result += raw
        }
        return result
    }

    private fun identityOf(raw: Raw): String = listOf(
        formatLanguage(raw.language) ?: "",
        formatCodec(raw.mime, raw.codecs) ?: "",
        raw.channels.toString(),
    ).joinToString("|")
}
