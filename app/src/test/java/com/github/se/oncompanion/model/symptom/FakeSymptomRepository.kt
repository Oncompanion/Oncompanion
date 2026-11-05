// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.symptom

import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.model.session.RejectingServer
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
 * - [addSymptom] requires [SymptomEntry.isValid] at `clock()` (else [IllegalArgumentException]),
 *   stores the entry with a new ID (`symptom-1`, `symptom-2`...) and `createdAt = clock()`, and
 *   returns the ID.
 * - [observeSymptoms] emits the user's entries newest `occurredAt` first, then every change.
 * - [observeSymptom] emits the entry (or null), then every change.
 * - [updateSymptom] requires a non-blank ID and [SymptomEntry.isValid] at `clock()`, replaces the
 *   entry keeping its `createdAt`, and does nothing if it doesn't exist.
 * - [deleteSymptom] removes the entry, and does nothing if it doesn't exist.
 *
 * Test hooks: [observeError] and [writeError] simulate failures, [serverRejectsWrites] and
 * [rejectPendingWrites] a server that rejects writes after they returned, [symptoms] exposes the
 * stored data.
 *
 * @param clock source of the `createdAt` timestamp set when adding (the "server time")
 * @param rejectedWrites where [rejectPendingWrites] reports the writes it undoes
 */
class FakeSymptomRepository(
    private val clock: () -> Instant = Instant::now,
    rejectedWrites: RejectedWrites = RejectedWrites(),
) : SymptomRepository {

  /** Stored entries, keyed by uid then by entry ID. */
  private val store = MutableStateFlow<Map<String, Map<String, SymptomEntry>>>(emptyMap())

  private var nextId = 1

  /** When non-null, both observe flows fail with it (checked when collection starts). */
  var observeError: Exception? = null

  /**
   * When non-null, [addSymptom], [updateSymptom] and [deleteSymptom] throw it and change nothing.
   */
  var writeError: Exception? = null

  private val server = RejectingServer<Map<String, Map<String, SymptomEntry>>>(rejectedWrites)

  /** When true, writes still return as usual, until [rejectPendingWrites] undoes them. */
  var serverRejectsWrites by server::rejectsWrites

  /**
   * The server rejects the writes saved since [serverRejectsWrites]: they are undone and reported.
   */
  fun rejectPendingWrites() = server.reject { store.value = it }

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
    require(entry.isValid(now = clock())) { "Invalid symptom entry: $entry" }
    server.saving(store.value)
    val id = "symptom-${nextId++}"
    seed(uid, entry.copy(id = id, createdAt = clock()))
    return id
  }

  override suspend fun updateSymptom(uid: String, entry: SymptomEntry) {
    writeError?.let { throw it }
    require(entry.id.isNotBlank()) { "The symptom to update has no ID" }
    require(entry.isValid(now = clock())) { "Invalid symptom entry: $entry" }
    server.saving(store.value)
    store.update { current ->
      val stored = current[uid]?.get(entry.id) ?: return@update current
      current +
          (uid to current[uid].orEmpty() + (entry.id to entry.copy(createdAt = stored.createdAt)))
    }
  }

  override suspend fun deleteSymptom(uid: String, id: String) {
    writeError?.let { throw it }
    server.saving(store.value)
    store.update { current -> current + (uid to current[uid].orEmpty() - id) }
  }
}
