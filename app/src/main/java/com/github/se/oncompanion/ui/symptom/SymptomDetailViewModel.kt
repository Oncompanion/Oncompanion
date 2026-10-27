// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
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
 * What the symptom detail shows.
 *
 * @property entry the symptom, or `null` while loading, after an error, or if it doesn't exist
 * @property isLoading true until the symptom arrives
 * @property hasError true if it couldn't be loaded (or nobody is signed in)
 * @property delete where the deletion of the symptom is at
 */
data class SymptomDetailUiState(
    val entry: SymptomEntry? = null,
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    override val delete: DeleteState = DeleteState(),
) : HasDeleteState {
  /** The symptom is shown and can be deleted. */
  override val canDelete: Boolean
    get() = entry != null && !isLoading && !hasError && !delete.isDeleting && !delete.isDeleted

  /** The symptom doesn't exist (e.g. deleted on another device). */
  val isNotFound: Boolean
    get() = !isLoading && !hasError && entry == null
}

/**
 * Loads one symptom of the signed-in user's journal (US-8), keeps it up to date, and deletes it
 * once the user confirms (US-9).
 */
class SymptomDetailViewModel(
    private val symptomId: String,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val symptomRepository: SymptomRepository = SymptomRepositoryProvider.repository,
) : ViewModel() {

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
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      observation?.cancel()
      _uiState.update { it.copy(isLoading = false, hasError = true) }
      return
    }
    observe(uid)
  }

  /** Observes the symptom of [uid] in place of the current observation, keeping what is shown. */
  private fun observe(uid: String) {
    observation?.cancel()
    observation = viewModelScope.launch {
      symptomRepository
          .observeSymptom(uid, symptomId)
          .catch { e ->
            Log.e(TAG, "Failed to load the symptom $symptomId", e)
            // Nothing to delete without the symptom: the confirmation is dropped
            _uiState.update {
              it.copy(isLoading = false, hasError = true, delete = it.delete.afterRead(false))
            }
          }
          .collect { entry ->
            // A symptom deleted elsewhere can't be deleted here: the confirmation is dropped
            _uiState.update {
              it.copy(
                  entry = entry,
                  isLoading = false,
                  hasError = false,
                  delete = it.delete.afterRead(exists = entry != null),
              )
            }
          }
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
          onFailure = ::observe,
      )

  private companion object {
    const val TAG = "SymptomDetailViewModel"
  }
}
