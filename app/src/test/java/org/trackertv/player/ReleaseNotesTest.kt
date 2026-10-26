package org.trackertv.player

import org.junit.Assert.assertEquals
import org.junit.Test
import org.trackertv.player.data.ReleaseNotes
import org.trackertv.player.data.ReleaseNotes.Kind

class ReleaseNotesTest {
    @Test
    fun markdownBecomesPlainLines() {
        val notes = """
            Первая строка с **жирным** и [ссылкой](https://example.org).

            ### Раздел
            - Пункт
              - Подпункт
            • Старый пункт,
              перенесённый на вторую строку.

            **Full Changelog**: https://github.com/x/y/compare/1.7.0...1.8.0
        """.trimIndent()
        assertEquals(
            listOf(
                ReleaseNotes.Line(Kind.TEXT, "Первая строка с жирным и ссылкой."),
                ReleaseNotes.Line(Kind.GAP, ""),
                ReleaseNotes.Line(Kind.HEADING, "Раздел"),
                ReleaseNotes.Line(Kind.BULLET, "Пункт"),
                ReleaseNotes.Line(Kind.SUB_BULLET, "Подпункт"),
                ReleaseNotes.Line(Kind.BULLET, "Старый пункт, перенесённый на вторую строку."),
            ),
            ReleaseNotes.lines(notes),
        )
        assertEquals("Первая строка с жирным и ссылкой.", ReleaseNotes.summary(notes))
    }
}
