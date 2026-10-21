// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the recovery flag across journal reconstruction. */
@RunWith(AndroidJUnit4::class)
class PreferencesCleanupJournalTest {
  /** Interrupted cleanup remains pending across journal reconstruction. */
  @Test
  fun pendingCleanupSurvivesReconstructionUntilSuccess() {
    val preferences =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("cleanup-journal-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    val first = PreferencesCleanupJournal(preferences)
    assertFalse(first.pending)
    first.setPending(true)
    val restarted = PreferencesCleanupJournal(preferences)
    assertTrue(restarted.pending)
    restarted.setPending(false)
    val complete = PreferencesCleanupJournal(preferences)
    assertFalse(complete.pending)
  }
}
