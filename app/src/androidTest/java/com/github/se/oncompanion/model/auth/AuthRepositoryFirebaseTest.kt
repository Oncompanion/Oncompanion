package com.github.se.oncompanion.model.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs [AuthRepositoryFirebase] against the Firebase Auth emulator (port 9099). */
@RunWith(AndroidJUnit4::class)
class AuthRepositoryFirebaseTest {

  private companion object {
    const val TIMEOUT_MS = 10_000L
  }

  private lateinit var auth: FirebaseAuth
  private lateinit var repository: AuthRepositoryFirebase

  // Unique identity per test so that tests sharing the emulator never collide.
  private val suffix = UUID.randomUUID().toString().take(8)
  private val sub = "alice-$suffix"
  private val email = "alice-$suffix@example.com"
  private val aliceToken =
      FirebaseEmulator.fakeGoogleIdToken(
          sub = sub,
          email = email,
          name = "Alice Martin",
          givenName = "Alice",
      )

  @Before
  fun setUp() {
    auth = FirebaseEmulator.auth
    auth.signOut()
    repository = AuthRepositoryFirebase(auth)
  }

  @After
  fun tearDown() {
    auth.signOut()
  }

  @Test
  fun currentUser_isNullWhenSignedOut() {
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithGoogle_returnsUserWithProfileFields() = runBlocking {
    val user = repository.signInWithGoogle(aliceToken)

    assertFalse(user.uid.isEmpty())
    assertEquals(email, user.email)
    assertEquals("Alice Martin", user.displayName)
    assertEquals("Alice", user.givenName)
    assertEquals("Alice", user.firstNameGuess())
  }

  @Test
  fun currentUser_matchesSignedInUserWithGivenName() = runBlocking {
    val signedIn = repository.signInWithGoogle(aliceToken)

    val current = repository.currentUser
    assertNotNull(current)
    assertEquals(signedIn.uid, current!!.uid)
    assertEquals(email, current.email)
    assertEquals("Alice Martin", current.displayName)
    assertEquals("Alice", current.givenName)
    assertEquals(auth.currentUser?.uid, current.uid)
  }

  @Test
  fun currentUser_givenNameIsSharedWithOtherInstances() = runBlocking {
    repository.signInWithGoogle(aliceToken)

    // Each screen creates its own repository: onboarding must still see the given name
    val other = AuthRepositoryFirebase(auth).currentUser
    assertEquals("Alice", other?.givenName)
    assertEquals("Alice", other?.firstNameGuess())
  }

  @Test
  fun currentUser_givenNameIsClearedBySignOut() = runBlocking {
    val alice = repository.signInWithGoogle(aliceToken)
    repository.signOut()
    // Signed in again without going through signInWithGoogle (e.g. an earlier launch)
    auth.signInWithCredential(GoogleAuthProvider.getCredential(aliceToken, null)).await()

    val current = repository.currentUser
    assertEquals(alice.uid, current?.uid)
    assertNull(current?.givenName)
  }

  @Test
  fun currentUser_givenNameIsNotGivenToAnotherAccount() = runBlocking {
    repository.signInWithGoogle(aliceToken)
    // Another account signs in without going through the repository
    auth.signOut()
    val bobToken =
        FirebaseEmulator.fakeGoogleIdToken(
            sub = "bob-$suffix",
            email = "bob-$suffix@example.com",
            name = "Bob Stone",
        )
    auth.signInWithCredential(GoogleAuthProvider.getCredential(bobToken, null)).await()

    val current = repository.currentUser
    assertEquals("bob-$suffix@example.com", current?.email)
    assertNull(current?.givenName)
    assertEquals("Bob", current?.firstNameGuess())
  }

  @Test
  fun signInWithGoogle_withoutGivenNameDoesNotKeepPreviousOne() = runBlocking {
    repository.signInWithGoogle(aliceToken)
    repository.signOut()
    val carolToken =
        FirebaseEmulator.fakeGoogleIdToken(
            sub = "carol-$suffix",
            email = "carol-$suffix@example.com",
            name = "Carol Hill",
        )

    val carol = repository.signInWithGoogle(carolToken)

    assertNull(carol.givenName)
    assertNull(repository.currentUser?.givenName)
  }

  @Test
  fun signInWithGoogle_sameTokenTwiceReusesAccount() = runBlocking {
    val first = repository.signInWithGoogle(aliceToken)
    repository.signOut()
    val second = repository.signInWithGoogle(aliceToken)

    assertEquals(first.uid, second.uid)
  }

  @Test
  fun signInWithGoogle_differentAccountsGetDifferentUids() = runBlocking {
    val alice = repository.signInWithGoogle(aliceToken)
    repository.signOut()
    val bobToken =
        FirebaseEmulator.fakeGoogleIdToken(sub = "bob-$suffix", email = "bob-$suffix@example.com")
    val bob = repository.signInWithGoogle(bobToken)

    assertFalse(alice.uid == bob.uid)
    assertEquals("bob-$suffix@example.com", bob.email)
  }

  @Test
  fun signOut_clearsCurrentUser() = runBlocking {
    repository.signInWithGoogle(aliceToken)
    assertNotNull(repository.currentUser)

    repository.signOut()

    assertNull(repository.currentUser)
  }

  @Test
  fun signOut_whenNobodySignedInDoesNotThrow() {
    assertNull(repository.currentUser)
    repository.signOut()
    repository.signOut()
    assertNull(repository.currentUser)
  }

  @Test
  fun observeCurrentUser_emitsCurrentStateImmediately() = runBlocking {
    assertNull(withTimeout(TIMEOUT_MS) { repository.observeCurrentUser().first() })

    val signedIn = repository.signInWithGoogle(aliceToken)
    val emitted = withTimeout(TIMEOUT_MS) { repository.observeCurrentUser().first() }
    assertEquals(signedIn.uid, emitted?.uid)
  }

  @Test
  fun observeCurrentUser_emitsNullThenUserThenNull() = runBlocking {
    val emissions = mutableListOf<AuthUser?>()
    val job = launch { repository.observeCurrentUser().take(3).toList(emissions) }

    // Wait for the initial "signed out" emission before signing in.
    withTimeout(TIMEOUT_MS) { while (emissions.isEmpty()) kotlinx.coroutines.delay(20) }
    val signedIn = repository.signInWithGoogle(aliceToken)
    withTimeout(TIMEOUT_MS) { while (emissions.size < 2) kotlinx.coroutines.delay(20) }
    repository.signOut()
    withTimeout(TIMEOUT_MS) { job.join() }

    assertEquals(3, emissions.size)
    assertNull(emissions[0])
    assertEquals(signedIn.uid, emissions[1]?.uid)
    assertEquals(email, emissions[1]?.email)
    assertNull(emissions[2])
  }

  @Test
  fun signInWithGoogle_invalidTokenThrows() = runBlocking {
    try {
      repository.signInWithGoogle("not-a-token")
      fail("Expected signInWithGoogle to throw on an invalid token")
    } catch (e: Exception) {
      // expected
    }
    assertNull(repository.currentUser)
  }
}
