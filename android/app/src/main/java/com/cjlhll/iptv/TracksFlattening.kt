package com.cjlhll.iptv

import androidx.media3.common.C
import androidx.media3.common.Tracks

/**
 * t179/t187 (AUDIO-RENDERER-01 / TRACKS-01): turn `player.currentTracks` into the picker's rows.
 *
 * The filtering itself lives in [select], which works on a plain [Candidate] list, so the rule can be
 * unit-tested without the Android framework (Media3's own `TrackGroup` needs `android.text.TextUtils`).
 * [audioTracks] is the thin Media3 adapter used by the player.
 */
object TracksFlattening {

    /** One audio track as reported by Media3, before the support/duplicate filtering. */
    data class Candidate(
        val groupIndex: Int,
        val trackIndex: Int,
        val language: String?,
        val label: String?,
        val mime: String?,
        val codecs: String?,
        val channels: Int,
        /** `Tracks.Group.isTrackSupported(trackIndex)` */
        val supported: Boolean,
        /** `Tracks.Group.isTrackSelected(trackIndex)` */
        val selected: Boolean,
    )

    /**
     * Keep only what the current renderer supports, then collapse cross-renderer copies of the same
     * stream (see [AudioTrackUi.dedupe], which never merges two tracks of one group).
     */
    fun select(candidates: List<Candidate>): List<AudioTrackUi.Raw> = AudioTrackUi.dedupe(
        candidates.filter { it.supported }.map {
            AudioTrackUi.Raw(
                groupIndex = it.groupIndex,
                trackIndex = it.trackIndex,
                language = it.language,
                label = it.label,
                mime = it.mime,
                codecs = it.codecs,
                channels = it.channels,
                selected = it.selected,
            )
        }
    )

    fun audioTracks(tracks: Tracks): List<AudioTrackUi.Raw> {
        val candidates = ArrayList<Candidate>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                candidates += Candidate(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    language = format.language,
                    label = format.label,
                    mime = format.sampleMimeType,
                    codecs = format.codecs,
                    channels = format.channelCount,
                    supported = group.isTrackSupported(trackIndex),
                    selected = group.isTrackSelected(trackIndex),
                )
            }
        }
        return select(candidates)
    }
}
