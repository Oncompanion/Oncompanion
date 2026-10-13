// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Configurable hospital search fake shared by repository and future ViewModel tests. */
class FakeHospitalRepository : HospitalRepository {
  /** Outcome returned for nonblank searches, including simulated recoverable errors. */
  var result: HospitalSearchResult = HospitalSearchResult.Success(emptyList())

  /** Submitted nonblank queries in invocation order, trimmed like the real repository. */
  val queries = mutableListOf<String>()

  /** Optional response gate for deterministic pending-search tests. */
  var pendingResult: CompletableDeferred<HospitalSearchResult>? = null

  /** Simulates a transport that finishes even after its caller cancels the search. */
  var ignoreCancellation: Boolean = false

  /** Queries whose response was delivered, including deliberately uncancellable responses. */
  val completedQueries = mutableListOf<String>()

  /** Queries interrupted while awaiting a controllable response. */
  val cancelledQueries = mutableListOf<String>()

  override suspend fun searchHospitals(searchText: String): HospitalSearchResult {
    val query = searchText.trim()
    if (query.isEmpty()) return HospitalSearchResult.Success(emptyList())
    queries.add(query)
    try {
      val pending = pendingResult
      val response =
          when {
            pending == null -> result
            ignoreCancellation -> withContext(NonCancellable) { pending.await() }
            else -> pending.await()
          }
      completedQueries.add(query)
      return response
    } catch (cancelled: CancellationException) {
      cancelledQueries.add(query)
      throw cancelled
    }
  }
}
