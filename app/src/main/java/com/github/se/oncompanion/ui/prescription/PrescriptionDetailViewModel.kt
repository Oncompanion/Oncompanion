// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.medication.ManageMedicationSchedule
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.ui.common.DeleteConfirmation
import com.github.se.oncompanion.ui.common.DeleteState
import com.github.se.oncompanion.ui.common.HasDeleteState
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
 * @property delete where the deletion of the prescription is at
 */
data class PrescriptionDetailUiState(
    val prescription: Prescription? = null,
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    override val delete: DeleteState = DeleteState(),
) : HasDeleteState {
  /** The prescription doesn't exist (e.g. deleted on another device). */
  val isNotFound: Boolean
    get() = !isLoading && !hasError && prescription == null

  /**
   * The prescription is shown and can be corrected: not after a read error, when what is on screen
   * may be out of date.
   */
  val canEdit: Boolean
    get() =
        prescription != null && !isLoading && !hasError && !delete.isDeleting && !delete.isDeleted

  /** The prescription is shown and can be deleted. */
  override val canDelete: Boolean
    get() = canEdit
}

/**
 * Loads one prescription of the signed-in user and keeps it up to date, so a correction saved in
 * the form shows when the user comes back. The repository reads from the local cache, so the detail
 * also works offline. Deletes the prescription once the user confirms (US-12).
 */
class PrescriptionDetailViewModel(
    private val prescriptionId: String,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val medicationRepository: MedicationRepository =
        MedicationRepositoryProvider.repository,
    private val manageMedicationSchedule: ManageMedicationSchedule =
        ManageMedicationSchedule(medicationRepository),
) : ViewModel() {

  private val _uiState = MutableStateFlow(PrescriptionDetailUiState())
  val uiState: StateFlow<PrescriptionDetailUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  private val deletion =
      DeleteConfirmation(
          uiState = _uiState,
          scope = viewModelScope,
          tag = TAG,
          item = "the prescription $prescriptionId",
          withDelete = { state, delete -> state.copy(delete = delete) },
      )

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
    observe(uid)
  }

  /** Observes the prescription of [uid] in place of the current observation. */
  private fun observe(uid: String) {
    observation?.cancel()
    observation = viewModelScope.launch {
      medicationRepository
          .observePrescription(uid, prescriptionId)
          .catch { e ->
            Log.e(TAG, "Failed to load the prescription $prescriptionId", e)
            // Nothing to delete without the prescription: the confirmation is dropped
            _uiState.update {
              it.copy(isLoading = false, hasError = true, delete = it.delete.afterRead(false))
            }
          }
          .collect { prescription ->
            // A prescription deleted elsewhere can't be deleted here: the confirmation is dropped
            _uiState.update {
              it.copy(
                  prescription = prescription,
                  isLoading = false,
                  hasError = false,
                  delete = it.delete.afterRead(exists = prescription != null),
              )
            }
          }
    }
  }

  /** Asks the user to confirm the deletion. Nothing is deleted before [confirmDelete]. */
  fun requestDelete() = deletion.request()

  /** Closes the confirmation and keeps the prescription. Ignored while it is being deleted. */
  fun cancelDelete() = deletion.cancel()

  /**
   * Deletes the prescription with its medications, through [ManageMedicationSchedule] like every
   * medication write. Returns once it is deleted on the device, also offline: the list no longer
   * shows it and the server deletes it when there is a connection.
   */
  fun confirmDelete() =
      deletion.confirm(
          signedInUid = { authRepository.signedInUid(TAG) },
          stopObserving = { observation?.cancel() },
          delete = { uid -> manageMedicationSchedule.delete(uid, prescriptionId) },
          onFailure = ::observe,
      )

  private companion object {
    const val TAG = "PrescriptionDetailViewModel"
  }
}
