package com.github.se.oncompanion.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Loading failures are separate from write failures, which retain the user's draft. */
enum class EditProfileStatus {
  LOADING,
  READY,
  SIGNED_OUT,
  MISSING_PROFILE,
  ERROR,
}

data class EditProfileUiState(
    val status: EditProfileStatus = EditProfileStatus.LOADING,
    val firstName: String = "",
    val familyName: String = "",
    val cancerType: String = "",
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val saveFailed: Boolean = false,
) {
  val canEdit: Boolean
    get() = status == EditProfileStatus.READY && !isSaving && !isSaved

  val canSave: Boolean
    get() = canEdit && firstName.isNotBlank()
}

/**
 * An existing profile's draft. Observation refreshes untouched fields and preserves local edits.
 */
class EditProfileViewModel(
    private val authRepository: AuthRepository = AuthRepositoryFirebase(),
    private val profileRepository: UserProfileRepository = UserProfileRepositoryFirestore(),
) : ViewModel() {
  private val _uiState = MutableStateFlow(EditProfileUiState())
  val uiState = _uiState.asStateFlow()
  private var profile: UserProfile? = null
  private var observation: Job? = null
  private var saving: Job? = null
  private var generation = 0
  private var firstNameEdited = false
  private var familyNameEdited = false
  private var cancerTypeEdited = false

  init {
    retry()
  }

  fun retry() {
    observation?.cancel()
    reset(EditProfileStatus.LOADING)
    observation = viewModelScope.launch {
      try {
        authRepository
            .observeCurrentUser()
            .map { it?.uid }
            .distinctUntilChanged()
            .collectLatest { uid ->
              reset(if (uid == null) EditProfileStatus.SIGNED_OUT else EditProfileStatus.LOADING)
              if (uid != null) {
                try {
                  profileRepository.observeProfile(uid).collect { current ->
                    if (current == null) {
                      reset(EditProfileStatus.MISSING_PROFILE)
                    } else {
                      val state = _uiState.value
                      _uiState.value =
                          state.copy(
                              status = EditProfileStatus.READY,
                              firstName =
                                  if (firstNameEdited) state.firstName else current.firstName,
                              familyName =
                                  if (familyNameEdited) state.familyName
                                  else current.familyName.orEmpty(),
                              cancerType =
                                  if (cancerTypeEdited) state.cancerType
                                  else current.cancerType.orEmpty(),
                          )
                      profile = current
                    }
                  }
                } catch (e: CancellationException) {
                  throw e
                } catch (e: Exception) {
                  reset(EditProfileStatus.ERROR)
                }
              }
            }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        reset(EditProfileStatus.ERROR)
      }
    }
  }

  fun onFirstNameChange(value: String) = edit {
    it.copy(firstName = value.take(UserProfile.MAX_NAME_LENGTH))
  }

  fun onFamilyNameChange(value: String) = edit {
    it.copy(familyName = value.take(UserProfile.MAX_NAME_LENGTH))
  }

  fun onCancerTypeChange(value: String) = edit {
    it.copy(cancerType = value.take(UserProfile.MAX_CANCER_TYPE_LENGTH))
  }

  private fun edit(change: (EditProfileUiState) -> EditProfileUiState) {
    val state = _uiState.value
    if (!state.canEdit) return
    val updated = change(state)
    firstNameEdited = firstNameEdited || updated.firstName != state.firstName
    familyNameEdited = familyNameEdited || updated.familyName != state.familyName
    cancerTypeEdited = cancerTypeEdited || updated.cancerType != state.cancerType
    _uiState.value = updated.copy(saveFailed = false)
  }

  /**
   * Saves the draft locally, including offline; role and creation time stay unchanged.
   *
   * [UserProfileRepositoryFirestore.updateProfile] completes once the write is applied locally,
   * without waiting for server acceptance. `saveFailed` only reports exceptions thrown by the
   * repository call before or during local completion, such as validation or immediate write
   * errors. A later server/security-rule rejection cannot set `saveFailed`; Firestore may roll back
   * the local change after this screen has reported completion.
   */
  fun saveProfile() {
    val state = _uiState.value
    val original = profile ?: return
    if (!state.canSave) return
    if (authRepository.currentUser?.uid != original.uid) {
      retry()
      return
    }
    val attempt = generation
    _uiState.value = state.copy(isSaving = true, saveFailed = false)
    saving = viewModelScope.launch {
      try {
        profileRepository.updateProfile(
            original.copy(
                firstName = state.firstName.trim(),
                familyName = state.familyName.trim().ifEmpty { null },
                cancerType = state.cancerType.trim().ifEmpty { null },
            )
        )
        if (attempt == generation && authRepository.currentUser?.uid == original.uid) {
          _uiState.value = _uiState.value.copy(isSaving = false, isSaved = true)
        }
      } catch (e: CancellationException) {
        if (attempt == generation) _uiState.value = _uiState.value.copy(isSaving = false)
        throw e
      } catch (e: Exception) {
        if (attempt == generation)
            _uiState.value = _uiState.value.copy(isSaving = false, saveFailed = true)
      }
    }
  }

  /** Navigation consumes completion once, so recomposition cannot pop another screen. */
  fun consumeSaved() {
    _uiState.value = _uiState.value.copy(isSaved = false)
  }

  private fun reset(status: EditProfileStatus) {
    generation++
    saving?.cancel()
    profile = null
    firstNameEdited = false
    familyNameEdited = false
    cancerTypeEdited = false
    _uiState.value = EditProfileUiState(status = status)
  }
}
