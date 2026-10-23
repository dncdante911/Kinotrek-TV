package org.trackertv.player.net

import java.net.URL

/**
 * What a release page says about the video: only the description is taken from the tracker; the
 * app lays it out in its own style. Everything is optional, pages are written by people.
 */
data class ReleaseDetails(
    val poster: String?,
    /** "Год выхода" → "2026", "Жанр" → "фантастика, боевик", … in page order. */
    val fields: List<Pair<String, String>>,
    val synopsis: String?,
    val screenshots: List<String>,
) {
    fun field(vararg names: String): String? = fields.firstOrNull { (label, _) -> names.any { label.equals(it, ignoreCase = true) } }?.second
    val genres: List<String> get() = field("Жанр")?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val cast: String? get() = field("В ролях", "В главных ролях", "Актёры", "Актеры")
    val director: String? get() = field("Режиссёр", "Режиссер")
    val country: String? get() = field("Страна", "Производство")
    val duration: String? get() = field("Продолжительность", "Время")
    val translation: String? get() = field("Перевод", "Озвучка", "Озвучивание")
    val quality: String? get() = field("Качество", "Качество видео")
    val video: String? get() = field("Видео")
    val audio: List<String> get() = fields.filter { it.first.startsWith("Аудио", ignoreCase = true) }.map { it.second }
    val subtitles: String? get() = field("Субтитры")

    companion object {
        /** Labels that are the story text, not a short fact. */
        private val SYNOPSIS = setOf("о фильме", "описание", "о сериале", "сюжет", "о мультфильме", "об аниме", "описание фильма", "описание сериала")
        private val NOT_POSTER = Regex("imdb|kinopoisk|/flag/|smile|rating|\\.gif(\\?|$)|button|banner|userbar|logo", RegexOption.IGNORE_CASE)
        private val IMG = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        private val RUTRACKER_IMG = Regex("<var[^>]+class=[\"'][^\"']*postImg[^\"']*[\"'][^>]+title=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        private val LINE_BREAK = Regex("<br\\s*/?>|</p>|</div>|</li>", RegexOption.IGNORE_CASE)
        private val TAG = Regex("<[^>]+>")
        private val SPACES = Regex("[ \\t\\u00a0]+")
        private val FIELD_LINE = Regex("^([А-ЯЁA-Z][^:]{1,40}?)\\s*:\\s*(.*)$")

        /** Rutor: the first cell of table#details, up to the MediaInfo spoiler. */
        fun parseRutor(html: String, pageUrl: String): ReleaseDetails? {
            val start = html.indexOf("id=\"details\"").takeIf { it >= 0 } ?: return null
            // Skip the rest of the opening tag so its attributes are not read as text.
            var block = html.substring(html.indexOf('>', start) + 1)
            listOf("<div class=\"hidewrap\"", "</td></tr>").map { block.indexOf(it) }.filter { it > 0 }.minOrNull()?.let { block = block.substring(0, it) }
            return parseBlock(block, pageUrl, IMG)
        }

        /** RuTracker: the first post of the topic. */
        fun parseRutracker(html: String, pageUrl: String): ReleaseDetails? {
            val start = html.indexOf("class=\"post_body\"").takeIf { it >= 0 } ?: return null
            // Skip the rest of the opening tag so its attributes are not read as text.
            var block = html.substring(html.indexOf('>', start) + 1)
            block.indexOf("class=\"sp-wrap\"").takeIf { it > 0 }?.let { block = block.substring(0, it) }
            block.indexOf("<!--/post_body-->").takeIf { it > 0 }?.let { block = block.substring(0, it) }
            // RuTracker labels look like <span class="post-b">Жанр</span>: value
            block = block.replace(Regex("<span class=\"post-b\">([^<]+)</span>\\s*:?", RegexOption.IGNORE_CASE), "<b>$1:</b>")
            return parseBlock(block, pageUrl, RUTRACKER_IMG)
        }

        private fun parseBlock(block: String, pageUrl: String, images: Regex): ReleaseDetails {
            val pictures = images.findAll(block).map { it.groupValues[1] }.filterNot { NOT_POSTER.containsMatchIn(it) }
                .mapNotNull { absolute(pageUrl, it) }.distinct().toList()
            // The cover comes first; later pictures are screenshots (often thumbnails linked to full size).
            val poster = pictures.firstOrNull()
            val thumbnails = pictures.drop(1).take(8)

            val lines = block.split(LINE_BREAK).map { decode(it.replace(TAG, " ")).replace(SPACES, " ").trim() }
            val fields = mutableListOf<Pair<String, String>>()
            var synopsis: String? = null
            var collectingStory = false
            val story = StringBuilder()
            for (line in lines) {
                if (line.isEmpty()) {
                    if (collectingStory && story.isNotEmpty()) collectingStory = false
                    continue
                }
                val match = FIELD_LINE.find(line)
                val label = match?.groupValues?.get(1)?.trim()
                when {
                    label != null && label.lowercase() in SYNOPSIS -> {
                        story.clear()
                        match.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { story.append(it) }
                        collectingStory = true
                    }
                    collectingStory && label == null -> story.append(if (story.isEmpty()) "" else " ").append(line)
                    label != null && match.groupValues[2].isNotBlank() -> {
                        collectingStory = false
                        fields += label to match.groupValues[2].replace(Regex("\\s+,"), ",").trim().trimEnd('|', ' ')
                    }
                }
                if (!collectingStory && story.isNotEmpty() && synopsis == null) synopsis = story.toString()
            }
            if (synopsis == null && story.isNotEmpty()) synopsis = story.toString()
            return ReleaseDetails(poster, fields.distinctBy { it.first + it.second }, synopsis?.take(2_000), thumbnails)
        }

        private fun absolute(base: String, path: String): String? = runCatching {
            val url = URL(URL(base), path.replace("&amp;", "&")).toString()
            if (url.startsWith("http://") || url.startsWith("https://")) url else null
        }.getOrNull()

        fun decode(text: String): String = text
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&laquo;", "«").replace("&raquo;", "»")
            .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
            .replace(Regex("&#(\\d+);")) { runCatching { it.groupValues[1].toInt().toChar().toString() }.getOrDefault("") }
            .replace("&amp;", "&")
    }
}
