package com.github.se.oncompanion.model.auth

import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignedInUidTest {

  /** An [AuthRepository] whose current user can't be read. */
  private class BrokenAuthRepository : AuthRepository {
    override val currentUser: AuthUser?
      get() = throw IllegalStateException("Firebase isn't initialized")

    override fun observeCurrentUser(): Flow<AuthUser?> = flowOf(null)

    override suspend fun signInWithGoogle(idToken: String): AuthUser =
        throw UnsupportedOperationException()

    override fun signOut() = Unit
  }

  @Test
  fun signedInUidIsTheUidOfTheSignedInUser() = runTest {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "patient-1") })
    auth.signInWithGoogle("token")

    assertEquals("patient-1", auth.signedInUid("Test"))
  }

  @Test
  fun signedInUidIsNullWhenNobodyIsSignedIn() {
    assertNull(FakeAuthRepository().signedInUid("Test"))
  }

  @Test
  fun signedInUidIsNullWhenTheUserCannotBeRead() {
    assertNull(BrokenAuthRepository().signedInUid("Test"))
  }
}
