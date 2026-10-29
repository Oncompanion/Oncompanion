// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.ui.navigation.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What the care circle member screen shows. */
sealed interface CareCircleMemberUiState {
  /** Waiting for the first result (from the local cache or the server). */
  data object Loading : CareCircleMemberUiState

  /**
   * The member's details.
   *
   * @property updatedJustNow whether the patient saved changes to the member during this visit
   * @property confirmingRemoval whether the "Remove from your circle?" dialog is open
   * @property isRemoving whether the removal was confirmed and is being saved
   * @property removeFailed whether the last removal failed; the member is still in the circle
   */
  data class Member(
      val member: CareCircleMember,
      val updatedJustNow: Boolean = false,
      val confirmingRemoval: Boolean = false,
      val isRemoving: Boolean = false,
      val removeFailed: Boolean = false,
  ) : CareCircleMemberUiState

  /** The patient removed the member: the screen leaves. */
  data object Removed : CareCircleMemberUiState

  /**
   * The member isn't in the circle (anymore): they were removed, possibly from another device, or
   * their document has no first name. Retrying wouldn't help.
   */
  data object NotFound : CareCircleMemberUiState

  /**
   * The member couldn't be read: nobody is signed in, the screen was opened without a member, or
   * Firestore refused the read (e.g. security rules). Being offline is not an error: the local
   * cache answers.
   */
  data object Error : CareCircleMemberUiState
}

/**
 * Shows one member of the signed-in user's care circle (US-14), and lets the patient remove them
 * after confirming. The member's uid is the [Screen.CARE_CIRCLE_MEMBER_ARG] navigation argument,
 * read from [savedStateHandle].
 *
 * The dialog's state lives here rather than in the screen, so the screen's content stays stateless
 * and each step can be tested.
 */
class CareCircleMemberViewModel(
    savedStateHandle: SavedStateHandle,
    private val careCircleRepository: CareCircleRepository =
        CareCircleRepositoryProvider.repository,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
) : ViewModel() {

  private val memberUid: String? = savedStateHandle[Screen.CARE_CIRCLE_MEMBER_ARG]

  private val _uiState = MutableStateFlow<CareCircleMemberUiState>(CareCircleMemberUiState.Loading)
  val uiState: StateFlow<CareCircleMemberUiState> = _uiState.asStateFlow()

  private val updatedJustNow = MutableStateFlow(false)

  private var observeJob: Job? = null

  init {
    observeMember()
  }

  /**
   * Called once the edit form saved this member, so the screen says "Updated just now" until the
   * patient leaves it.
   */
  fun onMemberUpdated() {
    updatedJustNow.value = true
  }

  /** Reads the member again after an [CareCircleMemberUiState.Error]. */
  fun retry() {
    observeMember()
  }

  /** Opens the "Remove from your circle?" dialog. */
  fun askRemove() = updateMember { if (it.isRemoving) it else it.copy(confirmingRemoval = true) }

  /** Closes the dialog without removing anyone. */
  fun dismissRemove() = updateMember { it.copy(confirmingRemoval = false) }

  /**
   * Removes the member from the circle, once the patient confirmed. Returns once saved on the
   * device, also offline (see [CareCircleRepository.removeMember]); the state is then
   * [CareCircleMemberUiState.Removed].
   */
  fun confirmRemove() {
    val state = _uiState.value as? CareCircleMemberUiState.Member ?: return
    if (!state.confirmingRemoval || state.isRemoving) return
    _uiState.value = state.copy(confirmingRemoval = false, isRemoving = true, removeFailed = false)
    viewModelScope.launch {
      try {
        val ownerUid = checkNotNull(authRepository.currentUser?.uid) { "Nobody is signed in" }
        careCircleRepository.removeMember(ownerUid, state.member.uid)
        _uiState.value = CareCircleMemberUiState.Removed
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't remove the care circle member", e)
        updateMember { it.copy(isRemoving = false, removeFailed = true) }
      }
    }
  }

  private fun updateMember(
      change: (CareCircleMemberUiState.Member) -> CareCircleMemberUiState.Member
  ) {
    val state = _uiState.value
    if (state is CareCircleMemberUiState.Member) _uiState.value = change(state)
  }

  /**
   * The state after the listener emitted [next]: the dialog stays as it was, and a member gone
   * while being removed is [CareCircleMemberUiState.Removed], not "no longer in your circle".
   */
  private fun merged(next: CareCircleMemberUiState): CareCircleMemberUiState {
    val current = _uiState.value
    return when {
      current == CareCircleMemberUiState.Removed -> current
      current !is CareCircleMemberUiState.Member -> next
      next is CareCircleMemberUiState.Member ->
          current.copy(member = next.member, updatedJustNow = next.updatedJustNow)
      current.isRemoving -> CareCircleMemberUiState.Removed
      else -> next
    }
  }

  private fun observeMember() {
    // Never two listeners updating the state
    observeJob?.cancel()
    _uiState.value = CareCircleMemberUiState.Loading
    observeJob = viewModelScope.launch {
      try {
        val ownerUid = authRepository.currentUser?.uid
        if (ownerUid == null || memberUid.isNullOrBlank()) {
          _uiState.value = CareCircleMemberUiState.Error
          return@launch
        }
        careCircleRepository
            .observeMember(ownerUid, memberUid)
            .combine(updatedJustNow) { member, updated ->
              if (member == null) CareCircleMemberUiState.NotFound
              else CareCircleMemberUiState.Member(member, updatedJustNow = updated)
            }
            .catch { _uiState.value = CareCircleMemberUiState.Error }
            .collect { _uiState.value = merged(it) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // e.g. Firebase not available
        _uiState.value = CareCircleMemberUiState.Error
      }
    }
  }

  private companion object {
    const val TAG = "CareCircleMember"
  }
}
