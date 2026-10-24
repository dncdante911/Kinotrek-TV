package org.trackertv.player.data

import java.time.LocalDate
import java.time.MonthDay

/** What the seasonal decoration shows; holidays take over from the season they fall in. */
enum class Occasion(val label: String, val holiday: Boolean) {
    WINTER("Зима", false),
    SPRING("Весна", false),
    SUMMER("Лето", false),
    AUTUMN("Осень", false),
    NEW_YEAR("Новый год и Рождество", true),
    EASTER("Пасха", true),
    VALENTINE("День святого Валентина", true),
    INDEPENDENCE("День Независимости", true),
    HALLOWEEN("Хэллоуин", true),
}

enum class DecorMode(val label: String) {
    AUTO("Авто"),
    HOLIDAYS("Только праздники"),
    OFF("Выключено"),
}

object Seasons {
    /** Holidays are decorated a week before and a week after the day. */
    private const val WINDOW_DAYS = 7L

    // Christmas (25 December in Ukraine since 2023) and New Year share one window.
    private val NEW_YEAR_FROM = MonthDay.of(12, 25).minusDays(WINDOW_DAYS)
    private val NEW_YEAR_TO = MonthDay.of(1, 1).plusDays(WINDOW_DAYS)

    fun occasion(date: LocalDate, mode: DecorMode): Occasion? = when (mode) {
        DecorMode.OFF -> null
        DecorMode.HOLIDAYS -> holiday(date)
        DecorMode.AUTO -> holiday(date) ?: season(date)
    }

    fun holiday(date: LocalDate): Occasion? {
        val day = MonthDay.from(date)
        if (!day.isBefore(NEW_YEAR_FROM) || !day.isAfter(NEW_YEAR_TO)) return Occasion.NEW_YEAR
        val year = date.year
        return listOf(
            orthodoxEaster(year) to Occasion.EASTER,
            LocalDate.of(year, 2, 14) to Occasion.VALENTINE,
            LocalDate.of(year, 8, 24) to Occasion.INDEPENDENCE,
            LocalDate.of(year, 10, 31) to Occasion.HALLOWEEN,
        ).firstOrNull { (holiday, _) -> !date.isBefore(holiday.minusDays(WINDOW_DAYS)) && !date.isAfter(holiday.plusDays(WINDOW_DAYS)) }?.second
    }

    fun season(date: LocalDate): Occasion = when (date.monthValue) {
        12, 1, 2 -> Occasion.WINTER
        in 3..5 -> Occasion.SPRING
        in 6..8 -> Occasion.SUMMER
        else -> Occasion.AUTUMN
    }

    /** Meeus' Julian algorithm, moved to the Gregorian calendar (+13 days, valid 1900–2099). */
    fun orthodoxEaster(year: Int): LocalDate {
        val a = year % 4
        val b = year % 7
        val c = year % 19
        val d = (19 * c + 15) % 30
        val e = (2 * a + 4 * b - d + 34) % 7
        val month = (d + e + 114) / 31
        val day = (d + e + 114) % 31 + 1
        return LocalDate.of(year, month, day).plusDays(13)
    }

    private fun MonthDay.minusDays(days: Long): MonthDay = MonthDay.from(atYear(2001).minusDays(days))
    private fun MonthDay.plusDays(days: Long): MonthDay = MonthDay.from(atYear(2001).plusDays(days))
}
