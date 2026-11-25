package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareDecoderPromotionTest {

    private fun candidate(
        name: String,
        softwareOnly: Boolean,
        sizeSupported: Boolean = true,
        unreliableSizeMetadata: Boolean = false,
    ) = VideoDecoderCandidate(
        value = name,
        name = name,
        softwareOnly = softwareOnly,
        sizeSupported = sizeSupported,
        unreliableSizeMetadata = unreliableSizeMetadata,
    )

    @Test
    fun promotesSizeCompatibleHardwareDecoderOverSoftwareFirst() {
        val input = listOf(
            candidate("OMX.google.hevc.decoder", softwareOnly = true),
            candidate("OMX.goke.video.decoder.hevc", softwareOnly = false, sizeSupported = true),
        )

        val result = promoteHardwareVideoDecoders(input)

        assertEquals(
            listOf("OMX.goke.video.decoder.hevc", "OMX.google.hevc.decoder"),
            result,
        )
    }

    @Test
    fun keepsOrderWhenHardwareDecoderIsAlreadyFirst() {
        val input = listOf(
            candidate("OMX.goke.video.decoder.hevc", softwareOnly = false, sizeSupported = true),
            candidate("OMX.google.hevc.decoder", softwareOnly = true),
        )

        val result = promoteHardwareVideoDecoders(input)

        assertEquals(
            listOf("OMX.goke.video.decoder.hevc", "OMX.google.hevc.decoder"),
            result,
        )
    }

    @Test
    fun keepsSoftwareFirstWhenHardwareDecoderDoesNotSupportSize() {
        val input = listOf(
            candidate("OMX.google.hevc.decoder", softwareOnly = true),
            candidate("OMX.vendor.1080p.hevc", softwareOnly = false, sizeSupported = false),
        )

        val result = promoteHardwareVideoDecoders(input)

        assertEquals(
            listOf("OMX.google.hevc.decoder", "OMX.vendor.1080p.hevc"),
            result,
        )
    }

    @Test
    fun preservesRelativeOrderAcrossMultipleHardwareAndSoftwareDecoders() {
        val input = listOf(
            candidate("OMX.google.hevc.decoder", softwareOnly = true),
            candidate("OMX.vendor.1080p.hevc", softwareOnly = false, sizeSupported = false),
            candidate("OMX.goke.video.decoder.hevc", softwareOnly = false, sizeSupported = true),
            candidate("c2.vendor.hevc.decoder", softwareOnly = false, sizeSupported = true),
            candidate("c2.android.hevc.decoder", softwareOnly = true),
        )

        val result = promoteHardwareVideoDecoders(input)

        assertEquals(
            listOf(
                "OMX.goke.video.decoder.hevc",
                "c2.vendor.hevc.decoder",
                "OMX.google.hevc.decoder",
                "OMX.vendor.1080p.hevc",
                "c2.android.hevc.decoder",
            ),
            result,
        )
    }

    @Test
    fun promotesHardwareWhenNeitherSoftwareNorHardwareClaimsSizeSupport() {
        val input = listOf(
            candidate("OMX.google.hevc.decoder", softwareOnly = true, sizeSupported = false),
            candidate("OMX.goke.video.decoder.hevc", softwareOnly = false, sizeSupported = false),
        )

        val result = promoteHardwareVideoDecoders(input)

        assertEquals(
            listOf("OMX.goke.video.decoder.hevc", "OMX.google.hevc.decoder"),
            result,
        )
    }

    @Test
    fun promotesHardwareWhenHardwareSizeMetadataIsCollapsed() {
        val input = listOf(
            candidate("OMX.google.hevc.decoder", softwareOnly = true, sizeSupported = true),
            candidate(
                "OMX.goke.video.decoder.hevc",
                softwareOnly = false,
                sizeSupported = false,
                unreliableSizeMetadata = true,
            ),
        )

        val result = promoteHardwareVideoDecoders(input)

        assertEquals(
            listOf("OMX.goke.video.decoder.hevc", "OMX.google.hevc.decoder"),
            result,
        )
    }

    @Test
    fun handlesEmptyAndSingleCandidateLists() {
        assertEquals(emptyList<String>(), promoteHardwareVideoDecoders(emptyList<VideoDecoderCandidate<String>>()))

        val single = listOf(candidate("OMX.google.hevc.decoder", softwareOnly = true))
        assertEquals(listOf("OMX.google.hevc.decoder"), promoteHardwareVideoDecoders(single))
    }
}
