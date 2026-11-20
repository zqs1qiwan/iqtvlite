package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** t169: the main-thread session snapshot that replaced the background `player.currentMediaItem` read. */
class PlaybackSessionTest {

    @Test
    fun beginMarksTheSessionCurrent() {
        PlaybackSession.begin("c1-l2-s3")
        assertTrue(PlaybackSession.isCurrent("c1-l2-s3"))
        assertEquals("c1-l2-s3", PlaybackSession.current())
        PlaybackSession.clear()
    }

    @Test
    fun anotherSessionIsNotCurrent() {
        PlaybackSession.begin("a")
        assertFalse("a stale background job must not act on a newer session", PlaybackSession.isCurrent("b"))
        PlaybackSession.clear()
    }

    @Test
    fun clearWithoutArgumentResets() {
        PlaybackSession.begin("x")
        PlaybackSession.clear()
        assertNull(PlaybackSession.current())
        assertFalse(PlaybackSession.isCurrent("x"))
    }

    @Test
    fun clearWithMatchingIdResetsOnlyThatSession() {
        PlaybackSession.begin("new")
        PlaybackSession.clear("old")
        assertTrue("clearing a stale id must not drop the live session", PlaybackSession.isCurrent("new"))
        PlaybackSession.clear("new")
        assertFalse(PlaybackSession.isCurrent("new"))
    }

    @Test
    fun nothingIsCurrentBeforeTheFirstPrepare() {
        PlaybackSession.clear()
        assertFalse(PlaybackSession.isCurrent(""))
        assertFalse(PlaybackSession.isCurrent("anything"))
    }
}
