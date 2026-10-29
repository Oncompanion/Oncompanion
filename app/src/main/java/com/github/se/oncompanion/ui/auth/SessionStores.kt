// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.github.se.oncompanion.domain.auth.SessionMemory
import com.github.se.oncompanion.domain.auth.SignOut

/** Retains session navigation across recreation and destroys it before another session opens. */
internal class SessionStores(private val signOut: SignOut, val savedState: SavedStateHandle) :
    ViewModel(), SessionMemory {
  private var identity: Pair<Long, String?>? = null
  private var current: ViewModelStoreOwner? = null
  /** An external account change needs fresh route resolution for the new user's role. */
  var accountChanged = false
    private set

  init {
    signOut.register(this)
  }

  /** Returns a store unique to this cleanup generation and authenticated user. */
  fun owner(generation: Long, uid: String?): ViewModelStoreOwner {
    val next = generation to uid
    // Signing in promotes the fresh unauthenticated graph, preserving its destination callback.
    // A previous authenticated store was already destroyed on sign-out or generation change.
    if (
        identity?.first == generation && identity?.second == null && uid != null && current != null
    ) {
      identity = next
      savedState["session_uid"] = uid
      return checkNotNull(current)
    }
    if (identity != next || current == null) {
      accountChanged = identity != null && identity?.second != uid
      // Restore only the same user's saved root state after process death.
      if (identity != null || savedState.get<String>("session_uid") != uid) clear()
      savedState["session_uid"] = uid
      identity = next
      current =
          object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
          }
    }
    return checkNotNull(current)
  }

  override fun clear() {
    current?.viewModelStore?.clear()
    current = null
    savedState.keys().forEach { savedState.remove<Any?>(it) }
  }

  override fun onCleared() {
    clear()
    signOut.unregister(this)
  }
}
