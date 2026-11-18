package com.cjlhll.iptv

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only against an explicitly installed test candidate; no external source or prefs changes. */
@RunWith(AndroidJUnit4::class)
class NativeBoundaryTest {
    private val context get() = object : android.content.ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): android.content.Context = this
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = super.getSharedPreferences("native_test_$name", mode)
        override fun getFilesDir(): java.io.File = java.io.File(super.getFilesDir(), "native-boundary-test").apply { mkdirs() }
        override fun getCacheDir(): java.io.File = java.io.File(super.getCacheDir(), "native-boundary-test").apply { mkdirs() }
    }
    private val xml = """<tv><channel id="c1"><display-name>中文台</display-name></channel><programme channel="c1" start="20261118080000 +0800" stop="20261118090000 +0800"><title>早间新闻</title></programme></tv>"""
    private fun gzip(body: String): ByteArray = ByteArrayOutputStream().apply {
        GZIPOutputStream(this).use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    @Test fun productionM3uAdapterRetainsNativeFieldsAndIdentity() {
        val title = "超长中文频道".repeat(2048)
        val m3u = "#EXTM3U\n#EXTINF:-1 tvg-id=\"c1\" tvg-name=\"中文台\" group-title=\"\" http-user-agent=\"VLC\" http-referrer=\"http://ref.example/\" catchup=\"append\" catchup-source=\"?utc={utc}\",$title\nhttp://example.test/live.ts\n#EXTINF:-1,重复URL\nhttp://example.test/live.ts\n"
        val channels = M3uParser.parse(m3u, "test-source")
        assertEquals(2, channels.size)
        assertEquals(title, channels[0].title)
        assertEquals("中文台", channels[0].tvgName)
        assertNull(channels[0].group)
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", channels[0].userAgent)
        assertEquals("http://ref.example/", channels[0].referrer)
        assertEquals("append", channels[0].catchupMode)
        assertEquals("?utc={utc}", channels[0].catchupSource)
        assertEquals(channels[0].id, M3uParser.parse(m3u, "test-source")[0].id)
        assertNotEquals(channels[0].id, M3uParser.parse(m3u, "other-source")[0].id)
    }

    @Test fun encodedAndBareGzipAreDecodedExactlyOnce() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(gzip(xml))))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/gzip").setBody(Buffer().write(gzip(xml))))
            val encoded = EpgRepository.loadStructured(context, server.url("/encoded").toString(), true)
            val bare = EpgRepository.loadStructured(context, server.url("/bare").toString(), true)
            assertTrue(encoded.isNetworkSuccess)
            assertTrue(bare.isNetworkSuccess)
            val a = encoded.dataOrNull!!.programsByChannelId["c1"]!!.single()
            val b = bare.dataOrNull!!.programsByChannelId["c1"]!!.single()
            assertEquals(a, b)
            assertEquals("早间新闻", a.title)
            assertEquals(28800, a.sourceOffsetSeconds)
            assertEquals(3600000L, a.endMillis-a.startMillis)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun cancelledWaiterDoesNotPoisonSharedRefresh() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(xml).setBodyDelay(1, TimeUnit.SECONDS))
            val source = server.url("/cancelled").toString()
            val first = async { EpgRepository.loadStructured(context, source, true) }
            val second = async { EpgRepository.loadStructured(context, source, true) }
            // Yield the runBlocking thread while both suspend on their shared IO job.
            kotlinx.coroutines.delay(100)
            first.cancel()
            assertTrue(second.await().isNetworkSuccess)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun lanServersUseDifferentPortsAndStopCleanly() {
        val first = SourceConfigLanServer(context) { _, _ -> }
        val second = SourceConfigLanServer(context) { _, _ -> }
        try {
            val a = first.start()
            val b = second.start()
            assertNotEquals(a.port, b.port)
            assertTrue(a.url.endsWith(":${a.port}/"))
            if (tv.iqtvlite.app.BuildConfig.APPLICATION_ID.endsWith(".validation")) {
                assertTrue(a.port in 28081..28084)
                assertTrue(b.port in 28081..28084)
            }
        } finally { first.stop(); second.stop() }
    }

    @Test fun media3CatchupOwnerStaysBForFirstRetryAndHome() {
        MockWebServer().use { server ->
            server.start()
            repeat(20) { server.enqueue(MockResponse().setResponseCode(503)) }
            val a = Channel("直播A",server.url("/a.ts").toString(),id=401,userAgent="UA-A",referrer="https://a.example/")
            val b = Channel("浏览B",server.url("/b.ts").toString(),id=402,userAgent="UA-B",referrer="https://b.example/")
            val programme = CatchupPlayRequest(server.url("/b-archive.ts").toString(),b.url,"B历史",0,3600000,owner=b)
            val intent = PlaybackIntent()
            lateinit var player: ExoPlayer
            fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
            fun wire(path: String, ua: String, ref: String) {
                val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline) {
                    val observed = server.takeRequest(1,TimeUnit.SECONDS) ?: continue
                    if (observed.path != path) continue // A canceled source may already have sent one request.
                    assertEquals(ua,observed.getHeader("User-Agent"))
                    assertEquals(ref,observed.getHeader("Referer"))
                    return
                }
                fail("No request for $path")
            }
            main { player=ExoPlayer.Builder(context).build(); StreamPlayback.prepare(player,context,MediaPlaybackRequest(a),intent) }
            try {
                wire("/a.ts","UA-A","https://a.example/")
                val bound = PlaybackIntent.catchup(listOf(a,b),programme,intent.request)!!
                main { StreamPlayback.prepare(player,context,bound,intent) }
                wire("/b-archive.ts","UA-B","https://b.example/")
                assertTrue(intent.active!!.mediaId.startsWith("c402-"))
                val retry = intent.active!!.request
                main { StreamPlayback.prepare(player,context,retry,intent) }
                wire("/b-archive.ts","UA-B","https://b.example/")
                main { intent.suspendPlayer(); StreamPlayback.release(player); player=ExoPlayer.Builder(context).build()
                    val home=PlaybackIntent.catchup(listOf(a),programme,intent.request)!!
                    StreamPlayback.prepare(player,context,home,intent) }
                wire("/b-archive.ts","UA-B","https://b.example/")
                assertTrue(intent.active!!.mediaId.startsWith("c402-"))
                assertEquals(b.id,intent.active!!.request.channel.id)
                val missingRows=ChannelGrouper.group(listOf(a))
                main {
                    val back=intent.returnToLive(programme,missingRows)!!
                    assertNull(back.groupIndex)
                    StreamPlayback.prepare(player,context,back.request,intent)
                }
                wire("/b.ts","UA-B","https://b.example/")
                main {
                    intent.suspendPlayer(); StreamPlayback.release(player); player=ExoPlayer.Builder(context).build()
                    val live=intent.liveOwner(missingRows,0,0)
                    assertEquals(b,live.channel)
                    assertEquals(0,live.number)
                    StreamPlayback.prepare(player,context,MediaPlaybackRequest(live.channel!!),intent)
                    runBlocking<Unit> {
                        assertTrue(intent.updatePlaylist(intent.ticket(),
                            { PlaylistSelection(missingRows,0,intent.liveOwner(missingRows,0,0).channel!!) },
                            { "missing B" }, { missingRows }, false) { update ->
                            val commit=intent.commitPlaylist(update,canPrepare=true,initial=false,recovery=false)
                            assertNull(commit.prepare)
                            assertNull(commit.groupIndex)
                        })
                    }
                    assertEquals(b,intent.liveOwner(missingRows,0,0).channel)
                    assertEquals("UA-B",StreamPlayback.restore(context,b.url,b.title).userAgent)
                    assertEquals("https://b.example/",StreamPlayback.restore(context,b.url,b.title).referrer)
                    assertTrue(intent.active!!.mediaId.startsWith("c402-"))
                }
                wire("/b.ts","UA-B","https://b.example/")
            } finally { main { StreamPlayback.release(player) } }
        }
    }

    @Test fun media3ErrorCarriesProducingSessionRatherThanLaterCurrentChannel() {
        MockWebServer().use { server ->
            server.start()
            repeat(6) { server.enqueue(MockResponse().setBody("not a media stream")) }
            val errors = java.util.concurrent.LinkedBlockingQueue<PlaybackFailure>()
            val intent = PlaybackIntent()
            val a = Channel("A",server.url("/a.ts").toString(),id=501,userAgent="UA-A")
            val b = Channel("B",server.url("/b.ts").toString(),id=502,userAgent="UA-B")
            lateinit var player: ExoPlayer
            lateinit var first: PreparedPlayback
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player=ExoPlayer.Builder(context).build()
                first=StreamPlayback.prepare(player,context,MediaPlaybackRequest(a),intent) { errors.offer(it) }
            }
            try {
                val error = errors.poll(15,TimeUnit.SECONDS)
                assertNotNull("Actual Media3 parser error must carry a session",error)
                assertEquals(first.mediaId,error!!.playback.mediaId)
                assertEquals(a,error.playback.request.channel)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    intent.beginSelection()
                    StreamPlayback.prepare(player,context,MediaPlaybackRequest(b),intent) { errors.offer(it) }
                }
                assertEquals(a,error.playback.request.channel)
                assertFalse(intent.isCurrent(error.playback))
                assertTrue(intent.active!!.mediaId.startsWith("c502-"))
            } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { StreamPlayback.release(player) } }
        }
    }

    @Test fun media3RequestUsesChannelUaAndReferer() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            lateinit var player: ExoPlayer
            val channel = Channel("HTTP边界测试", server.url("/live.ts").toString(), id=123,
                userAgent="Channel-UA/1", referrer="https://ref.example/")
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player = ExoPlayer.Builder(context).build()
                StreamPlayback.prepare(player, context, channel)
            }
            try {
                val request = server.takeRequest(10, TimeUnit.SECONDS)
                assertNotNull(request)
                assertEquals("Channel-UA/1", request!!.getHeader("User-Agent"))
                assertEquals("https://ref.example/", request.getHeader("Referer"))
                server.enqueue(MockResponse().setResponseCode(503))
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    StreamPlayback.prepare(player, context, Channel("默认UA", server.url("/default.ts").toString(), id=124))
                }
                val next = server.takeRequest(10, TimeUnit.SECONDS)
                assertNotNull(next)
                assertEquals("/default.ts", next!!.path)
                assertEquals("VLC/3.0.20 LibVLC/3.0.20", next.getHeader("User-Agent"))
                assertNull(next.getHeader("Referer"))
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { StreamPlayback.release(player) }
            }
        }
    }
}
