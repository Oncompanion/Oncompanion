// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why saving the profile failed, so the screen can show the right message. */
enum class OnboardingError {
  /** Nobody is signed in (e.g. the session expired): the user has to sign in again. */
  NOT_SIGNED_IN,
  /** Anything else. */
  SAVE_FAILED,
}

/**
 * @property role the chosen role; patient by default
 * @property firstName required (see [canSave])
 * @property cancerType only asked to patients (see [asksCancerType])
 * @property isSaving the profile is being saved
 * @property isSaved the profile is saved: onboarding is done
 */
data class OnboardingUiState(
    val role: Role = Role.PATIENT,
    val firstName: String = "",
    val familyName: String = "",
    val cancerType: String = "",
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: OnboardingError? = null,
) {
  /** The profile can be saved: a first name is filled in and nothing is being saved. */
  val canSave: Boolean
    get() = firstName.isNotBlank() && !isSaving && !isSaved

  /** Only a patient is asked for their cancer type: a caregiver isn't the one being treated. */
  val asksCancerType: Boolean
    get() = role == Role.PATIENT
}

/**
 * Onboarding (US-1): the role step, then the user's information, then the profile is saved. Shared
 * by both onboarding screens.
 *
 * The first name is pre-filled from the Google account (the user can change it).
 */
class OnboardingViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(OnboardingUiState(firstName = prefilledFirstName()))
  val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

  /**
   * Chooses the role. A cancer type already typed is kept, in case the user picks patient again.
   */
  fun selectRole(role: Role) {
    _uiState.update { it.copy(role = role) }
  }

  /** Text longer than [UserProfile.MAX_NAME_LENGTH] is cut, as the profile can't store it. */
  fun onFirstNameChange(value: String) {
    _uiState.update { it.copy(firstName = value.take(UserProfile.MAX_NAME_LENGTH)) }
  }

  /** Text longer than [UserProfile.MAX_NAME_LENGTH] is cut, as the profile can't store it. */
  fun onFamilyNameChange(value: String) {
    _uiState.update { it.copy(familyName = value.take(UserProfile.MAX_NAME_LENGTH)) }
  }

  /**
   * Text longer than [UserProfile.MAX_CANCER_TYPE_LENGTH] is cut, as the profile can't store it.
   */
  fun onCancerTypeChange(value: String) {
    _uiState.update { it.copy(cancerType = value.take(UserProfile.MAX_CANCER_TYPE_LENGTH)) }
  }

  /**
   * Saves the profile: trimmed first name, and family name and cancer type only if filled in (the
   * cancer type only for a patient, see [OnboardingUiState.asksCancerType]). Does nothing unless
   * [OnboardingUiState.canSave]. Saving returns as soon as the profile is stored on the device,
   * also offline.
   */
  fun saveProfile() {
    val state = _uiState.value
    if (!state.canSave) return
    val uid = authRepository.currentUser?.uid
    if (uid == null) {
      _uiState.update { it.copy(error = OnboardingError.NOT_SIGNED_IN) }
      return
    }
    _uiState.update { it.copy(isSaving = true, error = null) }
    viewModelScope.launch {
      try {
        profileRepository.createProfile(
            UserProfile(
                uid = uid,
                role = state.role,
                firstName = state.firstName.trim(),
                familyName = state.familyName.trim().ifEmpty { null },
                cancerType =
                    if (state.asksCancerType) state.cancerType.trim().ifEmpty { null } else null,
            )
        )
        _uiState.update { it.copy(isSaving = false, isSaved = true) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _uiState.update { it.copy(isSaving = false, error = OnboardingError.SAVE_FAILED) }
      }
    }
  }

  /** The error has been shown. */
  fun clearError() {
    _uiState.update { it.copy(error = null) }
  }

  /**
   * The first name from the Google account, or empty. The pre-fill is only a convenience: if the
   * current user can't be read (e.g. Firebase isn't initialized, as in unit tests), start empty.
   */
  private fun prefilledFirstName(): String =
      runCatching { authRepository.currentUser?.firstNameGuess() }
          .getOrNull()
          ?.take(UserProfile.MAX_NAME_LENGTH) ?: ""
}
