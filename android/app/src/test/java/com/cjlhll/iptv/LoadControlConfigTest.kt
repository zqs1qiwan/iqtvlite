package com.cjlhll.iptv

import androidx.media3.exoplayer.DefaultLoadControl
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Guard for USAB2-01: a previous LoadControl buffer config (800/6000/300/1000) violated Media3's
 * constraint minBufferMs >= bufferForPlaybackAfterRebufferMs and crashed PlayerActivity at start
 * (CI10077). This JVM test exercises the real DefaultLoadControl.Builder so an invalid parameter set
 * fails here instead of only on device.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LoadControlConfigTest {

    @Test
    fun productionBufferDurationsAreValid() {
        // Must NOT throw. Keep in sync with PlayerActivity.initializePlayer().
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 6_000, 300, 1_000)
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    @Test
    fun invalidBufferDurationsThrow() {
        // Reproduces the CI10077 crash: minBufferMs 800 < bufferForPlaybackAfterRebufferMs 1000.
        assertThrows(IllegalArgumentException::class.java) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(800, 6_000, 300, 1_000)
                .build()
        }
    }
}
