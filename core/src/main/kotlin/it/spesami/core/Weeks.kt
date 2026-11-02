package it.spesami.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object Weeks {
    private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", Locale.ITALIAN)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)

    fun startOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    /** "Lunedì 2 novembre" */
    fun dayLabel(weekStart: LocalDate, day: Int): String =
        weekStart.plusDays(day.toLong()).format(dayFormat).replaceFirstChar { it.titlecase(Locale.ITALIAN) }

    /** "2 – 8 novembre 2026", "28 ottobre – 3 novembre 2026", "29 dicembre 2025 – 4 gennaio 2026" */
    fun rangeLabel(weekStart: LocalDate): String {
        val end = weekStart.plusDays(6)
        return when {
            weekStart.year != end.year -> "${weekStart.format(dayMonthYear)} – ${end.format(dayMonthYear)}"
            weekStart.month != end.month -> "${weekStart.format(dayMonth)} – ${end.format(dayMonthYear)}"
            else -> "${weekStart.dayOfMonth} – ${end.format(dayMonthYear)}"
        }
    }
}
