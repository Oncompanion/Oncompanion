// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.auth.SignOutState
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Source
import java.util.UUID
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator-only cache lifecycle tests use a named app so other tests never lose emulator wiring.
 */
@RunWith(AndroidJUnit4::class)
class LocalSessionRepositoryFirestoreTest {
  private lateinit var app: FirebaseApp
  private lateinit var db: FirebaseFirestore
  private lateinit var auth: FirebaseAuth
  private lateinit var local: LocalSessionRepositoryFirestore
  private lateinit var profiles: UserProfileRepositoryFirestore
  private val email = "cache-${UUID.randomUUID()}@example.com"
  private val password = "password123"

  /** Connects both named SDKs to emulators before any authentication or database operation. */
  @Before
  fun setUp() = runBlocking {
    SessionAccess.reopen()
    app =
        FirebaseApp.initializeApp(
            ApplicationProvider.getApplicationContext(),
            FirebaseApp.getInstance().options,
            "cache-${UUID.randomUUID()}",
        )
    auth =
        FirebaseAuth.getInstance(app).apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.AUTH_PORT)
        }
    restartDatabase()
    local = LocalSessionRepositoryFirestore(dbProvider = { db })
    local.prepare()
    profiles = UserProfileRepositoryFirestore { db }
  }

  private fun restartDatabase() {
    db =
        FirebaseFirestore.getInstance(app).apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FIRESTORE_PORT)
        }
  }

  private suspend fun restartAsUpgrade() {
    db.terminate().await()
    local = LocalSessionRepositoryFirestore(dbProvider = { db })
    local.prepare()
    restartDatabase()
  }

  /** Releases only this test's named cache; remote emulator documents are left intact. */
  @After
  fun tearDown() = runBlocking {
    SessionAccess.reopen()
    runCatching {
      db.terminate().await()
      db.clearPersistence().await()
    }
    if (::auth.isInitialized) auth.signOut()
    // Auth's emulator override registry retains FirebaseApp references for the process lifetime.
    // Deleting a named app here makes the next useEmulator() fail with "FirebaseApp was deleted".
  }

  private suspend fun seed(): String {
    val uid = auth.createUserWithEmailAndPassword(email, password).await().user!!.uid
    profiles.createProfile(UserProfile(uid, Role.PATIENT, "Original"))
    withTimeout(10_000) { db.waitForPendingWrites().await() }
    assertEquals("Original", profiles.getCachedProfile(uid)?.firstName)
    return uid
  }

  private suspend fun cleanup() {
    local.seal()
    auth.signOut()
    local.clear()
    restartDatabase()
    local.reopen()
  }

  /** Acknowledged remote documents survive while the old disk cache is unavailable next session. */
  @Test
  fun synchronizedCacheIsClearedAndNextSessionWorksOffline() = runBlocking {
    val uid = seed()
    local.freeze()
    withTimeout(10_000) { local.synchronize() }
    cleanup()
    assertNull(profiles.getCachedProfile(uid))
    assertTrue(
        db.firestoreSettings.cacheSettings == null ||
            db.firestoreSettings.cacheSettings is PersistentCacheSettings
    )
    val otherUid = auth.createUserWithEmailAndPassword("next-$email", password).await().user!!.uid
    profiles.createProfile(UserProfile(otherUid, Role.PATIENT, "Next"))
    withTimeout(10_000) { db.waitForPendingWrites().await() }
    db.disableNetwork().await()
    assertEquals("Next", profiles.getCachedProfile(otherUid)?.firstName)
    assertNull(profiles.getCachedProfile(uid))
    // Restart without clearing: the next user must retain an actual persistent offline store.
    restartAsUpgrade()
    db.disableNetwork().await()
    assertEquals("Next", profiles.getCachedProfile(otherUid)?.firstName)
    assertNull(profiles.getCachedProfile(uid))
    db.enableNetwork().await()
    auth.signInWithEmailAndPassword(email, password).await()
    val remote = db.collection("users").document(uid).get(Source.SERVER).await()
    assertEquals("Original", remote.getString("firstName"))
  }

  /** A legacy cache with no pending mutations needs no first-install warning, even offline. */
  @Test
  fun upgradedSynchronizedCacheAllowsOfflineSignOutWithoutMigrationMetadata() = runBlocking {
    val uid = seed()
    restartAsUpgrade()
    db.disableNetwork().await()
    assertEquals("Original", profiles.getCachedProfile(uid)?.firstName)
    val preferences =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("upgrade-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    val signOut =
        SignOut(AuthRepositoryFirebase(auth), local, this).apply {
          initialize(PreferencesCleanupJournal(preferences))
        }
    signOut.begin {}
    assertEquals(
        SignOutState.COMPLETE,
        withTimeout(10_000) {
          signOut.state.first { it != SignOutState.SYNCHRONIZING && it != SignOutState.CLEANING }
        },
    )
    restartDatabase()
    // No previous user's documents may remain accessible to the next account through the cache.
    val otherUid =
        auth.createUserWithEmailAndPassword("upgrade-next-$email", password).await().user!!.uid
    db.disableNetwork().await()
    assertNull(profiles.getCachedProfile(uid))
    profiles.createProfile(UserProfile(otherUid, Role.PATIENT, "Next offline"))
    assertEquals("Next offline", profiles.getCachedProfile(otherUid)?.firstName)
    assertNull(profiles.getCachedProfile(uid))
  }

  /** The active user's empty queue cannot authorize deleting another legacy user's pending edit. */
  @Test
  fun legacyOtherUserQueueNeedsConsentUntilItsOwnerSynchronizes() = runBlocking {
    val firstUid = seed()
    val nextEmail = "legacy-next-$email"
    val nextUid = auth.createUserWithEmailAndPassword(nextEmail, password).await().user!!.uid
    profiles.createProfile(UserProfile(nextUid, Role.PATIENT, "Next"))
    withTimeout(10_000) { db.waitForPendingWrites().await() }
    auth.signInWithEmailAndPassword(email, password).await()
    db.disableNetwork().await()
    db.collection("users").document(firstUid).update("firstName", "Legacy pending")
    assertTrue(
        db.collection("users")
            .document(firstUid)
            .get(Source.CACHE)
            .await()
            .metadata
            .hasPendingWrites()
    )
    // Older AuthRepository.signOut() did not clear Firestore or record which users owned mutations.
    auth.signOut()
    auth.signInWithEmailAndPassword(nextEmail, password).await()
    restartAsUpgrade()
    db.disableNetwork().await()
    withTimeout(10_000) { db.waitForPendingWrites().await() }
    val preferences =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("legacy-other-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    val signOut =
        SignOut(AuthRepositoryFirebase(auth), local, this).apply {
          initialize(PreferencesCleanupJournal(preferences))
        }
    signOut.begin { fail("Another user's pending queue must be preserved") }
    withTimeout(10_000) { signOut.state.first { it == SignOutState.NEEDS_DISCARD_CONFIRMATION } }
    signOut.staySignedIn()
    assertEquals(nextUid, auth.currentUser?.uid)
    // Returning to the owner proves the queue was not destroyed, and permits real acknowledgement.
    auth.signInWithEmailAndPassword(email, password).await()
    assertEquals("Legacy pending", profiles.getCachedProfile(firstUid)?.firstName)
    db.enableNetwork().await()
    withTimeout(10_000) { db.waitForPendingWrites().await() }
    assertEquals(
        "Legacy pending",
        db.collection("users").document(firstUid).get(Source.SERVER).await().getString("firstName"),
    )
    signOut.begin {}
    assertEquals(
        SignOutState.COMPLETE,
        withTimeout(10_000) {
          signOut.state.first { it != SignOutState.SYNCHRONIZING && it != SignOutState.CLEANING }
        },
    )
  }

  /** Reconnection acknowledges the offline edit and a fresh sign-out needs no discard consent. */
  @Test
  fun offlineStayReconnectSynchronizeAndSignOutPreservesAcknowledgedProfile() = runBlocking {
    val uid = seed()
    val preferences =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("reconnect-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    db.disableNetwork().await()
    // Simulate the pre-#35 app: raw SDK writes, no journal or operation tracking, persisted to
    // disk.
    db.collection("users").document(uid).update("firstName", "Reconnected")
    assertTrue(
        db.collection("users").document(uid).get(Source.CACHE).await().metadata.hasPendingWrites()
    )
    restartAsUpgrade()
    db.disableNetwork().await()
    val document = db.collection("users").document(uid)
    assertTrue(document.get(Source.CACHE).await().metadata.hasPendingWrites())
    val signOut =
        SignOut(AuthRepositoryFirebase(auth), local, this, 1_000).apply {
          initialize(PreferencesCleanupJournal(preferences))
        }
    signOut.begin { fail("Offline changes need consent before cleanup") }
    withTimeout(10_000) { signOut.state.first { it == SignOutState.NEEDS_DISCARD_CONFIRMATION } }
    signOut.staySignedIn()
    assertEquals(SignOutState.IDLE, signOut.state.value)
    assertEquals(uid, auth.currentUser?.uid)
    assertEquals("Reconnected", profiles.getCachedProfile(uid)?.firstName)

    // Verify actual emulator acknowledgement, not just the optimistic local profile value.
    db.enableNetwork().await()
    withTimeout(10_000) { db.waitForPendingWrites().await() }
    val remote = withTimeout(10_000) { document.get(Source.SERVER).await() }
    assertEquals("Reconnected", remote.getString("firstName"))
    assertFalse(remote.metadata.hasPendingWrites())
    assertFalse(document.get(Source.CACHE).await().metadata.hasPendingWrites())
    var credentialsCleared = false
    signOut.begin { credentialsCleared = true }
    val result =
        withTimeout(10_000) {
          signOut.state.first {
            it == SignOutState.COMPLETE ||
                it == SignOutState.FAILED ||
                it == SignOutState.NEEDS_DISCARD_CONFIRMATION
          }
        }
    assertEquals(SignOutState.COMPLETE, result)
    assertTrue(credentialsCleared)
    assertNull(auth.currentUser)
    restartDatabase()
    auth.signInWithEmailAndPassword(email, password).await()
    assertEquals(
        "Reconnected",
        db.collection("users").document(uid).get(Source.SERVER).await().getString("firstName"),
    )
  }

  /**
   * A failed acknowledgement preserves mutations on cancel; explicit discard removes only local
   * work.
   */
  @Test
  fun offlineCancelPreservesChangesAndExplicitDiscardDoesNotDeleteRemoteData() = runBlocking {
    val uid = seed()
    db.disableNetwork().await()
    profiles.updateProfile(UserProfile(uid, Role.PATIENT, "Unsynchronized"))
    assertEquals("Unsynchronized", profiles.getCachedProfile(uid)?.firstName)
    local.freeze()
    try {
      withTimeout(250) { local.synchronize() }
      fail("Offline changes cannot be acknowledged")
    } catch (_: TimeoutCancellationException) {}
    local.resume()
    assertEquals(uid, auth.currentUser?.uid)
    assertEquals("Unsynchronized", profiles.getCachedProfile(uid)?.firstName)
    local.freeze()
    cleanup()
    assertNull(profiles.getCachedProfile(uid))
    auth.signInWithEmailAndPassword(email, password).await()
    assertEquals(
        "Original",
        db.collection("users").document(uid).get(Source.SERVER).await().getString("firstName"),
    )
  }

  /** A successful active-user barrier cannot certify an unreadable older account queue. */
  @Test
  fun unreadableLegacyCacheNeedsConsentEvenAfterCurrentUserAcknowledgement() = runBlocking {
    unverifiableLegacyCacheRequiresConsent(java.io.IOException("Cache is locked or unreadable"))
  }

  /** Unknown schemas preserve data on cancel and only clear after explicit discard. */
  @Test
  fun unknownLegacySchemaNeedsConsentEvenWhenCurrentUserHasNoPendingWrites() = runBlocking {
    unverifiableLegacyCacheRequiresConsent(IllegalStateException("Unknown schema"))
  }

  private suspend fun unverifiableLegacyCacheRequiresConsent(error: Exception) {
    val uid = seed()
    local =
        LocalSessionRepositoryFirestore(dbProvider = { db }, inspectLegacyOwners = { throw error })
    val preferences =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("unverifiable-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    val signOut =
        SignOut(
                AuthRepositoryFirebase(auth),
                local,
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()),
            )
            .apply { initialize(PreferencesCleanupJournal(preferences)) }
    var credentialsCleared = false
    signOut.begin { credentialsCleared = true }
    withTimeout(10_000) { signOut.state.first { it == SignOutState.NEEDS_DISCARD_CONFIRMATION } }
    assertFalse(credentialsCleared)
    signOut.staySignedIn()
    assertEquals(uid, auth.currentUser?.uid)
    assertEquals("Original", profiles.getCachedProfile(uid)?.firstName)
    signOut.begin { credentialsCleared = true }
    withTimeout(10_000) { signOut.state.first { it == SignOutState.NEEDS_DISCARD_CONFIRMATION } }
    signOut.discard { credentialsCleared = true }
    withTimeout(10_000) { signOut.state.first { it == SignOutState.COMPLETE } }
    assertTrue(credentialsCleared)
    assertNull(auth.currentUser)
    restartDatabase()
    assertNull(profiles.getCachedProfile(uid))
  }

  /** New detail, joined-circle and registration listeners must end before cache termination. */
  @Test
  fun allNewRepositoryListenersAreRemovedAndRejectRecollectionAfterCleanup() = runBlocking {
    val uid = seed()
    val appointments =
        com.github.se.oncompanion.model.appointment.AppointmentRepositoryFirestore(db)
    val medications = com.github.se.oncompanion.model.medication.MedicationRepositoryFirestore(db)
    val invites = com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryFirestore(db)
    val circle = com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore(db)
    val registrations =
        com.github.se.oncompanion.model.event.EventRegistrationRepositoryFirestore(db)
    val flows =
        listOf(
            appointments.observeAppointment(uid, "missing"),
            medications.observePrescription(uid, "missing"),
            invites.observeJoinedCircles(uid),
            circle.observeMember(uid, "missing"),
            registrations.observeIsRegistered(uid, "missing"),
        )
    val ready = flows.map { kotlinx.coroutines.CompletableDeferred<Unit>() }
    val listeners = flows.mapIndexed { index, flow ->
      launch { flow.collect { ready[index].complete(Unit) } }
    }
    withTimeout(10_000) { ready.forEach { it.await() } }
    local.freeze()
    local.seal()
    assertTrue(listeners.all { it.isCompleted })
    auth.signOut()
    local.clear()
    restartDatabase()
    local.reopen()
    for (flow in flows) {
      try {
        flow.first()
        fail("Previous session listener restarted")
      } catch (error: IllegalStateException) {
        assertEquals("The session has ended", error.message)
      }
    }
  }
}
