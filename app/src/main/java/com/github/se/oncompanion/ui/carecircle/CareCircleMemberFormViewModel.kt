// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.auth.signedInUser
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.EmailAlreadyInCircle
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.model.carecircle.emailAlreadyInCircle
import com.github.se.oncompanion.model.carecircle.isSameEmail
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.ui.common.SavePhase
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The draft of the care circle member form: what the patient typed and chose, and where saving is.
 *
 * @property relationship null until the patient picks one, so a missing relationship can be shown
 * @property email in edit mode, the member's account email, shown but not editable
 * @property permissions what the person will see; none until the patient turns them on
 * @property canAdd the sections the person can also add to; only sections they see, none until the
 *   patient turns them on
 * @property duplicate adding: who in the circle already has this email, if anyone (#257). Kept up
 *   to date as the email, the circle and the time change; never set when editing
 * @property accountEmail adding: the signed-in patient's own email, null when editing or if their
 *   account has none
 * @property firstNameTouched whether the first name was ever changed: a blank first name is only
 *   shown as an error once the patient has typed in it, so a new form doesn't start out red
 * @property isEditing whether the form edits a member of the circle instead of adding someone
 * @property isLoading in edit mode, waiting for the member's details to fill in the form
 * @property notFound in edit mode, the member isn't in the circle (anymore), e.g. removed elsewhere
 * @property loadFailed in edit mode, the member couldn't be read
 * @property loadedDetails in edit mode, the member's details as filled in, to tell whether anything
 *   changed
 * @property savedInviteCode the invite code of the invitation just created, once [save] is
 *   [SavePhase.SAVED]
 * @property saveFailed whether the last save failed; the draft is kept so the patient can retry
 */
data class CareCircleMemberFormUiState(
    val firstName: String = "",
    val familyName: String = "",
    val relationship: Relationship? = null,
    val email: String = "",
    val permissions: Set<CarePermission> = emptySet(),
    val canAdd: Set<CarePermission> = emptySet(),
    val duplicate: EmailAlreadyInCircle? = null,
    val accountEmail: String? = null,
    val firstNameTouched: Boolean = false,
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    val notFound: Boolean = false,
    val loadFailed: Boolean = false,
    val loadedDetails: CareCircleMemberDetails? = null,
    val save: SavePhase = SavePhase.EDITING,
    val savedInviteCode: String? = null,
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

  /**
   * Whether the patient typed their own email. A patient account can't join a circle, so that
   * invitation could never be used.
   */
  val isOwnEmail: Boolean
    get() = isSameEmail(email, accountEmail)

  /** Whether this email can't be invited: it's the patient's own, or already in the circle. */
  val emailRefused: Boolean
    get() = isOwnEmail || duplicate != null

  /** Whether the form is shown: not while the member to edit is loading or missing. */
  val showsForm: Boolean
    get() = !isLoading && !notFound && !loadFailed

  /** Whether the fields can still change: not while saving, nor once saved. */
  val canEdit: Boolean
    get() = showsForm && save == SavePhase.EDITING

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
          canAdd = canAdd,
      )
    }

  /** In edit mode, whether the form still holds the member's details as loaded. */
  val unchanged: Boolean
    get() = loadedDetails != null && details == loadedDetails

  /**
   * Whether "Send invitation" (or "Save changes") is enabled: when editing, only once something
   * changed, so tapping it can't rewrite the same details. Someone already in the circle with this
   * email is never invited again: the patient opens their invitation instead.
   */
  val canSave: Boolean
    get() =
        canEdit &&
            !emailRefused &&
            !missingFirstName &&
            !missingRelationship &&
            !invalidEmail &&
            !unchanged
}

/**
 * The form to add someone to the signed-in patient's care circle (#79), or to edit a member (#75):
 * their name, relationship, an email and what they can see.
 * - Adding: saving creates an invitation; the person becomes a member once they use its invite code
 *   (see docs/architecture.md, "Care circle invitations"). An email already in the circle is
 *   refused, and its invitation, if any, can be opened instead (#257).
 * - Editing [memberUid]: the form is filled in with the member's details once, then saving updates
 *   them. The email is their account email, which the patient can't change.
 *
 * @param memberUid the member to edit, or null to add someone
 * @param clock tells whether an invitation with the same email has expired; tests pass one they
 *   control
 */
class CareCircleMemberFormViewModel(
    private val inviteRepository: CareCircleInviteRepository =
        CareCircleInviteRepositoryProvider.repository,
    private val profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val careCircleRepository: CareCircleRepository =
        CareCircleRepositoryProvider.repository,
    private val memberUid: String? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

  private val _uiState =
      MutableStateFlow(
          CareCircleMemberFormUiState(isEditing = memberUid != null, isLoading = memberUid != null)
      )
  val uiState: StateFlow<CareCircleMemberFormUiState> = _uiState.asStateFlow()

  /** Bumped to check expiry again without new data from the repositories. */
  private val expiryChecks = MutableStateFlow(0)

  private var duplicateCheck: Job? = null

  /** What the email is compared with: the circle as last read, null until it has been read. */
  private val circle = MutableStateFlow<Circle?>(null)

  private data class Circle(
      val members: List<CareCircleMember>,
      val invites: List<CareCircleInvite>,
  )

  init {
    // Editing never compares the email: it is the member's own, and can't be changed
    if (memberUid != null) fillInMember(memberUid) else checkForDuplicates()
  }

  /**
   * Checks again whether an invitation with this email has expired. The screen calls it when it
   * resumes: the timer below doesn't run while the phone sleeps, and the user may change the clock.
   */
  fun refreshExpiry() {
    expiryChecks.update { it + 1 }
  }

  /**
   * Keeps [circle] and [CareCircleMemberFormUiState.duplicate] up to date while adding someone:
   * when the email changes, when the circle changes (from the local cache when offline), and when
   * an invitation with this email expires. A list that can't be read counts as empty: the rules
   * can't refuse a duplicate either, so not being able to check must not stop the patient from
   * inviting someone.
   */
  private fun checkForDuplicates() {
    val user = authRepository.signedInUser(TAG) ?: return
    val uid = user.uid
    _uiState.update { it.copy(accountEmail = user.email) }
    duplicateCheck = viewModelScope.launch {
      launch {
        combine(
                careCircleRepository.observeMembers(uid).emptyOnFailure(),
                inviteRepository.observePendingInvites(uid).emptyOnFailure(),
                ::Circle,
            )
            .collect { circle.value = it }
      }
      combine(
              circle.filterNotNull(),
              // Checked again when a failed save gives the form back
              _uiState.map { it.email to it.save }.distinctUntilChanged(),
              expiryChecks,
          ) { circle, (email, save), _ ->
            (email to circle).takeIf { save == SavePhase.EDITING }
          }
          // New data cancels the wait for the next expiry and starts again from it
          .collectLatest { check ->
            // Saving: the invitation being saved comes back, and isn't a duplicate
            val (email, circle) = check ?: return@collectLatest
            // Only an invitation with this email can change the result
            val expiries =
                circle.invites.filter { isSameEmail(it.email, email) }.map { it.expiresAt }
            repeatAtEachExpiry(clock, expiries) { now ->
              val duplicate = emailAlreadyInCircle(email, circle.members, circle.invites, now)
              _uiState.update {
                if (it.save == SavePhase.EDITING) it.copy(duplicate = duplicate) else it
              }
            }
          }
    }
  }

  /**
   * Who already has [email], from the circle as last read: the duplicate shown may not have caught
   * up with it yet. Waits for the circle's first read (from the cache when offline, so it doesn't
   * wait for the network), so sending can't skip the check, but only for
   * [FIRST_READ_TIMEOUT_MILLIS]: not being able to check must not stop the invitation. Never
   * checked when editing or when nobody is signed in.
   */
  private suspend fun duplicateWhenSaving(email: String): EmailAlreadyInCircle? {
    if (duplicateCheck == null) return null
    val (members, invites) =
        withTimeoutOrNull(FIRST_READ_TIMEOUT_MILLIS) { circle.filterNotNull().first() }
            ?: return null
    return emailAlreadyInCircle(email, members, invites, clock.instant())
  }

  private fun <T> Flow<List<T>>.emptyOnFailure(): Flow<List<T>> = catch { e ->
    // Only the type: a message could name a document, and an invite's ID is its invite code
    Log.w(TAG, "Couldn't read the circle to compare the email with: ${e.javaClass.simpleName}")
    emit(emptyList())
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
          if (state.save != SavePhase.EDITING) return@collect
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
                            canAdd = member.canAdd,
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
    val email = value.take(CareCircleInvite.MAX_EMAIL_LENGTH)
    // A new email drops the old one's warning at once; the same email wouldn't be compared again
    if (email == it.email) it else it.copy(email = email, duplicate = null)
  }

  /**
   * Gives or takes away access to [permission]. Hiding a section also stops the person from adding
   * to it.
   */
  fun onPermissionChange(permission: CarePermission, granted: Boolean) = edit {
    if (granted) it.copy(permissions = it.permissions + permission)
    else it.copy(permissions = it.permissions - permission, canAdd = it.canAdd - permission)
  }

  /** Lets the person add to [section] for the patient, or not. Only for a section they see. */
  fun onCanAddChange(section: CarePermission, allowed: Boolean) = edit {
    if (!allowed) it.copy(canAdd = it.canAdd - section)
    else if (section in it.permissions) it.copy(canAdd = it.canAdd + section) else it
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
    _uiState.value = state.copy(save = SavePhase.SAVING, saveFailed = false)
    viewModelScope.launch {
      duplicateWhenSaving(state.email)?.let { duplicate ->
        _uiState.update { it.copy(save = SavePhase.EDITING, duplicate = duplicate) }
        return@launch
      }
      // The new invite code when adding, null when editing
      val saved: Result<String?> =
          try {
            Result.success(saveForm(details, email = state.email.trim().ifEmpty { null }))
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            // Only the type: the message could name the invite's document, i.e. its code
            Log.w(TAG, "Couldn't save the care circle member: ${e.javaClass.simpleName}")
            Result.failure(e)
          }
      saved.fold(
          onSuccess = { inviteCode ->
            // The screen is leaving: the new invitation must not show up as a duplicate
            duplicateCheck?.cancel()
            _uiState.update { it.copy(save = SavePhase.SAVED, savedInviteCode = inviteCode) }
          },
          // Back to editing, which checks the email against the circle again
          onFailure = { _uiState.update { it.copy(save = SavePhase.EDITING, saveFailed = true) } },
      )
    }
  }

  /**
   * Updates the member being edited with [details], or invites someone new with them and [email].
   * Returns the new invite code, null when editing.
   */
  private suspend fun saveForm(details: CareCircleMemberDetails, email: String?): String? {
    val uid = checkNotNull(authRepository.signedInUid(TAG)) { "Nobody is signed in" }
    if (memberUid != null) {
      careCircleRepository.updateMember(uid, memberUid, details)
      return null
    }
    return createInvite(uid, details, email)
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
    _uiState.value = _uiState.value.copy(save = SavePhase.EDITING, savedInviteCode = null)
  }

  private companion object {
    const val TAG = "CareCircleMemberForm"

    /** How long Send waits for the circle's first read before sending without the check. */
    const val FIRST_READ_TIMEOUT_MILLIS = 3_000L
  }
}
