package org.trackertv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.trackertv.player.data.Misfit
import org.trackertv.player.data.ReleaseInfo
import org.trackertv.player.data.Rip
import org.trackertv.player.data.StreamFit
import org.trackertv.player.data.StreamProfile

class StreamFitTest {
    private fun check(title: String, size: String, seeds: Int, profile: StreamProfile, line: Double? = null): Misfit? =
        StreamFit.check(ReleaseInfo.parse(title), StreamFit.sizeBytes(size), seeds, profile, line)

    @Test
    fun ripTypes() {
        assertEquals(Rip.WEB_DL, ReleaseInfo.parse("Дюна (2021) WEB-DL 1080p").rip)
        assertEquals(Rip.WEBRIP, ReleaseInfo.parse("Дюна (2021) WEBRip 720p").rip)
        assertEquals(Rip.REMUX, ReleaseInfo.parse("Дюна / Dune [2021, BDRemux 2160p, HDR10]").rip)
        assertEquals(Rip.BDRIP, ReleaseInfo.parse("Дюна (2021) BDRip 1080p HEVC").rip)
        assertEquals(Rip.DISC, ReleaseInfo.parse("Дюна / Dune [2021, Blu-ray disc 2160p]").rip)
        assertNull(ReleaseInfo.parse("Домашнее видео").rip)
    }

    @Test
    fun episodeCount() {
        assertEquals(7, ReleaseInfo.parse("Одни из нас [S02] (2025) WEB-DL 1080p | Серии 1-7 из 7").episodes)
        assertEquals(8, ReleaseInfo.parse("Сериал / Show [01-08 из 10] (2024) WEB-DL 1080p").episodes)
        assertEquals(10, ReleaseInfo.parse("Show S01E01-10 (2024) WEB-DL 720p").episodes)
        assertNull(ReleaseInfo.parse("Сериал (2019-2024) WEB-DL 1080p | Сезон 1").episodes)
        assertNull(ReleaseInfo.parse("Фильм (2021) WEB-DL 1080p").episodes)
    }

    @Test
    fun sizes() {
        assertEquals(1_567_663_063L, StreamFit.sizeBytes("1.46 GB"))
        assertEquals(700L shl 20, StreamFit.sizeBytes("700 MB"))
        assertEquals((1.5 * (1L shl 30)).toLong(), StreamFit.sizeBytes("1,5 ГБ"))
        assertNull(StreamFit.sizeBytes(""))
    }

    @Test
    fun economyKeepsLightReleases() {
        assertNull(check("Дюна (2021) WEB-DL 1080p", "3.9 GB", 40, StreamProfile.ECONOMY))
        assertNull(check("Дюна (2021) BDRip 1080p HEVC", "2.6 GB", 12, StreamProfile.ECONOMY))
        assertNull(check("Одни из нас [S02] (2025) WEB-DL 1080p | Серии 1-7 из 7", "9.8 GB", 30, StreamProfile.ECONOMY))
    }

    @Test
    fun economyDropsHeavyReleases() {
        assertEquals(Misfit.REMUX, check("Дюна [2021, BDRemux 1080p]", "32 GB", 40, StreamProfile.ECONOMY))
        assertEquals(Misfit.QUALITY, check("Дюна (2021) WEB-DL 2160p HDR", "18 GB", 40, StreamProfile.ECONOMY))
        assertEquals(Misfit.CODEC, check("Дюна (2021) BDRip 1080p", "2.2 GB", 40, StreamProfile.ECONOMY))
        assertEquals(Misfit.TOO_BIG, check("Дюна (2021) WEB-DL 1080p", "7.5 GB", 40, StreamProfile.ECONOMY))
        // 4.5 GB over 110 minutes is ~5.9 Mbit/s: fine on its own, too much for a 6 Mbit/s line.
        assertNull(check("Дюна (2021) WEB-DL 1080p", "4.5 GB", 40, StreamProfile.ECONOMY))
        assertEquals(Misfit.HEAVY, check("Дюна (2021) WEB-DL 1080p", "4.5 GB", 40, StreamProfile.ECONOMY, line = 6.0))
    }

    @Test
    fun weakSeedsAreHiddenEverywhere() {
        assertEquals(Misfit.WEAK, check("Дюна (2021) WEB-DL 1080p", "3.9 GB", 3, StreamProfile.MAXIMUM))
        assertEquals(Misfit.WEAK, check("Дюна [2021, BDRemux 2160p]", "80 GB", 7, StreamProfile.MAXIMUM))
        assertNull(check("Дюна [2021, BDRemux 2160p]", "80 GB", 15, StreamProfile.MAXIMUM))
    }

    @Test
    fun balancedFollowsTheLine() {
        assertEquals(Misfit.REMUX, check("Дюна [2021, BDRemux 2160p]", "80 GB", 50, StreamProfile.BALANCED))
        assertNull(check("Дюна (2021) WEB-DL 2160p", "15 GB", 50, StreamProfile.BALANCED))
        assertNull(check("Дюна (2021) WEB-DL 2160p", "15 GB", 50, StreamProfile.BALANCED, line = 40.0))
        assertEquals(Misfit.HEAVY, check("Дюна (2021) WEB-DL 2160p", "15 GB", 50, StreamProfile.BALANCED, line = 20.0))
    }

    @Test
    fun autoProfile() {
        assertEquals(StreamProfile.ECONOMY, StreamFit.auto(metered = true, lineMbps = 300.0))
        assertEquals(StreamProfile.ECONOMY, StreamFit.auto(metered = false, lineMbps = 8.0))
        assertEquals(StreamProfile.BALANCED, StreamFit.auto(metered = false, lineMbps = 50.0))
        assertEquals(StreamProfile.BALANCED, StreamFit.auto(metered = false, lineMbps = null))
        assertEquals(StreamProfile.MAXIMUM, StreamFit.auto(metered = false, lineMbps = 250.0))
        assertEquals("4,3 Мбит/с", StreamFit.mbpsLabel(4.3))
        assertEquals("47 Мбит/с", StreamFit.mbpsLabel(47.6))
    }
}
