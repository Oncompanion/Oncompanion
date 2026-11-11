// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.domain.auth.SignOutState
import com.github.se.oncompanion.ui.onboarding.AccountSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Use another account" on a screen the user is stuck on, like the account check (#204): signs out
 * so they can pick another Google account, asking before discarding local changes that may not be
 * saved online, as onboarding does (#281).
 */
class AccountSwitchViewModel(private val signOut: SignOut = SignOutProvider.signOut) : ViewModel() {

  private val _state = MutableStateFlow<AccountSwitch?>(null)

  /** Where switching is, or null until "Use another account" (and once the user stayed). */
  val state: StateFlow<AccountSwitch?> = _state.asStateFlow()

  init {
    // Follows the sign-out this screen started, never one started elsewhere
    viewModelScope.launch {
      signOut.state.collect { phase ->
        if (_state.value == null) return@collect
        _state.value =
            when (phase) {
              SignOutState.NEEDS_DISCARD_CONFIRMATION -> AccountSwitch.CONFIRMING_DISCARD
              SignOutState.COMPLETE -> AccountSwitch.DONE
              // Stayed signed in
              SignOutState.IDLE -> null
              else -> AccountSwitch.SIGNING_OUT
            }
      }
    }
  }

  /**
   * Signs out so the user can pick another Google account: [clearCredentials] forgets the chosen
   * one, so the account picker shows again. Does nothing while already switching.
   */
  fun useAnotherAccount(clearCredentials: suspend () -> Unit) {
    if (_state.value != null) return
    _state.value = AccountSwitch.SIGNING_OUT
    signOut.begin(clearCredentials)
  }

  /** Discards the local changes that may not be saved online, and signs out. */
  fun discardChangesAndSwitch(clearCredentials: suspend () -> Unit) {
    if (_state.value != AccountSwitch.CONFIRMING_DISCARD) return
    signOut.discard(clearCredentials)
  }

  /** Keeps this account and its local changes. */
  fun stayOnThisAccount() {
    if (_state.value != AccountSwitch.CONFIRMING_DISCARD) return
    signOut.staySignedIn()
  }
}
