// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.hospital

/**
 * Holds the [HospitalRepository] the app uses, so ViewModels name only the interface. Tests replace
 * [repository] with a fake; deployment wiring calls [configure] once before creating ViewModels.
 */
object HospitalRepositoryProvider {
  /** No network searches until a deployment-configured proxy endpoint is supplied. */
  @Volatile var repository: HospitalRepository = UnconfiguredHospitalRepository

  /**
   * Installs one shared Nominatim repository for a configured HTTPS endpoint, retaining its cache
   * across fields. Invalid endpoints leave the current repository unchanged. This does not make a
   * request; the deployment remains responsible for the aggregate-rate-limited proxy.
   */
  fun configure(searchEndpoint: String) {
    repository = HospitalRepositoryNominatim(searchEndpoint)
  }
}

// Missing deployment configuration uses existing error feedback while manual entry stays usable.
private object UnconfiguredHospitalRepository : HospitalRepository {
  override suspend fun searchHospitals(searchText: String): HospitalSearchResult =
      if (searchText.isBlank()) HospitalSearchResult.Success(emptyList())
      else HospitalSearchResult.Error(HospitalSearchError.SERVICE)
}
