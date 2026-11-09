// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CareSection
import com.github.se.oncompanion.domain.carecircle.ObserveCareSection
import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.carecircle.ResolveSectionAccess
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.addedByName
import com.github.se.oncompanion.model.carecircle.observeMemberNames
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
import com.github.se.oncompanion.ui.common.DeleteConfirmation
import com.github.se.oncompanion.ui.common.DeleteState
import com.github.se.oncompanion.ui.common.HasDeleteState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the symptom detail shows.
 *
 * @property entry the symptom, or `null` while loading, after an error, or if it doesn't exist
 * @property isLoading true until the symptom arrives
 * @property hasError true if it couldn't be loaded (or nobody is signed in)
 * @property delete where the deletion of the symptom is at
 * @property canEdit whether the symptom can be edited or deleted: only in the user's own journal
 * @property isNotShared in caregiver mode, the patient doesn't share their symptoms: no symptom
 * @property addedByName who added the symptom, if a caregiver did: for the patient, the name they
 *   gave that member in their circle
 */
data class SymptomDetailUiState(
    val entry: SymptomEntry? = null,
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    override val delete: DeleteState = DeleteState(),
    val canEdit: Boolean = true,
    val isNotShared: Boolean = false,
    val addedByName: String? = null,
) : HasDeleteState {
  /** The symptom is shown and can be deleted. */
  override val canDelete: Boolean
    get() =
        canEdit &&
            entry != null &&
            !isLoading &&
            !hasError &&
            !delete.isDeleting &&
            !delete.isDeleted

  /** The symptom doesn't exist (e.g. deleted on another device). */
  val isNotFound: Boolean
    get() = !isLoading && !hasError && !isNotShared && entry == null
}

/**
 * Loads one symptom of the signed-in user's journal (US-8), keeps it up to date, and deletes it
 * once the user confirms (US-9). In caregiver mode (US-20), the symptom is the followed patient's,
 * read-only, when they share their symptoms.
 *
 * @param patientUid in caregiver mode, the patient whose symptom is shown; null for the user's own
 */
class SymptomDetailViewModel(
    private val symptomId: String,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val symptomRepository: SymptomRepository = SymptomRepositoryProvider.repository,
    private val patientUid: String? = null,
    resolveSection: ResolveSectionAccess =
        ResolveSectionAccess(ResolveCareCircleAccess(auth = authRepository), authRepository),
    private val careCircleRepository: CareCircleRepository =
        CareCircleRepositoryProvider.repository,
) : ViewModel() {

  private val observeSection = ObserveCareSection(resolveSection)

  private val _uiState = MutableStateFlow(SymptomDetailUiState())
  val uiState: StateFlow<SymptomDetailUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  private val deletion =
      DeleteConfirmation(
          uiState = _uiState,
          scope = viewModelScope,
          tag = TAG,
          item = "the symptom $symptomId",
          withDelete = { state, delete -> state.copy(delete = delete) },
      )

  init {
    load()
  }

  /** Starts (or, after an error, restarts) observing the symptom. */
  fun load() {
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    observe()
  }

  /** Observes the symptom in place of the current observation, keeping what is shown. */
  private fun observe() {
    observation?.cancel()
    observation = viewModelScope.launch {
      observeSection(CarePermission.SYMPTOMS, patientUid) { ownerUid ->
            combine(
                symptomRepository.observeSymptom(ownerUid, symptomId),
                namesOf(ownerUid),
                ::Pair,
            )
          }
          .collect { section -> _uiState.update { it.showing(section) } }
    }
  }

  /**
   * The names the patient gave their circle, to show who added the symptom. A caregiver gets none:
   * they see the name stored with the symptom.
   */
  private fun namesOf(ownerUid: String): Flow<Map<String, String>> =
      if (patientUid == null) careCircleRepository.observeMemberNames(ownerUid)
      else flowOf(emptyMap())

  /**
   * The detail for [section]. Without a symptom to show, the delete confirmation is dropped, so it
   * doesn't reopen by itself once the symptom is back.
   */
  private fun SymptomDetailUiState.showing(
      section: CareSection<Pair<SymptomEntry?, Map<String, String>>>
  ): SymptomDetailUiState =
      when (section) {
        // What is shown stays until the symptom arrives (e.g. after a failed deletion), but not
        // while the access itself isn't known: then nothing of it may show
        is CareSection.Loading ->
            if (section.accessKnown) copy(isLoading = entry == null, isNotShared = false)
            else
                copy(
                    entry = null,
                    addedByName = null,
                    isLoading = true,
                    hasError = false,
                    isNotShared = false,
                    canEdit = false,
                    delete = delete.afterRead(false),
                )
        // Nothing to read: not shared, or nobody to read it for
        CareSection.NoAccess,
        CareSection.NotShared ->
            copy(
                entry = null,
                addedByName = null,
                isLoading = false,
                hasError = section == CareSection.NoAccess,
                isNotShared = section == CareSection.NotShared,
                canEdit = false,
                delete = delete.afterRead(false),
            )
        is CareSection.Failed ->
            copy(
                isLoading = false,
                hasError = true,
                isNotShared = false,
                delete = delete.afterRead(false),
            )
        // A symptom deleted elsewhere can't be deleted here: the confirmation is dropped
        is CareSection.Shown -> {
          val (shown, names) = section.data
          copy(
              entry = shown,
              addedByName = shown?.let { addedByName(it.addedBy, it.addedByName, names) },
              isLoading = false,
              hasError = false,
              isNotShared = false,
              canEdit = section.canEdit,
              delete = delete.afterRead(exists = shown != null),
          )
        }
      }

  /** Asks the user to confirm the deletion. Nothing is deleted before [confirmDelete]. */
  fun requestDelete() = deletion.request()

  /** Closes the confirmation and keeps the symptom. Ignored while it is being deleted. */
  fun cancelDelete() = deletion.cancel()

  /**
   * Deletes the symptom. Like logging one, this returns once it is deleted on the device, also
   * offline: the journal no longer shows it and the server deletes it when there is a connection.
   */
  fun confirmDelete() =
      deletion.confirm(
          signedInUid = { authRepository.signedInUid(TAG) },
          stopObserving = { observation?.cancel() },
          delete = { uid -> symptomRepository.deleteSymptom(uid, symptomId) },
          onFailure = { observe() },
      )

  private companion object {
    const val TAG = "SymptomDetailViewModel"
  }
}
