// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.hospital

import android.util.Log

/**
 * Holds the [HospitalRepository] the app uses, so ViewModels name only the interface. Tests replace
 * [repository] with a fake; deployment wiring calls [configure] once before creating ViewModels.
 */
object HospitalRepositoryProvider {
  /** No network searches until a deployment-configured proxy endpoint is supplied. */
  @Volatile var repository: HospitalRepository = UnconfiguredHospitalRepository

  /**
   * True only when the authenticated proxy adapter is installed; missing configuration stays
   * manual-only.
   */
  val isConfigured: Boolean
    get() = repository is HospitalRepositoryProxy

  /**
   * Restores manual-only behavior for new consumers without constructing Firebase or HTTP clients.
   */
  fun disable() {
    repository = UnconfiguredHospitalRepository
  }

  /**
   * Applies startup configuration only to the unconfigured default, preserving installed adapters
   * and test overrides. Blank or invalid endpoints keep manual entry available without networking.
   */
  fun configureAtStartup(searchEndpoint: String) {
    if (searchEndpoint.isBlank() || repository !== UnconfiguredHospitalRepository) return
    try {
      configure(searchEndpoint)
    } catch (e: IllegalArgumentException) {
      // Endpoint validation can include the URL in its message; log only the failure type.
      Log.w(
          "HospitalRepositoryProvider",
          "Invalid hospital proxy configuration: ${e.javaClass.simpleName}",
      )
    }
  }

  /**
   * Installs one shared authenticated proxy repository for a configured HTTPS endpoint, retaining
   * its cache across fields. Invalid endpoints throw without changing the current repository. This
   * does not make a request; the deployment remains responsible for the aggregate-rate-limited
   * proxy.
   *
   * @throws IllegalArgumentException if the endpoint is not an allowed proxy HTTPS URL
   */
  fun configure(searchEndpoint: String) {
    repository = HospitalRepositoryProxy(searchEndpoint)
  }
}

// Missing deployment configuration uses existing error feedback while manual entry stays usable.
private object UnconfiguredHospitalRepository : HospitalRepository {
  override suspend fun searchHospitals(searchText: String): HospitalSearchResult =
      if (searchText.isBlank()) HospitalSearchResult.Success(emptyList())
      else HospitalSearchResult.Error(HospitalSearchError.SERVICE)
}
