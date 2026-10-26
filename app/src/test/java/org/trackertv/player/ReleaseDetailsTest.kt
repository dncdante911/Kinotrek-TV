package org.trackertv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.trackertv.player.net.ReleaseDetails

/** A made-up page with the same markup rutor uses for releases. */
class ReleaseDetailsTest {
    private val rutor = """
        <html><body><table id="details">
        <tr><td style="vertical-align:top;"></td><td><br /><img src="http://img.example.org/big/poster.jpg" style="float:right;" /><br />
        <br />
        <u>Информация о фильме</u><br />
        <b>Название:</b> Тестовый фильм<br />
        <b>Оригинальное название:</b> Test Movie<br />
        <b>Год выхода:</b> 2026<br />
        <b>Жанр:</b>  <a href="/tag/1/драма">драма</a>, <a href="/tag/1/комедия">комедия</a><br />
        <b>Режиссёр:</b> Иван Петров<br />
        <b>В ролях:</b> Анна Смирнова, Олег Иванов<br />
        <br />
         <a href="http://www.imdb.com/title/tt1/"><img src="http://s.rutor.info/imdb/pic/1.gif" /></a><br />
        <br />
        <b>О фильме:</b><br />
        Двое друзей отправляются в путешествие и находят то, что искали всю жизнь &mdash; друг друга.<br />
        <br />
        <b>Страна: </b>Россия<br />
        <b>Продолжительность: </b>01:45:00<br />
        <font size="3"><b>Перевод: </b>Оригинал</font><br />
        <br />
        <b>Качество: </b>WEB-DL 1080p<br />
        <b>Видео: </b>1920x1080 at 24 fps, AVC<br />
        <b>Аудио #01: </b>48 kHz, AC3, 5.1, 384 Kbps | RUS |<img src="https://static.example.org/flag/RU.png" /><br />
        <b>Субтитры: </b>нет<br />
        <br />
        <a href="http://img.example.org/big/s1.png"><img src="http://img.example.org/thumb/s1.jpg" /></a> <a href="http://img.example.org/big/s2.png"><img src="http://img.example.org/thumb/s2.jpg" /></a><br />
        <div class="hidewrap"><div class="hidehead">MI</div><textarea>General<br />Format : Matroska</textarea></div>
        </td></tr></table></body></html>
    """.trimIndent()

    @Test
    fun rutorPageIsParsed() {
        val d = ReleaseDetails.parseRutor(rutor, "https://rutor.info/torrent/1/test")!!
        assertEquals("http://img.example.org/big/poster.jpg", d.poster)
        assertEquals("2026", d.field("Год выхода"))
        assertEquals(listOf("драма", "комедия"), d.genres)
        assertEquals("Иван Петров", d.director)
        assertEquals("Анна Смирнова, Олег Иванов", d.cast)
        assertEquals("Россия", d.country)
        assertEquals("01:45:00", d.duration)
        assertEquals("WEB-DL 1080p", d.quality)
        assertEquals(1, d.audio.size)
        assertTrue(d.synopsis!!.startsWith("Двое друзей"))
        assertTrue(d.synopsis!!.contains("— друг друга"))
        // Thumbnails linked to the full picture give the full picture, sharp enough for a backdrop.
        assertEquals(listOf("http://img.example.org/big/s1.png", "http://img.example.org/big/s2.png"), d.screenshots)
        assertNull(d.trailer)
        // MediaInfo spoiler is not part of the description.
        assertNull(d.field("Format"))
    }

    @Test
    fun rutrackerPostIsParsed() {
        val html = """
            <div class="post_body" id="p-1">
            <var class="postImg postImgAligned img-right" title="https://img.example.org/cover.jpg">&#10;</var>
            <span class="post-b">Год выпуска</span>: 2025<br>
            <span class="post-b">Жанр</span>: триллер<br>
            <span class="post-b">Описание</span>: Короткий сюжет о главном герое.<br>
            <span class="post-b">Качество видео</span>: BDRip 1080p<br>
            </div><!--/post_body-->
        """.trimIndent()
        val d = ReleaseDetails.parseRutracker(html, "https://rutracker.org/forum/viewtopic.php?t=1")!!
        assertEquals("https://img.example.org/cover.jpg", d.poster)
        assertEquals("2025", d.field("Год выпуска"))
        assertEquals(listOf("триллер"), d.genres)
        assertEquals("Короткий сюжет о главном герое.", d.synopsis)
        assertEquals("BDRip 1080p", d.quality)
    }

    @Test
    fun pageWithoutDescriptionGivesNull() {
        assertNull(ReleaseDetails.parseRutor("<html>no details</html>", "https://rutor.info/torrent/2"))
    }

    @Test
    fun trailerFromEmbeddedVideo() {
        val page = rutor.replace(
            "</textarea></div>",
            "</textarea></div><div class=\"hidewrap\"><div class=\"hidehead\">Трейлер</div>" +
                "<iframe src=\"https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0\"></iframe></div>",
        )
        assertEquals("dQw4w9WgXcQ", ReleaseDetails.parseRutor(page, "http://rutor.info/torrent/1")?.trailer)
        val link = rutor.replace("<b>Субтитры: </b>нет<br />", "<b>Субтитры: </b>нет<br /><a href=\"https://youtu.be/abcDEF12345\">Трейлер</a><br />")
        assertEquals("abcDEF12345", ReleaseDetails.parseRutor(link, "http://rutor.info/torrent/1")?.trailer)
    }
}
