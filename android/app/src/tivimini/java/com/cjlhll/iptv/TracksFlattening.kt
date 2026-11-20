package com.cjlhll.iptv

import androidx.media3.common.C
import androidx.media3.common.Tracks

/**
 * t179 (AUDIO-RENDERER-01): flatten `player.currentTracks` into the picker's rows.
 *
 * Only tracks the **current renderer supports** are kept (`isTrackSupported`), and duplicates — Media3
 * reports the same stream once per candidate renderer (MediaCodec + the FFmpeg extension) — are
 * collapsed by [AudioTrackUi.dedupe] preferring the selected track. That is what stops an AAC track
 * from being routed to the extension renderer through the UI.
 *
 * Pure mapping over Media3 value types (no player access, no Android APIs), so it stays safe on the
 * application looper and is easy to reason about.
 */
object TracksFlattening {

    fun audioTracks(tracks: Tracks): List<AudioTrackUi.Raw> {
        val flattened = ArrayList<AudioTrackUi.Raw>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                flattened += AudioTrackUi.Raw(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    language = format.language,
                    label = format.label,
                    mime = format.sampleMimeType,
                    codecs = format.codecs,
                    channels = format.channelCount,
                    selected = group.isTrackSelected(trackIndex),
                )
            }
        }
        return AudioTrackUi.dedupe(flattened)
    }
}
