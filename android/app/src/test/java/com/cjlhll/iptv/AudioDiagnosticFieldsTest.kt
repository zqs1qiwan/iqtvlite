package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDiagnosticFieldsTest {
    @Test fun normalAudioTokensRemainUseful() {
        assertEquals("audio/mpeg-L2", AudioDiagnosticFields.mime("audio/mpeg-L2"))
        assertEquals("mp4a.40.2", AudioDiagnosticFields.codecs("mp4a.40.2"))
        assertEquals("zh-Hans-CN", AudioDiagnosticFields.language("zh-Hans-CN"))
        assertEquals("OMX.google.mp3.decoder", AudioDiagnosticFields.name("OMX.google.mp3.decoder"))
        assertEquals("unknown", AudioDiagnosticFields.language(null))
    }

    @Test fun credentialsUrlsControlsAndOversizeValuesCannotEnterLogs() {
        for (value in listOf("https://user:password@host/live?token=secret", "x\nforged session=abc", "x".repeat(1024))) {
            assertEquals("redacted", AudioDiagnosticFields.mime(value))
            assertEquals("redacted", AudioDiagnosticFields.codecs(value))
            assertEquals("redacted", AudioDiagnosticFields.language(value))
            assertEquals("redacted", AudioDiagnosticFields.name(value))
        }
    }
}
