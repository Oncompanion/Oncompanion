// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.auth.SessionMemory
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.model.session.RejectedWritesProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What [RejectedWriteSnackbar] shows.
 *
 * @property unseenRejections how many changes were undone because the server rejected them, that
 *   the user hasn't been told about yet; the snackbar shows while it is above 0
 */
data class RejectedWriteUiState(val unseenRejections: Int = 0)

/**
 * Tells the user when the server rejected a change that was already saved on the device, whichever
 * screen made it. Rejections are forgotten at sign-out, so the next user never sees them.
 */
class RejectedWriteViewModel(
    private val rejectedWrites: RejectedWrites = RejectedWritesProvider.rejectedWrites,
    private val signOut: SignOut = SignOutProvider.signOut,
) : ViewModel() {

  private val forgetOnSignOut = SessionMemory { rejectedWrites.forget() }

  /** The rejections the user hasn't been told about yet. */
  val uiState: StateFlow<RejectedWriteUiState> =
      rejectedWrites.unseen
          .map(::RejectedWriteUiState)
          .stateIn(
              viewModelScope,
              SharingStarted.Eagerly,
              RejectedWriteUiState(rejectedWrites.unseen.value),
          )

  init {
    signOut.register(forgetOnSignOut)
  }

  /** The snackbar told the user about [count] rejections; later ones still get their message. */
  fun onShown(count: Int) {
    rejectedWrites.acknowledge(count)
  }

  override fun onCleared() {
    signOut.unregister(forgetOnSignOut)
  }
}
