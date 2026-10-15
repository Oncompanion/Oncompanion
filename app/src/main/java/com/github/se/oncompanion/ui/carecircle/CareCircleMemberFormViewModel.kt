// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The draft of the care circle member form: what the patient typed and chose, and where saving is.
 *
 * @property relationship null until the patient picks one, so a missing relationship can be shown
 * @property permissions what the person will see; none until the patient turns them on
 * @property firstNameTouched whether the first name was ever changed: a blank first name is only
 *   shown as an error once the patient has typed in it, so a new form doesn't start out red
 * @property savedInviteCode the invite code of the invitation just created, once [isSaved]
 * @property saveFailed whether the last save failed; the draft is kept so the patient can retry
 */
data class CareCircleMemberFormUiState(
    val firstName: String = "",
    val familyName: String = "",
    val relationship: Relationship? = null,
    val email: String = "",
    val permissions: Set<CarePermission> = emptySet(),
    val firstNameTouched: Boolean = false,
    val isSaving: Boolean = false,
    val savedInviteCode: String? = null,
    val isSaved: Boolean = false,
    val saveFailed: Boolean = false,
) {
  /** Whether the first name, which is required, is still blank. */
  val missingFirstName: Boolean
    get() = firstName.isBlank()

  /** Whether the relationship, which is required, hasn't been picked. */
  val missingRelationship: Boolean
    get() = relationship == null

  /** Whether an email was typed that doesn't look like one. An empty email is fine. */
  val invalidEmail: Boolean
    get() = email.isNotBlank() && !CareCircleInvite.isValidEmail(email.trim())

  /** Whether the fields can still change: not while saving, nor once saved. */
  val canEdit: Boolean
    get() = !isSaving && !isSaved

  /** Whether "Send invitation" is enabled. */
  val canSave: Boolean
    get() = canEdit && !missingFirstName && !missingRelationship && !invalidEmail
}

/**
 * The form to add someone to the signed-in patient's care circle (#79): their name, relationship,
 * an optional email to send the invitation to, and what they can see. Saving creates an invitation;
 * the person becomes a member once they use its invite code (see docs/architecture.md, "Care circle
 * invitations").
 *
 * Named "member form" rather than "add member" because editing a member (#75) uses the same form.
 */
class CareCircleMemberFormViewModel(
    private val inviteRepository: CareCircleInviteRepository =
        CareCircleInviteRepositoryProvider.repository,
    private val profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(CareCircleMemberFormUiState())
  val uiState: StateFlow<CareCircleMemberFormUiState> = _uiState.asStateFlow()

  /**
   * Changes the first name, cut to [CareCircleMemberDetails.MAX_NAME_LENGTH] (the longest name the
   * server accepts).
   */
  fun onFirstNameChange(value: String) = edit {
    it.copy(
        firstName = value.take(CareCircleMemberDetails.MAX_NAME_LENGTH),
        firstNameTouched = true,
    )
  }

  /** Changes the optional last name, cut like the first name. */
  fun onFamilyNameChange(value: String) = edit {
    it.copy(familyName = value.take(CareCircleMemberDetails.MAX_NAME_LENGTH))
  }

  /** Picks what the person is to the patient. */
  fun onRelationshipChange(relationship: Relationship) = edit {
    it.copy(relationship = relationship)
  }

  /**
   * Changes the optional email the invitation is sent to, cut to
   * [CareCircleInvite.MAX_EMAIL_LENGTH] (the longest email the server accepts).
   */
  fun onEmailChange(value: String) = edit {
    it.copy(email = value.take(CareCircleInvite.MAX_EMAIL_LENGTH))
  }

  /** Gives or takes away access to [permission]. */
  fun onPermissionChange(permission: CarePermission, granted: Boolean) = edit {
    it.copy(permissions = if (granted) it.permissions + permission else it.permissions - permission)
  }

  private fun edit(change: (CareCircleMemberFormUiState) -> CareCircleMemberFormUiState) {
    val state = _uiState.value
    if (!state.canEdit) return
    _uiState.value = change(state).copy(saveFailed = false)
  }

  /**
   * Creates the invitation, with the patient's first name copied in so the person sees whose circle
   * they join. Does nothing unless [CareCircleMemberFormUiState.canSave], which also drops a second
   * tap. Returns once saved on the device, also offline (see [CareCircleInviteRepository]).
   */
  fun save() {
    val state = _uiState.value
    if (!state.canSave) return
    val relationship = state.relationship ?: return
    _uiState.value = state.copy(isSaving = true, saveFailed = false)
    viewModelScope.launch {
      val invite =
          try {
            val uid = checkNotNull(authRepository.signedInUid(TAG)) { "Nobody is signed in" }
            // The cached profile answers at once, also offline
            val owner =
                profileRepository.getCachedProfile(uid)
                    ?: profileRepository.getProfile(uid)
                    ?: error("The patient has no profile")
            inviteRepository.createInvite(
                ownerUid = uid,
                ownerFirstName = owner.firstName,
                details =
                    CareCircleMemberDetails(
                        firstName = state.firstName.trim(),
                        familyName = state.familyName.trim().ifEmpty { null },
                        relationship = relationship,
                        permissions = state.permissions,
                    ),
                email = state.email.trim().ifEmpty { null },
            )
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Couldn't create the invitation", e)
            null
          }
      _uiState.value =
          if (invite == null) _uiState.value.copy(isSaving = false, saveFailed = true)
          else
              _uiState.value.copy(
                  isSaving = false,
                  isSaved = true,
                  savedInviteCode = invite.inviteCode,
              )
    }
  }

  /** Called once the screen has left after saving, so recomposition can't leave a second time. */
  fun consumeSaved() {
    _uiState.value = _uiState.value.copy(isSaved = false, savedInviteCode = null)
  }

  private companion object {
    const val TAG = "CareCircleMemberForm"
  }
}
