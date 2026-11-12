// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The phone's record of the accepted prescriptions a caregiver closed (US-43). */
@RunWith(AndroidJUnit4::class)
class SeenPrescriptionsPreferencesTest {

  private val preferences =
      ApplicationProvider.getApplicationContext<Context>()
          .getSharedPreferences("seen_prescriptions_test", Context.MODE_PRIVATE)
          .also { it.edit().clear().commit() }

  @Test
  fun closedPrescriptions_areKeptPerAccount_andStayAfterTheAppRestarts(): Unit = runBlocking {
    val seen = SeenPrescriptionsPreferences(preferences)
    seen.markSeen("sam", "anne", "p1")
    seen.markSeen("sam", "paul", "p1")
    seen.markSeen("lea", "anne", "p2")

    assertEquals(setOf("anne/p1", "paul/p1"), seen.observe("sam").first())
    // Another account sees only its own, also once the app is restarted
    val restarted = SeenPrescriptionsPreferences(preferences)
    assertEquals(setOf("anne/p2"), restarted.observe("lea").first())
    assertEquals(emptySet<String>(), restarted.observe("bob").first())
  }
}
