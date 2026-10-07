package com.github.se.oncompanion.ui.planning

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Shared numeric date contract; uuuu enables strict leap-year validation. */
object PlanningDates {
  fun isSupported(date: LocalDate): Boolean = date.year in 1..9999

  private val formatter =
      DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

  fun format(date: LocalDate): String {
    require(isSupported(date)) { "Date must have a four-digit positive year" }
    return date.format(formatter)
  }

  fun parse(text: String): LocalDate {
    require(text.matches(Regex("[0-9]{2}/[0-9]{2}/[0-9]{4}"))) { "Date must use dd/MM/yyyy" }
    return LocalDate.parse(text, formatter).also {
      require(it.year in 1..9999) { "Date must have a four-digit positive year" }
    }
  }
}
