// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

private const val TAG = "CareCircleMemberNames"

/**
 * Emits the first name [ownerUid] gave each member of their circle, by uid, now and every time it
 * changes. If the circle can't be read, emits no names instead of failing, so an entry a caregiver
 * added falls back to the name stored with it rather than hiding the list it is shown in.
 */
fun CareCircleRepository.observeMemberNames(ownerUid: String): Flow<Map<String, String>> = flow {
  emitAll(observeMembers(ownerUid))
}
    .map { members -> members.associate { it.uid to it.firstName } }
    .catch { e ->
      Log.w(TAG, "Failed to read the care circle's names: ${e.javaClass.simpleName}")
      emit(emptyMap())
    }

/**
 * The name to show as "Added by" for an entry signed by [addedBy] with the stored [storedName]: the
 * first name the patient gave that member in [names], or [storedName] if they aren't in it (e.g.
 * removed since, or for a caregiver, who gets no names).
 */
fun addedByName(addedBy: String?, storedName: String?, names: Map<String, String>): String? =
    addedBy?.let(names::get) ?: storedName
