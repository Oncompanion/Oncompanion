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
import kotlinx.coroutines.CancellationException
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
 * @property isConfirmingDelete the user asked to delete the prescription and hasn't confirmed or
 *   cancelled
 * @property isDeleting the prescription is being deleted
 * @property hasDeleteError the last deletion failed; the prescription is kept
 * @property isDeleted the prescription is deleted: the screen is done
 */
data class PrescriptionDetailUiState(
    val prescription: Prescription? = null,
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val isConfirmingDelete: Boolean = false,
    val isDeleting: Boolean = false,
    val hasDeleteError: Boolean = false,
    val isDeleted: Boolean = false,
) {
  /** The prescription doesn't exist (e.g. deleted on another device). */
  val isNotFound: Boolean
    get() = !isLoading && !hasError && prescription == null

  /**
   * The prescription is shown and can be corrected: not after a read error, when what is on screen
   * may be out of date.
   */
  val canEdit: Boolean
    get() = prescription != null && !isLoading && !hasError && !isDeleting && !isDeleted

  /** The prescription is shown and can be deleted. */
  val canDelete: Boolean
    get() = canEdit

  /**
   * The dialog asking to confirm the deletion is shown: only while its Delete button can do
   * something, so it never stays over a prescription that is gone or reloading.
   */
  val isDeleteDialogShown: Boolean
    get() = isConfirmingDelete && (canDelete || isDeleting)
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
            _uiState.update {
              it.copy(
                  isLoading = false,
                  hasError = true,
                  isConfirmingDelete = false,
                  hasDeleteError = false,
              )
            }
          }
          .collect { prescription ->
            _uiState.update {
              // A prescription deleted elsewhere can't be deleted here: the confirmation is dropped
              val stillConfirming = it.isConfirmingDelete && prescription != null
              it.copy(
                  prescription = prescription,
                  isLoading = false,
                  hasError = false,
                  isConfirmingDelete = stillConfirming,
                  hasDeleteError = it.hasDeleteError && stillConfirming,
              )
            }
          }
    }
  }

  /** Asks the user to confirm the deletion. Nothing is deleted before [confirmDelete]. */
  fun requestDelete() {
    _uiState.update {
      if (it.canDelete) it.copy(isConfirmingDelete = true, hasDeleteError = false) else it
    }
  }

  /** Closes the confirmation and keeps the prescription. Ignored while it is being deleted. */
  fun cancelDelete() {
    _uiState.update {
      if (it.isDeleting) it else it.copy(isConfirmingDelete = false, hasDeleteError = false)
    }
  }

  /**
   * Deletes the prescription with its medications, through [ManageMedicationSchedule] like every
   * medication write. Returns once it is deleted on the device, also offline: the list no longer
   * shows it and the server deletes it when there is a connection.
   */
  fun confirmDelete() {
    val state = _uiState.value
    if (!state.isConfirmingDelete || !state.canDelete) return
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update { it.copy(hasDeleteError = true) }
      return
    }
    // Stops observing first, or the deleted prescription would show as "no longer exists" before
    // the screen closes
    observation?.cancel()
    _uiState.update { it.copy(isDeleting = true, hasDeleteError = false) }
    viewModelScope.launch {
      try {
        manageMedicationSchedule.delete(uid, prescriptionId)
        _uiState.update {
          it.copy(isDeleting = false, isConfirmingDelete = false, isDeleted = true)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to delete the prescription $prescriptionId", e)
        // Keeps the prescription and the dialog shown, so the user can try again
        _uiState.update { it.copy(isDeleting = false, hasDeleteError = true) }
        observe(uid)
      }
    }
  }

  private companion object {
    const val TAG = "PrescriptionDetailViewModel"
  }
}
