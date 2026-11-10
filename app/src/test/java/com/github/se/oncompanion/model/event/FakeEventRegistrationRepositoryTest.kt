// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeEventRegistrationRepositoryTest {

  private val repository = FakeEventRegistrationRepository()

  @Test
  fun nothingIsConfirmed_atFirst() = runBlocking {
    assertNull(repository.observeRegistration("sam", "yoga").first())
  }

  @Test
  fun confirm_isObserved_andKeptOnce() = runBlocking {
    repository.confirm("sam", "yoga")
    repository.confirm("sam", "yoga")

    assertNotNull(repository.observeRegistration("sam", "yoga").first())
    assertEquals(setOf("yoga"), repository.registeredEventIds("sam"))
  }

  @Test
  fun remove_isObserved_andLeavesOtherConfirmations() = runBlocking {
    repository.confirm("sam", "yoga")
    repository.confirm("sam", "walk")

    repository.remove("sam", "yoga")

    assertNull(repository.observeRegistration("sam", "yoga").first())
    assertEquals(setOf("walk"), repository.registeredEventIds("sam"))
  }

  @Test
  fun confirmations_areSeparatedByUser() = runBlocking {
    repository.confirm("sam", "yoga")

    assertNull(repository.observeRegistration("alex", "yoga").first())
  }

  @Test
  fun writeError_failsWithoutChangingAnything() {
    repository.writeError = IOException("offline")

    assertThrows(IOException::class.java) { repository.confirm("sam", "yoga") }
    assertTrue(repository.registeredEventIds("sam").isEmpty())
  }

  @Test
  fun observeError_failsTheFlow() {
    repository.observeError = IOException("offline")

    assertThrows(IOException::class.java) {
      runBlocking { repository.observeRegistration("sam", "yoga").first() }
    }
  }

  @Test
  fun invalidIds_areRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      repository.observeRegistration("sam", "a/b")
    }
    assertThrows(IllegalArgumentException::class.java) { repository.confirm(" ", "yoga") }
  }
}
