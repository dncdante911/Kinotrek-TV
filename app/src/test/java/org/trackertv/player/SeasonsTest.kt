package org.trackertv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.trackertv.player.data.DecorMode
import org.trackertv.player.data.Occasion
import org.trackertv.player.data.Seasons
import java.time.LocalDate

class SeasonsTest {
    private fun on(year: Int, month: Int, day: Int, mode: DecorMode = DecorMode.AUTO) = Seasons.occasion(LocalDate.of(year, month, day), mode)

    @Test
    fun orthodoxEaster() {
        assertEquals(LocalDate.of(2024, 5, 5), Seasons.orthodoxEaster(2024))
        assertEquals(LocalDate.of(2025, 4, 20), Seasons.orthodoxEaster(2025))
        assertEquals(LocalDate.of(2026, 4, 12), Seasons.orthodoxEaster(2026))
        assertEquals(LocalDate.of(2027, 5, 2), Seasons.orthodoxEaster(2027))
    }

    @Test
    fun holidayWindowsAreAWeekEachSide() {
        assertEquals(Occasion.WINTER, on(2026, 12, 17))
        assertEquals(Occasion.NEW_YEAR, on(2026, 12, 18))
        assertEquals(Occasion.NEW_YEAR, on(2027, 1, 8))
        assertEquals(Occasion.WINTER, on(2027, 1, 9))
        assertEquals(Occasion.EASTER, on(2026, 4, 5))
        assertEquals(Occasion.EASTER, on(2026, 4, 19))
        assertEquals(Occasion.SPRING, on(2026, 4, 20))
        assertEquals(Occasion.VALENTINE, on(2026, 2, 7))
        assertEquals(Occasion.INDEPENDENCE, on(2026, 8, 31))
        assertEquals(Occasion.AUTUMN, on(2026, 10, 23))
        assertEquals(Occasion.HALLOWEEN, on(2026, 10, 24))
        assertEquals(Occasion.HALLOWEEN, on(2026, 11, 7))
    }

    @Test
    fun seasonsChangeEveryThreeMonths() {
        assertEquals(Occasion.SPRING, on(2026, 3, 1))
        assertEquals(Occasion.SUMMER, on(2026, 6, 1))
        assertEquals(Occasion.AUTUMN, on(2026, 9, 1))
        assertEquals(Occasion.WINTER, on(2026, 2, 28))
    }

    @Test
    fun modes() {
        assertNull(on(2026, 3, 1, DecorMode.HOLIDAYS))
        assertEquals(Occasion.HALLOWEEN, on(2026, 10, 30, DecorMode.HOLIDAYS))
        assertNull(on(2026, 12, 31, DecorMode.OFF))
    }
}
