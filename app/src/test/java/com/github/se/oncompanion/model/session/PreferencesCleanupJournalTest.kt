// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.session

import android.content.Context
import android.content.SharedPreferences
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

  @Test
  fun failedDurableCommitThrowsWithoutApplyingTheRecoveryFlag() {
    val actual =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("failed-cleanup-journal", Context.MODE_PRIVATE)
    var applied = false
    val preferences =
        object : SharedPreferences by actual {
          override fun edit(): SharedPreferences.Editor {
            val editor = actual.edit()
            return object : SharedPreferences.Editor by editor {
              override fun commit() = false

              override fun apply() {
                applied = true
              }
            }
          }
        }
    org.junit.Assert.assertThrows(IllegalStateException::class.java) {
      PreferencesCleanupJournal(preferences).setPending(true)
    }
    assertFalse(applied)
    assertFalse(PreferencesCleanupJournal(actual).pending)
  }
}
