// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryFirestore
import com.github.se.oncompanion.model.medication.Prescription
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the prescriptions list shows.
 *
 * @property today the current date, which decides what counts as "This month"
 * @property prescriptions the saved prescriptions, most recently prescribed first
 * @property isLoading true until the prescriptions arrive
 * @property hasError true if they couldn't be loaded (or nobody is signed in)
 */
data class PrescriptionsUiState(
    val today: LocalDate,
    val prescriptions: List<Prescription> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
) {
  /**
   * The prescriptions prescribed on or after the first day of the current month, in the order of
   * [prescriptions] (Figma: "This month").
   */
  val thisMonth: List<Prescription>
    get() = prescriptions.filterNot { it.prescribedOn.isBefore(today.withDayOfMonth(1)) }

  /**
   * The prescriptions prescribed before the current month, in the same order (Figma: "Earlier").
   */
  val earlier: List<Prescription>
    get() = prescriptions.filter { it.prescribedOn.isBefore(today.withDayOfMonth(1)) }
}

/**
 * Loads the signed-in user's saved prescriptions (US-11) and keeps them up to date, so a
 * prescription saved from the form appears without reloading. The repository reads from the local
 * cache, so the list also works offline.
 *
 * @param clock the current date, which decides what counts as "This month"
 */
class PrescriptionsViewModel(
    private val authRepository: AuthRepository = AuthRepositoryFirebase(),
    private val medicationRepository: MedicationRepository = MedicationRepositoryFirestore(),
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

  private val _uiState = MutableStateFlow(PrescriptionsUiState(today = LocalDate.now(clock)))
  val uiState: StateFlow<PrescriptionsUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  init {
    load()
  }

  /** Starts (or, after an error, restarts) observing the prescriptions. */
  fun load() {
    observation?.cancel()
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update { it.copy(isLoading = false, hasError = true) }
      return
    }
    observation = viewModelScope.launch {
      medicationRepository
          .observePrescriptions(uid)
          .catch { e ->
            Log.e(TAG, "Failed to load the prescriptions", e)
            _uiState.update { it.copy(isLoading = false, hasError = true) }
          }
          .collect { prescriptions ->
            _uiState.update {
              it.copy(prescriptions = prescriptions, isLoading = false, hasError = false)
            }
          }
    }
  }

  /**
   * Updates the current date, so "This month" stays right while the ViewModel lives (the screen
   * calls it each time it comes back).
   */
  fun refreshToday() {
    _uiState.update { it.copy(today = LocalDate.now(clock)) }
  }

  private companion object {
    const val TAG = "PrescriptionsViewModel"
  }
}
