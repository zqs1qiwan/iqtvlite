package com.cjlhll.iptv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the same return/resolve/commit functions called by Activity, with indexes intentionally left at A. */
class UnlistedLiveOwnerTest {
    private val a = Channel("频道A", "http://fixture/a.ts", tvgId="a", id=101, userAgent="UA-A", referrer="https://a.example/")
    private val b = Channel("频道B", "http://fixture/b.ts", tvgId="b", id=202, userAgent="UA-B", referrer="https://b.example/")
    private val c = Channel("频道C", "http://fixture/c.ts", tvgId="c", id=303, userAgent="UA-C")

    private inner class Screen {
        val intent = PlaybackIntent()
        var groups = ChannelGrouper.group(listOf(a,b,c))
        var index = 0
        var variant = 0
        var history: CatchupPlayRequest? = null
        var prepares = 0
        var playerAvailable = true
        fun view() = intent.liveOwner(groups,index,variant)
        fun prepare(request: MediaPlaybackRequest) { intent.prepared(request,"c${request.channel.id}-test-${++prepares}") }
        fun startHistory() {
            prepare(MediaPlaybackRequest(a))
            history=CatchupPlayRequest("http://fixture/b-archive.ts",b.url,"B历史",0,3600000,owner=b)
            val bound=PlaybackIntent.catchup(ChannelGrouper.allChannels(groups),history!!,intent.request)!!
            intent.beginSelection(); prepare(bound)
        }
        suspend fun refresh(channels: List<Channel>, initial: Boolean = false) {
            assertTrue(intent.updatePlaylist(intent.ticket(), { PlaylistSelection(groups,index,view().channel!!) },
                { "controlled playlist" }, { ChannelGrouper.group(channels) }, !initial) { update ->
                val policy=intent.commitPlaylist(update,canPrepare=history == null && playerAvailable,initial=initial,recovery=false)
                groups=update.groups
                policy.groupIndex?.let { index=it }
                policy.variantIndex?.let { variant=it }
                policy.prepare?.let { prepare(it) }
            })
        }
        fun back() {
            val plan=intent.returnToLive(history!!,groups)!!
            assertNull(plan.groupIndex) // This fixture deliberately covers the missing-B Activity branch.
            history=null
            prepare(plan.request)
        }
        fun stop() { playerAvailable=false; intent.suspendPlayer() }
        fun resume() {
            playerAvailable=true
            val request=if (history != null) PlaybackIntent.catchup(ChannelGrouper.allChannels(groups),history!!,intent.request)!!
                else MediaPlaybackRequest(view().channel!!)
            prepare(request)
        }
        fun homeAndResume() { stop(); resume() }
        fun choose(channel: Channel) {
            intent.beginSelection()
            index=groups.indexOfFirst { it.variants.any { row -> row.channel.id == channel.id } }
            variant=0
            history=null
            prepare(MediaPlaybackRequest(view().channel!!))
        }
    }

    @Test fun missingBBackHomeAndAnotherMissingRefreshKeepActualBAndMetadata() = runBlocking<Unit> {
        val screen=Screen()
        screen.startHistory()
        screen.refresh(listOf(a,c))
        assertEquals(a,screen.view().channel) // Original live/browse separation while still watching history.
        screen.back()
        assertEquals(0,screen.index) // No forced browse index is used to hide B's missing row.
        fun assertB() {
            val metadata=screen.view()
            assertEquals(b,metadata.channel)
            assertEquals(-1,metadata.groupIndex)
            assertEquals(0,metadata.number) // Unlisted B must not use A's channel number.
            assertTrue(metadata.retainedOutsideList)
            assertEquals(b,screen.intent.request!!.channel)
            assertEquals(b.url,screen.intent.request!!.streamUrl)
            assertEquals("UA-B",screen.intent.request!!.httpHeaders()["User-Agent"])
            assertEquals("https://b.example/",screen.intent.request!!.httpHeaders()["Referer"])
            assertTrue(screen.intent.active!!.mediaId.startsWith("c202-"))
            assertFalse(screen.intent.request!!.isCatchup)
        }
        assertB()
        screen.homeAndResume(); assertB()
        val preparedBefore=screen.prepares
        screen.refresh(listOf(c,a)); assertB()
        assertEquals(preparedBefore,screen.prepares) // A missing-row update cannot restart A or stop B.
        screen.homeAndResume(); assertB()
    }

    @Test fun sameStableBRejoinsByIdentityAndExplicitCTerminatesRetention() = runBlocking<Unit> {
        val screen=Screen()
        screen.startHistory(); screen.refresh(listOf(a,c)); screen.back()
        val restoredB=b.copy(url="http://fixture/new-b.ts")
        screen.refresh(listOf(c,a,restoredB))
        assertFalse(screen.intent.retainsUnlistedLive)
        assertEquals(2,screen.index)
        assertEquals(restoredB,screen.view().channel)
        assertEquals(3,screen.view().number)
        assertEquals(restoredB,screen.intent.request!!.channel)
        screen.homeAndResume()
        assertEquals(restoredB,screen.intent.request!!.channel)

        val missing=Screen()
        missing.startHistory(); missing.refresh(listOf(a,c)); missing.back()
        missing.choose(c)
        assertFalse(missing.intent.retainsUnlistedLive)
        assertEquals(c,missing.view().channel)
        missing.refresh(listOf(a,c))
        missing.homeAndResume()
        assertEquals(c,missing.intent.request!!.channel)
    }

    @Test fun updatesWhileStoppedKeepBOrRejoinBWithoutPreparingReleasedPlayer() = runBlocking<Unit> {
        val screen=Screen()
        screen.startHistory(); screen.refresh(listOf(a,c)); screen.back()
        screen.stop()
        val count=screen.prepares
        screen.refresh(listOf(c,a),initial=true)
        assertEquals(b,screen.view().channel)
        assertEquals(count,screen.prepares)
        screen.refresh(listOf(c,b,a),initial=true)
        assertFalse(screen.intent.retainsUnlistedLive)
        assertEquals(1,screen.index)
        assertEquals(b,screen.view().channel)
        assertEquals(count,screen.prepares)
        screen.resume()
        assertEquals(b,screen.intent.active!!.request.channel)
        assertEquals(count+1,screen.prepares)
    }

    @Test fun anotherKnownOwnerReusingBUrlCannotEndRetention() = runBlocking<Unit> {
        val screen=Screen()
        screen.startHistory(); screen.refresh(listOf(a,c)); screen.back()
        screen.refresh(listOf(a.copy(url=b.url),c))
        assertTrue(screen.intent.retainsUnlistedLive)
        assertEquals(b,screen.view().channel)
        assertEquals(-1,screen.view().groupIndex)
        screen.homeAndResume()
        assertEquals(b,screen.intent.request!!.channel)
    }
}
