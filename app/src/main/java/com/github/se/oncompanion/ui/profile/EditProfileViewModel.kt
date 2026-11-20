// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.ui.common.SavePhase
import java.time.Clock
import java.time.LocalDate
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

/**
 * Profile draft with optional patient hospital text; an empty hospital never prevents saving.
 *
 * @property dateOfBirth the optional date of birth, `null` when not set or cleared
 * @property dateOfBirthTyped the digits the user typed as the date of birth, or `null` when the
 *   field shows [dateOfBirth] (as saved, or as picked)
 * @property today the phone's today: the latest date of birth that can be picked and saved. The
 *   ViewModel sets it from its clock in every state it makes; the default (no day allowed) never
 *   reads the wall clock, so a test that forgets it doesn't depend on the day it runs
 */
data class EditProfileUiState(
    val status: EditProfileStatus = EditProfileStatus.LOADING,
    val firstName: String = "",
    val familyName: String = "",
    val cancerType: String = "",
    val save: SavePhase = SavePhase.EDITING,
    val saveFailed: Boolean = false,
    val hospital: String = "",
    val isPatient: Boolean = true,
    val dateOfBirth: LocalDate? = null,
    val today: LocalDate = LocalDate.MIN,
    val dateOfBirthTyped: String? = null,
) {
  /**
   * What is typed as the date of birth isn't a whole, real day yet (e.g. "31/1" or "31/02/1980"):
   * the draft can't be saved, and the field shows why.
   */
  val dateOfBirthUnreadable: Boolean
    get() = !dateOfBirthTyped.isNullOrEmpty() && dateOfBirth == null

  /**
   * The date of birth is after [today], whether picked here or saved by another phone (one ahead in
   * time zone, or whose clock was wrong): it is shown as an error and the draft can't be saved.
   */
  val dateOfBirthInFuture: Boolean
    get() = dateOfBirth?.isAfter(today) == true

  val canEdit: Boolean
    get() = status == EditProfileStatus.READY && save == SavePhase.EDITING

  val canSave: Boolean
    get() =
        canEdit &&
            firstName.isNotBlank() &&
            hospital.length <= UserProfile.MAX_HOSPITAL_LENGTH &&
            !dateOfBirthInFuture &&
            !dateOfBirthUnreadable
}

/**
 * An existing profile's draft. Observation refreshes untouched fields and preserves local edits.
 *
 * @param clock the phone's clock and time zone: a date of birth after its today is refused
 */
class EditProfileViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
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
  private var hospitalEdited = false
  private var dateOfBirthEdited = false

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
                              today = LocalDate.now(clock),
                              isPatient = current.role == Role.PATIENT,
                              hospital =
                                  if (hospitalEdited) state.hospital
                                  else current.hospital.orEmpty(),
                              firstName =
                                  if (firstNameEdited) state.firstName else current.firstName,
                              familyName =
                                  if (familyNameEdited) state.familyName
                                  else current.familyName.orEmpty(),
                              cancerType =
                                  if (cancerTypeEdited) state.cancerType
                                  else current.cancerType.orEmpty(),
                              dateOfBirth =
                                  if (dateOfBirthEdited) state.dateOfBirth else current.dateOfBirth,
                              dateOfBirthTyped =
                                  if (dateOfBirthEdited) state.dateOfBirthTyped else null,
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

  /** Bounds manual hospital text like the other fields; caregivers keep their existing data. */
  fun onHospitalChange(value: String) = edit {
    if (it.isPatient) it.copy(hospital = value.take(UserProfile.MAX_HOSPITAL_LENGTH)) else it
  }

  /**
   * Sets, changes or clears ([date] null) the date of birth, e.g. picked; after today it's an
   * error.
   */
  fun onDateOfBirthChange(date: LocalDate?) = edit {
    it.copy(dateOfBirth = date, dateOfBirthTyped = null)
  }

  /**
   * The user typed [digits] as the date of birth, which read as [date]: `null` while they aren't a
   * whole, real day yet, which can't be saved.
   */
  fun onDateOfBirthTyped(digits: String, date: LocalDate?) = edit {
    it.copy(dateOfBirth = date, dateOfBirthTyped = digits)
  }

  private fun edit(change: (EditProfileUiState) -> EditProfileUiState) {
    val state = _uiState.value
    if (!state.canEdit) return
    val updated = change(state)
    firstNameEdited = firstNameEdited || updated.firstName != state.firstName
    familyNameEdited = familyNameEdited || updated.familyName != state.familyName
    cancerTypeEdited = cancerTypeEdited || updated.cancerType != state.cancerType
    hospitalEdited = hospitalEdited || updated.hospital != state.hospital
    dateOfBirthEdited =
        dateOfBirthEdited ||
            updated.dateOfBirth != state.dateOfBirth ||
            updated.dateOfBirthTyped != state.dateOfBirthTyped
    // Today is read again at each edit, so a screen left open past midnight follows the date
    _uiState.value = updated.copy(saveFailed = false, today = LocalDate.now(clock))
  }

  /**
   * Saves the draft locally, including offline; role and creation time stay unchanged.
   *
   * [UserProfileRepository.updateProfile] completes once the write is applied locally, without
   * waiting for server acceptance. `saveFailed` only reports exceptions thrown by the repository
   * call before or during local completion, such as validation or immediate write errors. A later
   * server/security-rule rejection cannot set `saveFailed`; Firestore may roll back the local
   * change after this screen has reported completion.
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
    _uiState.value = state.copy(save = SavePhase.SAVING, saveFailed = false)
    saving = viewModelScope.launch {
      try {
        profileRepository.updateProfile(updatedProfile(original, state))
        if (attempt == generation && authRepository.currentUser?.uid == original.uid) {
          _uiState.value = _uiState.value.copy(save = SavePhase.SAVED)
        }
      } catch (e: CancellationException) {
        if (attempt == generation) _uiState.value = _uiState.value.copy(save = SavePhase.EDITING)
        throw e
      } catch (e: Exception) {
        if (attempt == generation)
            _uiState.value = _uiState.value.copy(save = SavePhase.EDITING, saveFailed = true)
      }
    }
  }

  // Normalize the captured draft without changing protected or caregiver-only data.
  private fun updatedProfile(original: UserProfile, draft: EditProfileUiState): UserProfile =
      original.copy(
          firstName = draft.firstName.trim(),
          familyName = draft.familyName.trim().ifEmpty { null },
          cancerType = draft.cancerType.trim().ifEmpty { null },
          hospital =
              if (draft.isPatient) draft.hospital.trim().ifEmpty { null } else original.hospital,
          dateOfBirth = draft.dateOfBirth,
      )

  /** Navigation consumes completion once, so recomposition cannot pop another screen. */
  fun consumeSaved() {
    _uiState.value = _uiState.value.copy(save = SavePhase.EDITING)
  }

  private fun reset(status: EditProfileStatus) {
    generation++
    saving?.cancel()
    profile = null
    firstNameEdited = false
    familyNameEdited = false
    cancerTypeEdited = false
    hospitalEdited = false
    dateOfBirthEdited = false
    _uiState.value = EditProfileUiState(status = status, today = LocalDate.now(clock))
  }
}
