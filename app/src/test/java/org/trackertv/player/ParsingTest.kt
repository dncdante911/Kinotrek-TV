package org.trackertv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.trackertv.player.data.LibraryItem
import org.trackertv.player.data.Progress
import org.trackertv.player.data.Quality
import org.trackertv.player.data.ReleaseInfo
import org.trackertv.player.ui.plural

class ParsingTest {
    @Test
    fun rutorTitle() {
        val info = ReleaseInfo.parse("Человек-паук: Новый день / Spider-Man: Brand New Day (2026) WEB-DL 1080p от ExKinoRay | D, P")
        assertEquals("Человек-паук: Новый день", info.title)
        assertEquals("Spider-Man: Brand New Day", info.original)
        assertEquals("2026", info.year)
        assertEquals(Quality.FULL_HD, info.quality)
        assertFalse(info.series)
        assertEquals("Человек-паук: Новый день 2026", info.searchQuery)
    }

    @Test
    fun rutrackerTitleWithSeriesAndHdr() {
        val info = ReleaseInfo.parse("Одни из нас / The Last of Us [S02] (2025) WEB-DL 2160p HDR10 Dolby Vision | Серии 1-7 из 7")
        assertEquals("Одни из нас", info.title)
        assertEquals(Quality.UHD, info.quality)
        assertTrue(info.hdr)
        assertTrue(info.dolbyVision)
        assertTrue(info.series)
        assertEquals(listOf("4K", "DV", "Сериал"), info.badges)
    }

    @Test
    fun audioCodecIsNotASeason() {
        // "DTS5.1" must not look like an "S5" season marker.
        assertFalse(ReleaseInfo.parse("Дюна (2021) BDRip 1080p DTS5.1").series)
    }

    @Test
    fun plainTitle() {
        val info = ReleaseInfo.parse("Домашнее видео")
        assertEquals("Домашнее видео", info.title)
        assertNull(info.year)
        assertNull(info.quality)
    }

    @Test
    fun magnetsOfTheSameTorrentShareIdentity() {
        val a = "magnet:?xt=urn:btih:36D6CDC9528B0772A0FA7A279CC86E969267FA5E&dn=rutor.info&tr=udp://opentor.net:6969"
        val b = "magnet:?xt=urn:btih:36d6cdc9528b0772a0fa7a279cc86e969267fa5e&tr=http://retracker.local/announce"
        assertEquals(LibraryItem.identityOf(a), LibraryItem.identityOf(b))
        assertEquals("https://x/y.mp4", LibraryItem.identityOf("https://x/y.mp4"))
    }

    @Test
    fun progressFinishesNearTheEnd() {
        assertTrue(Progress("k", 96_000, 100_000, 0).finished)
        assertFalse(Progress("k", 50_000, 100_000, 0).finished)
        assertFalse(Progress("k", 50_000, 0, 0).finished)
    }

    @Test
    fun russianPlurals() {
        assertEquals("1 раздача", plural(1, "раздача", "раздачи", "раздач"))
        assertEquals("3 раздачи", plural(3, "раздача", "раздачи", "раздач"))
        assertEquals("11 раздач", plural(11, "раздача", "раздачи", "раздач"))
        assertEquals("21 раздача", plural(21, "раздача", "раздачи", "раздач"))
    }
}
