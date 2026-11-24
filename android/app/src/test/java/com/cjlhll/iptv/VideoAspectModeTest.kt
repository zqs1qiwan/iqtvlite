package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoAspectModeTest {

    @Test
    fun cyclesCleanlyThroughAllModesAndParsesStoredValues() {
        assertEquals(VideoAspectMode.FIT, VideoAspectMode.parse(null))
        assertEquals(VideoAspectMode.FIT, VideoAspectMode.parse("unknown"))
        assertEquals(VideoAspectMode.ZOOM, VideoAspectMode.parse("zoom"))
        assertEquals(VideoAspectMode.FILL, VideoAspectMode.parse("FILL"))

        assertEquals(VideoAspectMode.ZOOM, VideoAspectMode.FIT.next())
        assertEquals(VideoAspectMode.FILL, VideoAspectMode.ZOOM.next())
        assertEquals(VideoAspectMode.FIT, VideoAspectMode.FILL.next())

        assertEquals("适应", VideoAspectMode.FIT.shortLabel(AppLang.ZH))
        assertEquals("Fill", VideoAspectMode.ZOOM.shortLabel(AppLang.EN))
    }
}
