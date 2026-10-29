// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.session

import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.se.oncompanion.domain.auth.CleanupJournal

/** Synchronous durable recovery flag, written before authentication or cache destruction. */
class PreferencesCleanupJournal(private val preferences: SharedPreferences) : CleanupJournal {
  override val pending: Boolean
    get() = preferences.getBoolean("cleanup_pending", false)

  override fun setPending(pending: Boolean) {
    preferences.edit {
      putBoolean("cleanup_pending", pending)
      // KTX returns Unit: check the synchronous commit here before its final no-op apply.
      check(commit()) { "Could not persist the local cleanup state" }
    }
  }
}
