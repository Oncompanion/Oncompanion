// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.Prescription
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the prescription detail shows.
 *
 * @property prescription the prescription, or `null` while loading, after an error, or if it
 *   doesn't exist
 * @property isLoading true until the prescription arrives
 * @property hasError true if it couldn't be loaded (or nobody is signed in)
 */
data class PrescriptionDetailUiState(
    val prescription: Prescription? = null,
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
) {
  /** The prescription doesn't exist (e.g. deleted on another device). */
  val isNotFound: Boolean
    get() = !isLoading && !hasError && prescription == null
}

/**
 * Loads one prescription of the signed-in user and keeps it up to date, so a correction saved in
 * the form shows when the user comes back. The repository reads from the local cache, so the detail
 * also works offline.
 */
class PrescriptionDetailViewModel(
    private val prescriptionId: String,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val medicationRepository: MedicationRepository =
        MedicationRepositoryProvider.repository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(PrescriptionDetailUiState())
  val uiState: StateFlow<PrescriptionDetailUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  init {
    load()
  }

  /** Starts (or, after an error, restarts) observing the prescription. */
  fun load() {
    // Only one read at a time, so a second retry doesn't add a second listener
    observation?.cancel()
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update { it.copy(isLoading = false, hasError = true) }
      return
    }
    observation = viewModelScope.launch {
      medicationRepository
          .observePrescription(uid, prescriptionId)
          .catch { e ->
            Log.e(TAG, "Failed to load the prescription $prescriptionId", e)
            _uiState.update { it.copy(isLoading = false, hasError = true) }
          }
          .collect { prescription ->
            _uiState.update {
              it.copy(prescription = prescription, isLoading = false, hasError = false)
            }
          }
    }
  }

  private companion object {
    const val TAG = "PrescriptionDetailViewModel"
  }
}
