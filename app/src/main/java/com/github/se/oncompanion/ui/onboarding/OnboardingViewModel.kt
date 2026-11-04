// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.domain.auth.SignOutState
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.ui.common.SavePhase
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

/** Where switching to another account is, once the user chose "Use another account". */
enum class AccountSwitch {
  /** Signing out: local changes are being synchronized, then local data cleared. */
  SIGNING_OUT,
  /** Local changes may not be saved online: the user chooses to discard them or stay. */
  CONFIRMING_DISCARD,
  /** Signed out: the user picks another account on the sign-in screen. */
  DONE,
}

/**
 * @property role the chosen role; patient by default
 * @property firstName required (see [canSave])
 * @property cancerType only asked to patients (see [asksCancerType])
 * @property save where saving the profile is; once [SavePhase.SAVED], onboarding is done
 * @property email the signed-in account's email, so the user sees which account they picked
 * @property accountSwitch where switching to another account is, if the user chose to
 */
data class OnboardingUiState(
    val role: Role = Role.PATIENT,
    val firstName: String = "",
    val familyName: String = "",
    val cancerType: String = "",
    val save: SavePhase = SavePhase.EDITING,
    val error: OnboardingError? = null,
    val email: String? = null,
    val accountSwitch: AccountSwitch? = null,
) {
  /** The user can switch to another account: nothing is being saved or switched yet. */
  val canSwitchAccount: Boolean
    get() = accountSwitch == null && save == SavePhase.EDITING

  /** The profile can be saved: a first name is filled in and nothing is being saved. */
  val canSave: Boolean
    get() = firstName.isNotBlank() && save == SavePhase.EDITING

  /** Only a patient is asked for their cancer type: a caregiver isn't the one being treated. */
  val asksCancerType: Boolean
    get() = role == Role.PATIENT
}

/**
 * Onboarding (US-1): the role step, then the user's information, then the profile is saved. Shared
 * by both onboarding screens.
 *
 * The first name is pre-filled from the Google account (the user can change it). Until the profile
 * is saved, the user can sign out to pick another Google account (#281): nothing was saved for this
 * one yet.
 */
class OnboardingViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val signOut: SignOut = SignOutProvider.signOut,
) : ViewModel() {

  private val _uiState =
      MutableStateFlow(OnboardingUiState(firstName = prefilledFirstName(), email = accountEmail()))
  val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

  init {
    // Follows the sign-out this screen started, never one started elsewhere
    viewModelScope.launch {
      signOut.state.collect { phase ->
        if (_uiState.value.accountSwitch == null) return@collect
        val switch =
            when (phase) {
              SignOutState.NEEDS_DISCARD_CONFIRMATION -> AccountSwitch.CONFIRMING_DISCARD
              SignOutState.COMPLETE -> AccountSwitch.DONE
              // Stayed signed in
              SignOutState.IDLE -> null
              else -> AccountSwitch.SIGNING_OUT
            }
        _uiState.update { it.copy(accountSwitch = switch) }
      }
    }
  }

  /**
   * Signs out so the user can pick another Google account: [clearCredentials] forgets the chosen
   * one, so the account picker shows again. Does nothing unless
   * [OnboardingUiState.canSwitchAccount].
   */
  fun useAnotherAccount(clearCredentials: suspend () -> Unit) {
    if (!_uiState.value.canSwitchAccount) return
    _uiState.update { it.copy(accountSwitch = AccountSwitch.SIGNING_OUT) }
    signOut.begin(clearCredentials)
  }

  /** Discards the local changes that may not be saved online, and signs out. */
  fun discardChangesAndSwitch(clearCredentials: suspend () -> Unit) {
    if (_uiState.value.accountSwitch != AccountSwitch.CONFIRMING_DISCARD) return
    signOut.discard(clearCredentials)
  }

  /** Keeps this account and its local changes: onboarding goes on. */
  fun stayOnThisAccount() {
    if (_uiState.value.accountSwitch != AccountSwitch.CONFIRMING_DISCARD) return
    signOut.staySignedIn()
  }

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
    _uiState.update { it.copy(save = SavePhase.SAVING, error = null) }
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
        _uiState.update { it.copy(save = SavePhase.SAVED) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _uiState.update { it.copy(save = SavePhase.EDITING, error = OnboardingError.SAVE_FAILED) }
      }
    }
  }

  /** The error has been shown. */
  fun clearError() {
    _uiState.update { it.copy(error = null) }
  }

  /** The account's email, if it can be read (not in unit tests without Firebase). */
  private fun accountEmail(): String? = runCatching {
    authRepository.currentUser?.email
  }
      .getOrNull()

  /**
   * The first name from the Google account, or empty. The pre-fill is only a convenience: if the
   * current user can't be read (e.g. Firebase isn't initialized, as in unit tests), start empty.
   */
  private fun prefilledFirstName(): String =
      runCatching { authRepository.currentUser?.firstNameGuess() }
          .getOrNull()
          ?.take(UserProfile.MAX_NAME_LENGTH) ?: ""
}
