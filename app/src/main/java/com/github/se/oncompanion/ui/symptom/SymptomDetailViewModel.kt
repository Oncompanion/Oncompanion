package com.github.se.oncompanion.ui.symptom

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomRepositoryFirestore
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
 */
data class SymptomDetailUiState(
    val entry: SymptomEntry? = null,
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
) {
  /** The symptom doesn't exist (e.g. deleted on another device). */
  val isNotFound: Boolean
    get() = !isLoading && !hasError && entry == null
}

/** Loads one symptom of the signed-in user's journal (US-8), and keeps it up to date. */
class SymptomDetailViewModel(
    private val symptomId: String,
    private val authRepository: AuthRepository = AuthRepositoryFirebase(),
    private val symptomRepository: SymptomRepository = SymptomRepositoryFirestore(),
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

  private companion object {
    const val TAG = "SymptomDetailViewModel"
  }
}

/**
 * The signed-in user's uid, or `null` if nobody is signed in or the user can't be read (logged with
 * [tag]). Symptom screens are only reachable after signing in, so `null` is an error for them.
 */
internal fun AuthRepository.signedInUid(tag: String): String? =
    try {
      currentUser?.uid
    } catch (e: Exception) {
      Log.w(tag, "Couldn't read the signed-in user", e)
      null
    }
