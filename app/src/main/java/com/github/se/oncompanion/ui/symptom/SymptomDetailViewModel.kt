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
import kotlinx.coroutines.CancellationException
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
 * @property isConfirmingDelete the dialog asking to confirm the deletion is shown
 * @property isDeleting the symptom is being deleted
 * @property hasDeleteError the last deletion failed; the symptom is kept
 * @property isDeleted the symptom is deleted: the screen is done
 */
data class SymptomDetailUiState(
    val entry: SymptomEntry? = null,
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val isConfirmingDelete: Boolean = false,
    val isDeleting: Boolean = false,
    val hasDeleteError: Boolean = false,
    val isDeleted: Boolean = false,
) {
  /** The symptom is shown and can be deleted. */
  val canDelete: Boolean
    get() = entry != null && !isLoading && !hasError && !isDeleting && !isDeleted

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

  init {
    load()
  }

  /** Starts (or, after an error, restarts) observing the symptom. */
  fun load() {
    observation?.cancel()
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update { it.copy(isLoading = false, hasError = true) }
      return
    }
    observation = viewModelScope.launch {
      symptomRepository
          .observeSymptom(uid, symptomId)
          .catch { e ->
            Log.e(TAG, "Failed to load the symptom $symptomId", e)
            _uiState.update { it.copy(isLoading = false, hasError = true) }
          }
          .collect { entry ->
            _uiState.update { it.copy(entry = entry, isLoading = false, hasError = false) }
          }
    }
  }

  /** Asks the user to confirm the deletion. Nothing is deleted before [confirmDelete]. */
  fun requestDelete() {
    _uiState.update {
      if (it.canDelete) it.copy(isConfirmingDelete = true, hasDeleteError = false) else it
    }
  }

  /** Closes the confirmation and keeps the symptom. Ignored while it is being deleted. */
  fun cancelDelete() {
    _uiState.update {
      if (it.isDeleting) it else it.copy(isConfirmingDelete = false, hasDeleteError = false)
    }
  }

  /**
   * Deletes the symptom. Like logging one, this returns once it is deleted on the device, also
   * offline: the journal no longer shows it and the server deletes it when there is a connection.
   */
  fun confirmDelete() {
    val state = _uiState.value
    if (!state.isConfirmingDelete || !state.canDelete) return
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update { it.copy(hasDeleteError = true) }
      return
    }
    // Stops observing first, or the deleted symptom would show as "no longer exists" before the
    // screen closes
    observation?.cancel()
    _uiState.update { it.copy(isDeleting = true, hasDeleteError = false) }
    viewModelScope.launch {
      try {
        symptomRepository.deleteSymptom(uid, symptomId)
        _uiState.update {
          it.copy(isDeleting = false, isConfirmingDelete = false, isDeleted = true)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to delete the symptom $symptomId", e)
        _uiState.update { it.copy(isDeleting = false, hasDeleteError = true) }
        load()
      }
    }
  }

  private companion object {
    const val TAG = "SymptomDetailViewModel"
  }
}
