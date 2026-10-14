// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.appointment.FakeAppointmentRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Checks what [PlanningRepositoryProvider] exposes by default and that it can be replaced. */
class PlanningRepositoryProviderTest {
  private val original = PlanningRepositoryProvider.repository
  private val appAppointmentRepository = AppointmentRepositoryProvider.repository
  private val appAuthRepository = AuthRepositoryProvider.repository

  @After
  fun restoreProviders() {
    PlanningRepositoryProvider.repository = original
    AppointmentRepositoryProvider.repository = appAppointmentRepository
    AuthRepositoryProvider.repository = appAuthRepository
  }

  /**
   * Planning shows the signed-in patient's appointments from the appointment and auth providers,
   * even when they were replaced after the planning provider was first used.
   */
  @Test
  fun showsTheAppointmentsOfTheAppointmentAndAuthProvidersByDefault() = runTest {
    val day = LocalDate.of(2026, 10, 25)
    val range = PlanningRange(day, day.plusDays(1), ZoneId.of("Europe/Zurich"))
    val auth = FakeAuthRepository { AuthUser(it) }
    auth.signInWithGoogle("patient")
    val appointments = FakeAppointmentRepository()
    appointments.seed(
        "patient",
        Appointment(
            "saved",
            "Saved consultation",
            range.startInclusive,
            AppointmentType.CONSULTATION,
        ),
    )
    AuthRepositoryProvider.repository = auth
    AppointmentRepositoryProvider.repository = appointments

    val items = original.observeItems(range).first { it.isNotEmpty() }

    assertEquals(listOf("Saved consultation"), items.map { it.title })
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakePlanningRepository()
    PlanningRepositoryProvider.repository = fake
    assertSame(fake, PlanningRepositoryProvider.repository)
  }
}
