package com.github.se.oncompanion.model.event

import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeEventRepositoryTest {

  private val now = LocalDateTime.of(2026, 10, 10, 12, 0)

  private fun event(id: String, start: LocalDateTime) =
      Event(
          id = id,
          title = "Event $id",
          place = "Place $id",
          startDateTime = start,
          summary = "Summary $id",
          description = "Description $id",
      )

  @Test
  fun defaultRepository_returnsTheMockupEvents_soonestFirst() = runBlocking {
    val events = FakeEventRepository().getUpcomingEvents().first()

    assertEquals(
        listOf(
            "Gentle yoga for patients",
            "Nutrition workshop",
            "Patient support group",
            "Pink October charity walk",
        ),
        events.map { it.title },
    )
  }

  @Test
  fun sampleEvents_areInTheComingDays_andHaveUniqueIds() {
    val today = LocalDate.of(2026, 10, 3)
    val events = FakeEventRepository.sampleEvents(today)

    assertTrue(events.all { it.startDateTime.toLocalDate().isAfter(today) })
    assertEquals(events.size, events.map { it.id }.toSet().size)
  }

  @Test
  fun upcomingEvents_leaveOutPastEvents_andAreSortedByDate() = runBlocking {
    val past = event("past", now.minusDays(1))
    val later = event("later", now.plusDays(5))
    val sooner = event("sooner", now.plusHours(1))
    val repository = FakeEventRepository(listOf(past, later, sooner), now = { now })

    assertEquals(listOf(sooner, later), repository.getUpcomingEvents().first())
  }

  @Test
  fun emptyRepository_returnsNoEvents() = runBlocking {
    assertTrue(FakeEventRepository(emptyList()).getUpcomingEvents().first().isEmpty())
  }

  @Test
  fun getEvent_returnsTheEvent_orNullIfMissing() = runBlocking {
    val event = event("a", now.plusDays(1))
    val repository = FakeEventRepository(listOf(event), now = { now })

    assertEquals(event, repository.getEvent("a"))
    assertNull(repository.getEvent("missing"))
  }
}
