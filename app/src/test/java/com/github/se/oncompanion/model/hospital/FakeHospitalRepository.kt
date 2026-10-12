package com.github.se.oncompanion.model.hospital

/** Configurable hospital search fake shared by repository and future ViewModel tests. */
class FakeHospitalRepository : HospitalRepository {
  /** Outcome returned for nonblank searches, including simulated recoverable errors. */
  var result: HospitalSearchResult = HospitalSearchResult.Success(emptyList())

  /** Submitted nonblank queries in invocation order, trimmed like the real repository. */
  val queries = mutableListOf<String>()

  override suspend fun searchHospitals(searchText: String): HospitalSearchResult {
    val query = searchText.trim()
    if (query.isEmpty()) return HospitalSearchResult.Success(emptyList())
    queries.add(query)
    return result
  }
}
