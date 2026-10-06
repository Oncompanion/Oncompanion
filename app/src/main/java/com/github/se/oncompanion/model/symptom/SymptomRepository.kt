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
}
