package com.cjlhll.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel as EventQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIntentRegressionTest {
    private val a = Channel("频道A", "http://fixture/a.ts", tvgId="a", id=101, userAgent="UA-A", referrer="https://a.example/")
    private val b = Channel("频道B", "http://fixture/b.ts", tvgId="b", id=202, userAgent="UA-B", referrer="https://b.example/")
    private val c = Channel("频道C", "http://fixture/c.ts", tvgId="c", id=303, userAgent="UA-C")
    private fun groups(vararg channels: Channel) = ChannelGrouper.group(channels.toList())

    @Test fun aToBBackgroundRefreshPreservesLatestBAndHeadersAcrossReorder() = runBlocking<Unit> {
        val intent = PlaybackIntent()
        intent.prepared(MediaPlaybackRequest(a), "A-session")
        var rows = groups(a,b,c)
        var selected = a
        intent.beginSelection()
        selected = b
        intent.prepared(MediaPlaybackRequest(b), "B-session")
        var committed: PlaylistApplication? = null
        assertTrue(intent.updatePlaylist(intent.ticket(),
            { PlaylistSelection(rows, rows.indexOfFirst { it.defaultChannel.url == selected.url }, selected) },
            { "fresh playlist" }, { groups(c,a,b) }, false) { update ->
            committed=update; rows=update.groups; selected=update.channel
        })
        assertEquals(b.url, selected.url)
        assertEquals(2, committed!!.groupIndex)
        assertEquals("UA-B", committed!!.channel.userAgent)
        assertEquals(b, intent.request!!.channel)
    }

    @Test fun queuedOldAErrorCannotDownloadOrStampCurrentBAsFailed() = runBlocking<Unit> {
        val intent = PlaybackIntent()
        val failedA = intent.prepared(MediaPlaybackRequest(a), "actual-A-media-id")
        val events = EventQueue<PlaybackFailure>(EventQueue.BUFFERED)
        events.send(PlaybackFailure(failedA))
        intent.beginSelection()
        intent.prepared(MediaPlaybackRequest(b), "actual-B-media-id")
        val queued = events.receive()
        assertEquals(a, queued.playback.request.channel)
        var downloaded = false
        val applied = intent.recover(queued, { PlaylistSelection(groups(a,b),1,b) },
            { downloaded=true; "old" }, { error("old A must not parse") }) { error("old A must not apply") }
        assertFalse(applied)
        assertFalse(downloaded)
        assertEquals("actual-B-media-id", intent.active!!.mediaId)
        events.close()
    }

    @Test fun delayedRecoveryParseCannotCoverUserSwitchFromBToC() = runBlocking<Unit> {
        val intent = PlaybackIntent()
        val failedB = intent.prepared(MediaPlaybackRequest(b), "B-failed")
        var selected = b
        val parseEntered = CompletableDeferred<Unit>()
        val finishParse = CompletableDeferred<Unit>()
        var commits = 0
        val update = async {
            intent.recover(PlaybackFailure(failedB), { PlaylistSelection(groups(a,b,c),1,selected) },
                { "updated" }, { parseEntered.complete(Unit); finishParse.await(); groups(a,b,c) }) { commits++ }
        }
        parseEntered.await()
        intent.beginSelection(); selected=c
        finishParse.complete(Unit)
        assertFalse(update.await())
        assertEquals(0, commits)
        assertEquals(c, selected)
        intent.prepared(MediaPlaybackRequest(c), "C-new")
        assertEquals(c, intent.active!!.request.channel)
    }

    @Test fun delayedBackgroundReadCannotReplaceNewUserSelection() = runBlocking<Unit> {
        val intent = PlaybackIntent()
        intent.prepared(MediaPlaybackRequest(b), "B")
        val ticket = intent.ticket()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val update = async {
            intent.updatePlaylist(ticket, { PlaylistSelection(groups(a,b,c),1,b) },
                { entered.complete(Unit); finish.await(); "old-read" }, { error("stale download must not parse") }, false) { error("stale read must not commit") }
        }
        entered.await()
        intent.beginSelection()
        intent.prepared(MediaPlaybackRequest(c), "C")
        finish.complete(Unit)
        assertFalse(update.await())
        assertEquals(c, intent.request!!.channel)
    }

    @Test fun currentBErrorRefreshRetainsBWhenAStillExists() = runBlocking<Unit> {
        val intent = PlaybackIntent()
        val failedB = intent.prepared(MediaPlaybackRequest(b), "B-failed")
        var committed: MediaPlaybackRequest? = null
        assertTrue(intent.recover(PlaybackFailure(failedB), { PlaylistSelection(groups(a,b),1,b) },
            { "fresh" }, { groups(a,b) }) { update ->
            committed=MediaPlaybackRequest(update.channel)
            intent.prepared(committed!!, "B-recovery")
        })
        assertEquals(b, committed!!.channel)
        assertEquals("B-recovery", intent.active!!.mediaId)
    }

    @Test fun browsingBCatchupDoesNotBorrowAAndRetainsBForRetryAndHome() {
        val intent = PlaybackIntent()
        intent.prepared(MediaPlaybackRequest(a), "A-live")
        val programme = CatchupPlayRequest("http://fixture/b-archive.ts", b.url, "B历史节目", 0, 3600000)
        val bound = PlaybackIntent.catchup(listOf(a,b), programme, intent.request)!!
        assertEquals(b, bound.channel)
        assertEquals("UA-B", bound.channel.userAgent)
        assertEquals("https://b.example/", bound.channel.referrer)
        assertEquals(programme.catchupUrl, bound.streamUrl)
        intent.beginSelection()
        val first = intent.prepared(bound, "B-catchup")
        intent.prepared(first.request, "B-retry")
        intent.suspendPlayer()
        val resumed = PlaybackIntent.catchup(listOf(a), programme, intent.request)!!
        assertEquals(bound, resumed) // Even a refreshed list missing B cannot retag it as A.
        intent.prepared(resumed, "B-home")
        assertEquals(202L, intent.active!!.request.channel.id)
        assertEquals("https://b.example/", intent.active!!.request.channel.referrer)
        val backToLive = MediaPlaybackRequest(intent.request!!.channel)
        assertEquals(b.url, backToLive.streamUrl)
        assertFalse(backToLive.isCatchup)
    }

    @Test fun boundBHeadersReachLocalhostForFirstRetryAndHomeWithoutChangingLiveA() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        repeat(4) { server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("ok")) }
        try {
            val base = server.url("/").toString().trimEnd('/')
            val liveA = a.copy(url="$base/a.ts")
            val liveB = b.copy(url="$base/b.ts")
            val programme = CatchupPlayRequest("$base/b-catchup.ts",liveB.url,"B历史",0,3600000,owner=liveB)
            val intent = PlaybackIntent()
            fun send(request: MediaPlaybackRequest) {
                val http = okhttp3.Request.Builder().url(request.streamUrl).apply { request.httpHeaders().forEach { (k,v) -> header(k,v) } }.build()
                NetUtil.ipv4OkHttpClient.newCall(http).execute().use { assertTrue(it.isSuccessful) }
            }
            intent.prepared(MediaPlaybackRequest(liveA),"A-live"); send(intent.request!!)
            val bound = PlaybackIntent.catchup(listOf(liveA,liveB),programme,intent.request)!!
            intent.prepared(bound,"B-first"); send(intent.request!!)
            val retry = intent.active!!.request
            intent.prepared(retry,"B-retry"); send(intent.request!!)
            intent.suspendPlayer()
            val home = PlaybackIntent.catchup(listOf(liveA),programme,intent.request)!!
            intent.prepared(home,"B-home"); send(intent.request!!)
            fun received(): Triple<String?,String?,String?> {
                val observed = server.takeRequest(3,java.util.concurrent.TimeUnit.SECONDS)!!
                return Triple(observed.path,observed.getHeader("User-Agent"),observed.getHeader("Referer"))
            }
            assertEquals(Triple("/a.ts","UA-A","https://a.example/"),received())
            repeat(3) { assertEquals(Triple("/b-catchup.ts","UA-B","https://b.example/"),received()) }
            assertEquals(202L,intent.active!!.request.channel.id)
        } finally { server.shutdown() }
    }

    @Test fun backgroundRefreshUsesBIdentityWhenAReusesBsUrl() = runBlocking<Unit> {
        val sameUrlA = a.copy(url=b.url)
        for (updatedB in listOf(b,b.copy(url="http://fixture/new-b.ts"))) {
            val intent = PlaybackIntent()
            intent.prepared(MediaPlaybackRequest(b),"B")
            var result: Channel? = null
            assertTrue(intent.updatePlaylist(intent.ticket(), { PlaylistSelection(groups(sameUrlA,b),1,b) },
                { "fresh" }, { groups(sameUrlA,updatedB) }, false) { result=it.channel })
            assertEquals(updatedB,result)
            assertEquals("UA-B",result!!.userAgent)
        }
    }

    @Test fun explicitCatchupOwnerDisambiguatesDuplicateLiveUrl() {
        val sameUrlA = a.copy(url=b.url)
        val programme = CatchupPlayRequest("http://fixture/archive",b.url,"B节目",0,1,owner=b)
        val bound = PlaybackIntent.catchup(listOf(sameUrlA,b),programme,MediaPlaybackRequest(sameUrlA))!!
        assertEquals(b.id,bound.channel.id)
        assertEquals("UA-B",bound.channel.userAgent)
    }

    @Test fun missingCatchupOwnerDoesNotFallBackToUnrelatedLiveA() {
        val programme = CatchupPlayRequest("http://fixture/b-archive.ts", b.url, "B节目", 0, 1)
        assertNull(PlaybackIntent.catchup(listOf(a), programme, MediaPlaybackRequest(a)))
    }
}
