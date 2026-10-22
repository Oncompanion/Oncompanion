// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeEventRegistrationRepositoryTest {

  private val repository = FakeEventRegistrationRepository()

  @Test
  fun nothingIsConfirmed_atFirst() = runBlocking {
    assertFalse(repository.observeIsRegistered("sam", "yoga").first())
  }

  @Test
  fun confirm_isObserved_andKeptOnce() = runBlocking {
    repository.confirm("sam", "yoga")
    repository.confirm("sam", "yoga")

    assertTrue(repository.observeIsRegistered("sam", "yoga").first())
    assertEquals(setOf("yoga"), repository.registeredEventIds("sam"))
  }

  @Test
  fun remove_isObserved_andLeavesOtherConfirmations() = runBlocking {
    repository.confirm("sam", "yoga")
    repository.confirm("sam", "walk")

    repository.remove("sam", "yoga")

    assertFalse(repository.observeIsRegistered("sam", "yoga").first())
    assertEquals(setOf("walk"), repository.registeredEventIds("sam"))
  }

  @Test
  fun confirmations_areSeparatedByUser() = runBlocking {
    repository.confirm("sam", "yoga")

    assertFalse(repository.observeIsRegistered("alex", "yoga").first())
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
      runBlocking { repository.observeIsRegistered("sam", "yoga").first() }
    }
  }

  @Test
  fun invalidIds_areRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      repository.observeIsRegistered("sam", "a/b")
    }
    assertThrows(IllegalArgumentException::class.java) { repository.confirm(" ", "yoga") }
  }
}
