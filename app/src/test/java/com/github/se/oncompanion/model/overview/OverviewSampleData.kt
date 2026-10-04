package com.github.se.oncompanion.model.overview

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The data of the "US-23 · Overview dashboard (proposal)" Figma mockup, around [today]. Test
 * sources only: invented medications must never reach a patient's screen.
 */
object OverviewSampleData {

  fun repository(today: LocalDate): FakeOverviewRepository {
    fun at(date: LocalDate, hour: Int, minute: Int = 0) =
        LocalDateTime.of(date, LocalTime.of(hour, minute))

    return FakeOverviewRepository(
        nextAppointment =
            NextAppointment(
                title = "Oncology check-up",
                dateTime = at(today.plusDays(1), 9, 30),
                doctor = "Dr. Martin",
                place = "HUG, Oncology day unit",
            ),
        todayItems =
            listOf(
                TodayItem(
                    id = "ondansetron-morning",
                    kind = TodayItemKind.MEDICATION,
                    time = at(today, 8),
                    title = "Ondansetron 8 mg",
                    subtitle = "1 tablet · with breakfast",
                    isTaken = true,
                ),
                TodayItem(
                    id = "gentle-yoga",
                    kind = TodayItemKind.EVENT,
                    time = at(today, 10),
                    endTime = at(today, 11, 30),
                    title = "Gentle yoga for patients",
                    subtitle = "Event · Wellness center",
                ),
                TodayItem(
                    id = "dexamethasone-lunch",
                    kind = TodayItemKind.MEDICATION,
                    time = at(today, 13),
                    title = "Dexamethasone 4 mg",
                    subtitle = "1 tablet · with lunch",
                ),
            ),
    )
  }
}
