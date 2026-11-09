// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CareSection
import com.github.se.oncompanion.domain.carecircle.ObserveCareSection
import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.carecircle.ResolveSectionAccess
import com.github.se.oncompanion.domain.medication.ManageMedicationSchedule
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.carecircle.CarePermission
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
 * @property isOwn whether it is the user's own prescription: a caregiver only reads the patient's
 * @property isNotShared in caregiver mode, the patient doesn't share their prescriptions
 */
data class PrescriptionDetailUiState(
    val prescription: Prescription? = null,
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    override val delete: DeleteState = DeleteState(),
    val isOwn: Boolean = true,
    val isNotShared: Boolean = false,
) : HasDeleteState {
  /** The prescription doesn't exist (e.g. deleted on another device). */
  val isNotFound: Boolean
    get() = !isLoading && !hasError && !isNotShared && prescription == null

  /**
   * The prescription is shown and can be corrected: not after a read error, when what is on screen
   * may be out of date.
   */
  val canEdit: Boolean
    get() =
        isOwn &&
            prescription != null &&
            !isLoading &&
            !hasError &&
            !delete.isDeleting &&
            !delete.isDeleted

  /** The prescription is shown and can be deleted. */
  override val canDelete: Boolean
    get() = canEdit
}

/**
 * Loads one prescription of the signed-in user and keeps it up to date, so a correction saved in
 * the form shows when the user comes back. The repository reads from the local cache, so the detail
 * also works offline. Deletes the prescription once the user confirms (US-12). In caregiver mode
 * (US-20), the prescription is the followed patient's, read-only, when they share their
 * prescriptions.
 *
 * @param patientUid in caregiver mode, the patient whose prescription is shown; null for the user's
 *   own
 */
class PrescriptionDetailViewModel(
    private val prescriptionId: String,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val medicationRepository: MedicationRepository =
        MedicationRepositoryProvider.repository,
    private val manageMedicationSchedule: ManageMedicationSchedule =
        ManageMedicationSchedule(medicationRepository),
    private val patientUid: String? = null,
    resolveSection: ResolveSectionAccess =
        ResolveSectionAccess(ResolveCareCircleAccess(auth = authRepository), authRepository),
) : ViewModel() {

  private val observeSection = ObserveCareSection(resolveSection)

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
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    observe()
  }

  /** Observes the prescription in place of the current observation, keeping what is shown. */
  private fun observe() {
    // Only one read at a time, so a second retry doesn't add a second listener
    observation?.cancel()
    observation = viewModelScope.launch {
      observeSection(CarePermission.PRESCRIPTIONS, patientUid) { ownerUid ->
            medicationRepository.observePrescription(ownerUid, prescriptionId)
          }
          .collect { section -> _uiState.update { it.showing(section) } }
    }
  }

  /**
   * The detail for [section]. Without a prescription to show, the delete confirmation is dropped,
   * so it doesn't reopen by itself once the prescription is back.
   */
  private fun PrescriptionDetailUiState.showing(
      section: CareSection<Prescription?>
  ): PrescriptionDetailUiState =
      when (section) {
        // What is shown stays until the prescription arrives (e.g. after a failed deletion), but
        // not while the access itself isn't known: then nothing of it may show
        is CareSection.Loading ->
            if (section.accessKnown) copy(isLoading = prescription == null, isNotShared = false)
            else
                copy(
                    prescription = null,
                    isLoading = true,
                    hasError = false,
                    isNotShared = false,
                    isOwn = false,
                    delete = delete.afterRead(false),
                )
        // Nothing to read: not shared, or nobody to read it for
        CareSection.NoAccess,
        CareSection.NotShared ->
            copy(
                prescription = null,
                isLoading = false,
                hasError = section == CareSection.NoAccess,
                isNotShared = section == CareSection.NotShared,
                isOwn = false,
                delete = delete.afterRead(false),
            )
        is CareSection.Failed ->
            copy(
                isLoading = false,
                hasError = true,
                isNotShared = false,
                delete = delete.afterRead(false),
            )
        // A prescription deleted elsewhere can't be deleted here: the confirmation is dropped
        is CareSection.Shown ->
            copy(
                prescription = section.data,
                isLoading = false,
                hasError = false,
                isNotShared = false,
                isOwn = section.canEdit,
                delete = delete.afterRead(exists = section.data != null),
            )
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
          onFailure = { observe() },
      )

  private companion object {
    const val TAG = "PrescriptionDetailViewModel"
  }
}
