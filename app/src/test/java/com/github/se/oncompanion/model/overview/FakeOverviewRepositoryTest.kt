package com.github.se.oncompanion.model.overview

import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeOverviewRepositoryTest {

  private val today = LocalDate.of(2026, 10, 3)

  @Test
  fun defaultRepository_isEmpty() = runBlocking {
    val repository = FakeOverviewRepository()

    assertNull(repository.observeNextAppointment().first())
    assertTrue(repository.observeTodayItems().first().isEmpty())
  }

  @Test
  fun sampleData_hasTheMockupAppointmentTomorrow() = runBlocking {
    val appointment = FakeOverviewRepository.withSampleData(today).observeNextAppointment().first()

    assertEquals("Oncology check-up", appointment?.title)
    assertEquals(LocalDateTime.of(2026, 10, 4, 9, 30), appointment?.dateTime)
  }

  @Test
  fun sampleData_hasThreeItemsToday_withUniqueIds() = runBlocking {
    val items = FakeOverviewRepository.withSampleData(today).observeTodayItems().first()

    assertEquals(3, items.size)
    assertTrue(items.all { it.time.toLocalDate() == today })
    assertEquals(items.size, items.map { it.id }.toSet().size)
  }

  @Test
  fun changingTheData_emitsTheNewValues() = runBlocking {
    val repository = FakeOverviewRepository()
    val item =
        TodayItem(
            id = "a",
            kind = TodayItemKind.APPOINTMENT,
            time = LocalDateTime.of(2026, 10, 3, 15, 0),
            title = "Blood test",
        )

    repository.todayItems.value = listOf(item)

    assertEquals(listOf(item), repository.observeTodayItems().first())
  }
}
