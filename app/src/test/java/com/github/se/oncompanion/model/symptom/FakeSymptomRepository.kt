package com.github.se.oncompanion.model.symptom

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [SymptomRepository] for ViewModel unit tests (no Firebase).
 *
 * Follows the same contract as [SymptomRepositoryFirestore]:
 * - [addSymptom] requires [SymptomEntry.isValid] (else [IllegalArgumentException]), stores the
 *   entry with a new ID (`symptom-1`, `symptom-2`...) and `createdAt = clock()`, and returns the
 *   ID.
 * - [observeSymptoms] emits the user's entries newest `occurredAt` first, then every change.
 * - [observeSymptom] emits the entry (or null), then every change.
 *
 * Test hooks: [observeError] and [writeError] simulate failures, [symptoms] exposes the stored
 * data.
 *
 * @param clock source of the `createdAt` timestamp set when adding (the "server time")
 */
class FakeSymptomRepository(private val clock: () -> Instant = Instant::now) : SymptomRepository {

  /** Stored entries, keyed by uid then by entry ID. */
  private val store = MutableStateFlow<Map<String, Map<String, SymptomEntry>>>(emptyMap())

  private var nextId = 1

  /** When non-null, both observe flows fail with it (checked when collection starts). */
  var observeError: Exception? = null

  /** When non-null, [addSymptom] throws it and stores nothing. */
  var writeError: Exception? = null

  /** Snapshot of the stored entries of [uid], in no particular order. */
  fun symptoms(uid: String): List<SymptomEntry> = store.value[uid].orEmpty().values.toList()

  /** Inserts [entries] for [uid] as-is (IDs included), bypassing validation. Test setup only. */
  fun seed(uid: String, vararg entries: SymptomEntry) {
    store.update { current ->
      current + (uid to current[uid].orEmpty() + entries.associateBy { it.id })
    }
  }

  override fun observeSymptoms(uid: String): Flow<List<SymptomEntry>> = flow {
    observeError?.let { throw it }
    emitAll(
        store
            .map { it[uid].orEmpty().values.sortedByDescending(SymptomEntry::occurredAt) }
            .distinctUntilChanged()
    )
  }

  override fun observeSymptom(uid: String, id: String): Flow<SymptomEntry?> = flow {
    observeError?.let { throw it }
    emitAll(store.map { it[uid]?.get(id) }.distinctUntilChanged())
  }

  override suspend fun addSymptom(uid: String, entry: SymptomEntry): String {
    writeError?.let { throw it }
    require(entry.isValid()) { "Invalid symptom entry: $entry" }
    val id = "symptom-${nextId++}"
    seed(uid, entry.copy(id = id, createdAt = clock()))
    return id
  }
}
