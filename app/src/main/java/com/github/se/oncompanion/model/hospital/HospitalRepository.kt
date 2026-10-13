package com.github.se.oncompanion.model.hospital

/** A hospital's OpenStreetMap identity, name, full address label and coordinates. */
data class Hospital(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

/** Search outcome; an empty successful list means no matching hospitals. */
sealed interface HospitalSearchResult {
  /** Matching hospitals in the service's relevance order. */
  data class Success(val hospitals: List<Hospital>) : HospitalSearchResult

  /** Recoverable failure for the caller to present using localized text. */
  data class Error(val reason: HospitalSearchError) : HospitalSearchResult
}

/** Distinguishes transport failures, service refusal and malformed responses. */
enum class HospitalSearchError {
  NETWORK,
  SERVICE,
  RATE_LIMITED,
  INVALID_RESPONSE,
}

/** Hospital lookup boundary for ViewModels; contains no patient data or persistence. */
fun interface HospitalRepository {
  /**
   * Searches only on explicit user submission, never on each keystroke. Blank text returns an empty
   * success without a request. Expected failures return [HospitalSearchResult.Error]; coroutine
   * cancellation still propagates. Submit only hospital names or public locations.
   */
  suspend fun searchHospitals(searchText: String): HospitalSearchResult

  companion object {
    /** Required credit to display alongside results, including cached results. */
    const val ATTRIBUTION = "© OpenStreetMap contributors"

    /** Link accompanying the credit, including the Open Database License. */
    const val ATTRIBUTION_URL = "https://www.openstreetmap.org/copyright"
  }
}
