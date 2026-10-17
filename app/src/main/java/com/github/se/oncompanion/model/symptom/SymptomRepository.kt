// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.symptom

import kotlinx.coroutines.flow.Flow

/** Reads and writes the symptom journal of a user. Use [SymptomRepositoryFirestore] in the app. */
interface SymptomRepository {

  /**
   * Emits the symptoms of [uid], newest [SymptomEntry.occurredAt] first, now and every time they
   * change. Works offline from the local cache, including entries logged offline.
   */
  fun observeSymptoms(uid: String): Flow<List<SymptomEntry>>

  /**
   * Emits the symptom [id] of [uid] (or `null` if it doesn't exist) now and every time it changes.
   */
  fun observeSymptom(uid: String, id: String): Flow<SymptomEntry?>

  /**
   * Saves a new symptom for [uid] and returns its ID. [SymptomEntry.id] and
   * [SymptomEntry.createdAt] are ignored. Returns as soon as the entry is saved on the device (also
   * offline); it reaches the server when there is a connection.
   *
   * @throws IllegalArgumentException if the entry is not [SymptomEntry.isValid]
   */
  suspend fun addSymptom(uid: String, entry: SymptomEntry): String

  /**
   * Replaces the symptom [SymptomEntry.id] of [uid] with [entry], keeping when it was first logged
   * ([SymptomEntry.createdAt] is ignored). Like [addSymptom], returns as soon as the change is
   * saved on the device (also offline). Does nothing if the symptom doesn't exist (e.g. it was
   * deleted on another device): an edit never brings a deleted symptom back.
   *
   * @throws IllegalArgumentException if [SymptomEntry.id] is blank or the entry is not
   *   [SymptomEntry.isValid]
   */
  suspend fun updateSymptom(uid: String, entry: SymptomEntry)

  /**
   * Deletes the symptom [id] of [uid]. Does nothing if it doesn't exist. Like [addSymptom], returns
   * as soon as the entry is deleted on the device (also offline), so it disappears from
   * [observeSymptoms] right away; the server deletes it when there is a connection.
   */
  suspend fun deleteSymptom(uid: String, id: String)
}
