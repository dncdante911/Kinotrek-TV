package org.trackertv.player.data

/**
 * Release notes from GitHub are Markdown; a TV shows them as plain lines: headings, bullets and
 * text, without asterisks, link syntax or the "Full Changelog" line that only makes sense on the site.
 */
object ReleaseNotes {
    enum class Kind { HEADING, BULLET, SUB_BULLET, TEXT, GAP }

    data class Line(val kind: Kind, val text: String)

    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val EMPHASIS = Regex("(\\*\\*|__|`)")
    private val BULLET = Regex("^(\\s*)([-*+•]|\\d+[.)])\\s+(.*)$")
    private val URL_ONLY = Regex("^<?https?://\\S+>?$")

    fun lines(markdown: String): List<Line> {
        val result = ArrayList<Line>()
        for (raw in markdown.replace("\r", "").lines()) {
            if (raw.contains("Full Changelog", ignoreCase = true) || URL_ONLY.matches(raw.trim())) continue
            if (raw.isBlank()) {
                if (result.isNotEmpty() && result.last().kind != Kind.GAP) result += Line(Kind.GAP, "")
                continue
            }
            val bullet = BULLET.find(raw)
            result += when {
                raw.trimStart().startsWith("#") -> Line(Kind.HEADING, clean(raw.trimStart().trimStart('#')))
                bullet != null && bullet.groupValues[1].length >= 2 -> Line(Kind.SUB_BULLET, clean(bullet.groupValues[3]))
                bullet != null -> Line(Kind.BULLET, clean(bullet.groupValues[3]))
                // A wrapped line of the bullet above (old notes used hard line breaks).
                raw.startsWith("  ") && result.lastOrNull()?.kind in setOf(Kind.BULLET, Kind.SUB_BULLET) -> {
                    val last = result.removeAt(result.lastIndex)
                    last.copy(text = last.text + " " + clean(raw))
                }
                else -> Line(Kind.TEXT, clean(raw))
            }
        }
        while (result.lastOrNull()?.kind == Kind.GAP) result.removeAt(result.lastIndex)
        return result
    }

    /** The first meaningful line, for a one-line summary. */
    fun summary(markdown: String): String? = lines(markdown).firstOrNull { it.kind != Kind.GAP }?.text

    private fun clean(text: String) = text.replace(LINK, "$1").replace(EMPHASIS, "").trim()
}
