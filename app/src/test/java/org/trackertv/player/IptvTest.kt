package org.trackertv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.trackertv.player.net.Iptv

/** A playlist in the shape iptv-org publishes. */
class IptvTest {
    private val playlist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="1Plus1Marafon.ua@SD" tvg-logo="https://i.imgur.com/smQKa2G.png" group-title="General",1+1 Marafon (1080p)
        https://example.org/marafon/playlist.m3u8
        #EXTINF:-1 tvg-id="Kanal.ua" tvg-logo="https://i.imgur.com/k.png" http-user-agent="Mozilla/5.0 Test" group-title="News;General",Канал Новин (720p) [Not 24/7]
        #EXTVLCOPT:http-referrer=https://example.org/
        https://example.org/news.m3u8
        #EXTINF:-1 tvg-id="Blocked.ua" group-title="Movies",Blocked Movies (576p) [Geo-blocked]
        https://example.org/blocked.m3u8
        #EXTINF:-1 tvg-id="Plain.ua" group-title="Undefined",Plain, with comma
        http://example.org/plain.ts
    """.trimIndent()

    @Test
    fun channelsAreRead() {
        val channels = Iptv.parse(playlist)
        assertEquals(listOf("1+1 Marafon", "Канал Новин", "Plain, with comma"), channels.map { it.name })

        val marafon = channels[0]
        assertEquals("https://example.org/marafon/playlist.m3u8", marafon.url)
        assertEquals("https://i.imgur.com/smQKa2G.png", marafon.logo)
        assertEquals("General", marafon.group)
        assertEquals("1080p", marafon.quality)
        assertFalse(marafon.partTime)
        assertNull(marafon.userAgent)

        val news = channels[1]
        assertEquals("News", news.group)
        assertTrue(news.partTime)
        assertEquals("Mozilla/5.0 Test", news.userAgent)
        assertEquals("https://example.org/", news.referer)

        assertNull("an undefined group is no group", channels[2].group)
        assertNull(channels[2].logo)
    }
}
