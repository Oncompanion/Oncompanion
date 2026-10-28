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
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
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
 * @property email in edit mode, the member's account email, shown but not editable
 * @property permissions what the person will see; none until the patient turns them on
 * @property firstNameTouched whether the first name was ever changed: a blank first name is only
 *   shown as an error once the patient has typed in it, so a new form doesn't start out red
 * @property isEditing whether the form edits a member of the circle instead of adding someone
 * @property isLoading in edit mode, waiting for the member's details to fill in the form
 * @property notFound in edit mode, the member isn't in the circle (anymore), e.g. removed elsewhere
 * @property loadFailed in edit mode, the member couldn't be read
 * @property loadedDetails in edit mode, the member's details as filled in, to tell whether anything
 *   changed
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
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    val notFound: Boolean = false,
    val loadFailed: Boolean = false,
    val loadedDetails: CareCircleMemberDetails? = null,
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

  /**
   * Whether an email was typed that doesn't look like one. An empty email is fine, and a member's
   * account email isn't checked: the patient can't change it.
   */
  val invalidEmail: Boolean
    get() = !isEditing && email.isNotBlank() && !CareCircleInvite.isValidEmail(email.trim())

  /** Whether the form is shown: not while the member to edit is loading or missing. */
  val showsForm: Boolean
    get() = !isLoading && !notFound && !loadFailed

  /** Whether the fields can still change: not while saving, nor once saved. */
  val canEdit: Boolean
    get() = showsForm && !isSaving && !isSaved

  /**
   * What saving stores: the names trimmed, a blank last name left out. Null without relationship.
   */
  val details: CareCircleMemberDetails?
    get() = relationship?.let {
      CareCircleMemberDetails(
          firstName = firstName.trim(),
          familyName = familyName.trim().ifEmpty { null },
          relationship = it,
          permissions = permissions,
      )
    }

  /** In edit mode, whether the form still holds the member's details as loaded. */
  val unchanged: Boolean
    get() = loadedDetails != null && details == loadedDetails

  /**
   * Whether "Send invitation" (or "Save changes") is enabled: when editing, only once something
   * changed, so tapping it can't rewrite the same details.
   */
  val canSave: Boolean
    get() = canEdit && !missingFirstName && !missingRelationship && !invalidEmail && !unchanged
}

/**
 * The form to add someone to the signed-in patient's care circle (#79), or to edit a member (#75):
 * their name, relationship, an email and what they can see.
 * - Adding: saving creates an invitation; the person becomes a member once they use its invite code
 *   (see docs/architecture.md, "Care circle invitations").
 * - Editing [memberUid]: the form is filled in with the member's details once, then saving updates
 *   them. The email is their account email, which the patient can't change.
 *
 * @param memberUid the member to edit, or null to add someone
 */
class CareCircleMemberFormViewModel(
    private val inviteRepository: CareCircleInviteRepository =
        CareCircleInviteRepositoryProvider.repository,
    private val profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val careCircleRepository: CareCircleRepository =
        CareCircleRepositoryProvider.repository,
    private val memberUid: String? = null,
) : ViewModel() {

  private val _uiState =
      MutableStateFlow(
          CareCircleMemberFormUiState(isEditing = memberUid != null, isLoading = memberUid != null)
      )
  val uiState: StateFlow<CareCircleMemberFormUiState> = _uiState.asStateFlow()

  init {
    memberUid?.let(::fillInMember)
  }

  /**
   * Fills in the form with the member's details once. Later changes don't overwrite what the
   * patient is typing, but a member who leaves the circle makes the form [notFound].
   */
  private fun fillInMember(memberUid: String) {
    viewModelScope.launch {
      try {
        val ownerUid = checkNotNull(authRepository.signedInUid(TAG)) { "Nobody is signed in" }
        require(memberUid.isNotBlank()) { "No member to edit" }
        careCircleRepository.observeMember(ownerUid, memberUid).collect { member ->
          val state = _uiState.value
          // The member saved from this form comes back: the screen is leaving
          if (state.isSaving || state.isSaved) return@collect
          _uiState.value =
              when {
                member == null -> state.copy(isLoading = false, notFound = true)
                state.isLoading ->
                    state
                        .copy(
                            firstName = member.firstName,
                            familyName = member.familyName.orEmpty(),
                            relationship = member.relationship,
                            email = member.email.orEmpty(),
                            permissions = member.permissions,
                            isLoading = false,
                        )
                        // Trimmed like a save, so an untouched form counts as unchanged
                        .let { it.copy(loadedDetails = it.details) }
                else -> state
              }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the member to edit", e)
        _uiState.value = _uiState.value.copy(isLoading = false, loadFailed = true)
      }
    }
  }

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
   * Adding: creates the invitation, with the patient's first name copied in so the person sees
   * whose circle they join. Editing: updates the member's name, relationship and access. Does
   * nothing unless [CareCircleMemberFormUiState.canSave], which also drops a second tap. Returns
   * once saved on the device, also offline.
   */
  fun save() {
    val state = _uiState.value
    if (!state.canSave) return
    val details = state.details ?: return
    _uiState.value = state.copy(isSaving = true, saveFailed = false)
    viewModelScope.launch {
      // The new invite code when adding, null when editing
      val saved: Result<String?> =
          try {
            val uid = checkNotNull(authRepository.signedInUid(TAG)) { "Nobody is signed in" }
            Result.success(
                if (memberUid != null) {
                  careCircleRepository.updateMember(uid, memberUid, details)
                  null
                } else {
                  createInvite(uid, details, email = state.email.trim().ifEmpty { null })
                }
            )
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Couldn't save the care circle member", e)
            Result.failure(e)
          }
      _uiState.value =
          saved.fold(
              onSuccess = { inviteCode ->
                _uiState.value.copy(isSaving = false, isSaved = true, savedInviteCode = inviteCode)
              },
              onFailure = { _uiState.value.copy(isSaving = false, saveFailed = true) },
          )
    }
  }

  /** Creates [ownerUid]'s invitation for [details] and returns its invite code. */
  private suspend fun createInvite(
      ownerUid: String,
      details: CareCircleMemberDetails,
      email: String?,
  ): String {
    // The cached profile answers at once, also offline
    val owner =
        profileRepository.getCachedProfile(ownerUid)
            ?: profileRepository.getProfile(ownerUid)
            ?: error("The patient has no profile")
    return inviteRepository
        .createInvite(
            ownerUid = ownerUid,
            ownerFirstName = owner.firstName,
            details = details,
            email = email,
        )
        .inviteCode
  }

  /** Called once the screen has left after saving, so recomposition can't leave a second time. */
  fun consumeSaved() {
    _uiState.value = _uiState.value.copy(isSaved = false, savedInviteCode = null)
  }

  private companion object {
    const val TAG = "CareCircleMemberForm"
  }
}
