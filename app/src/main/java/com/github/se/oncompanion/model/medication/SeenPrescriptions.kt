// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The prescriptions a caregiver added whose acceptance by the patient they closed (US-43), so
 * "Accepted by Anne" no longer shows. Kept on the phone only, as decided for #396: it works offline
 * and the patient's prescriptions stay theirs to write.
 */
interface SeenPrescriptions {

  /** Emits the prescriptions [caregiverUid] closed, as [key]s, now and every time they change. */
  fun observe(caregiverUid: String): Flow<Set<String>>

  /** [caregiverUid] closed the acceptance of prescription [id] of [patientUid]. */
  fun markSeen(caregiverUid: String, patientUid: String, id: String)

  companion object {
    /** How a prescription is named in [observe]: its ID with its patient's, as IDs are per user. */
    fun key(patientUid: String, id: String) = "$patientUid/$id"
  }
}

/**
 * [SeenPrescriptions] in the phone's [preferences]. Each account has its own entry, so the next
 * account to sign in sees none of it; it only holds record IDs, which identify nothing outside the
 * app.
 */
class SeenPrescriptionsPreferences(private val preferences: SharedPreferences) : SeenPrescriptions {

  // Changes since the app started, so every screen reading it sees a new one at once
  private val changes = MutableStateFlow(0)

  override fun observe(caregiverUid: String): Flow<Set<String>> = changes.map {
    preferences.getStringSet(caregiverUid, null).orEmpty().toSet()
  }

  override fun markSeen(caregiverUid: String, patientUid: String, id: String) {
    val seen = preferences.getStringSet(caregiverUid, null).orEmpty()
    preferences.edit { putStringSet(caregiverUid, seen + SeenPrescriptions.key(patientUid, id)) }
    changes.update { it + 1 }
  }
}

/**
 * [SeenPrescriptions] in memory, until [MainActivity][com.github.se.oncompanion.MainActivity]
 * installs the phone's preferences. Tests use it as it is.
 */
class SeenPrescriptionsInMemory : SeenPrescriptions {
  private val seen = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

  override fun observe(caregiverUid: String): Flow<Set<String>> = seen.map {
    it[caregiverUid].orEmpty()
  }

  override fun markSeen(caregiverUid: String, patientUid: String, id: String) {
    seen.update {
      it + (caregiverUid to it[caregiverUid].orEmpty() + SeenPrescriptions.key(patientUid, id))
    }
  }
}

/** Holds the app's [SeenPrescriptions]; MainActivity installs the phone's. */
object SeenPrescriptionsProvider {
  @Volatile var store: SeenPrescriptions = SeenPrescriptionsInMemory()
}
