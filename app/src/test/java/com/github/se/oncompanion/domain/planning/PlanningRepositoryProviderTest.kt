// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.planning.FakePlanningRepository
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what [PlanningRepositoryProvider] exposes by default and that it can be replaced. */
class PlanningRepositoryProviderTest {
  private val original = PlanningRepositoryProvider.repository

  @After
  fun restoreProvider() {
    PlanningRepositoryProvider.repository = original
  }

  /**
   * Planning shows the patient's appointments unless told otherwise; building it needs no Firebase.
   */
  @Test
  fun exposesTheAppRepositoryByDefault() {
    assertTrue(original is AppointmentPlanningSource)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakePlanningRepository()
    PlanningRepositoryProvider.repository = fake
    assertSame(fake, PlanningRepositoryProvider.repository)
  }
}
