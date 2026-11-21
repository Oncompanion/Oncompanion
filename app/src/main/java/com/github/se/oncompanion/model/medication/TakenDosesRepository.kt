// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * The doses of their medications a patient ticked each day on the Overview (US-28): for each day,
 * how many doses of each medication were taken. The patient records them; a caregiver who sees the
 * patient's prescriptions only reads them. The app records what the patient ticked, nothing more:
 * how many doses a day a medication has comes from its frequency (`DoseCount`).
 */
interface TakenDosesRepository {

  /**
   * How many doses of each medication, by [Medication.id], [uid] ticked on [date], the patient's
   * local day; a medication without a tick is absent, so a new day starts empty. It emits again
   * when a tick changes, from the device's cache when offline, and fails if the user may not read
   * them (e.g. a caregiver who no longer sees the patient's prescriptions).
   */
  fun observeDay(uid: String, date: LocalDate): Flow<Map<String, Int>>

  /**
   * Records that [count] doses of [medicationId] were taken on [date] (0 unticks them all), without
   * changing the other medications of that day. It returns once saved on the device, also offline.
   *
   * @throws IllegalArgumentException if [medicationId] is blank or [count] isn't from 0 to
   *   [MAX_TAKEN_PER_DAY]
   */
  suspend fun setTaken(uid: String, date: LocalDate, medicationId: String, count: Int)

  companion object {
    /** At most one dose an hour is recorded. Keep in sync with `firestore.rules`. */
    const val MAX_TAKEN_PER_DAY = 24
  }
}
