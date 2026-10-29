// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.auth

import androidx.lifecycle.SavedStateHandle
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.model.session.FakeLocalSessionRepository
import org.junit.Assert.*
import org.junit.Test

/** Root saved state must follow the same privacy boundary as retained navigation ViewModels. */
class SessionStoresTest {
  private val signOut = SignOut(FakeAuthRepository(), FakeLocalSessionRepository())

  @Test
  fun sameUserProcessRestorationKeepsChoiceButNextGenerationClearsIt() {
    val state = SavedStateHandle(mapOf("session_uid" to "one", "patient" to "private"))
    val stores = SessionStores(signOut, state)
    val first = stores.owner(0, "one")
    assertEquals("private", state.get<String>("patient"))
    assertSame(first, stores.owner(0, "one"))
    val next = stores.owner(1, "one")
    assertNotSame(first, next)
    assertNull(state.get<String>("patient"))
  }

  @Test
  fun anotherUsersRestoredStateAndExplicitCleanupCannotLeakIntoFreshStore() {
    val state = SavedStateHandle(mapOf("session_uid" to "one", "patient" to "private"))
    val stores = SessionStores(signOut, state)
    val first = stores.owner(0, "two")
    assertNull(state.get<String>("patient"))
    state["patient"] = "second private choice"
    stores.clear()
    assertTrue(state.keys().isEmpty())
    assertNotSame(first, stores.owner(0, "two"))
  }

  @Test
  fun freshSignInRetainsItsNavigationWhileDistinctAuthenticatedUsersNeverShare() {
    val stores = SessionStores(signOut, SavedStateHandle())
    val login = stores.owner(0, null)
    assertSame(login, stores.owner(0, "one"))
    val nextLogin = stores.owner(0, null)
    assertNotSame(login, nextLogin)
    assertSame(nextLogin, stores.owner(0, "two"))
    assertNotSame(nextLogin, stores.owner(0, "one"))
  }
}
