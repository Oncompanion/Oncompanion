// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryFirestore
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import com.github.se.oncompanion.model.carecircle.EmailAlreadyInCircle
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The add member form refuses an email already in the circle without a connection (#257): the
 * members and invitations come from the phone's cache, like every other screen offline.
 */
@RunWith(AndroidJUnit4::class)
class CareCircleMemberFormOfflineTest {

  private val db
    get() = FirebaseEmulator.firestore

  private val store = ViewModelStore()

  private lateinit var aliceUid: String

  /** Léa's invitation, created online before the test: the phone has it from the server. */
  private lateinit var leaInviteCode: String

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    aliceUid = EmulatorTestData.createUser("alice")
    // Marc joined; written bypassing the rules, which only allow it by redeeming
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}",
        "marc-uid",
        """{"firstName": {"stringValue": "Marc"}, "relationship": {"stringValue": "SON"},
            "permissions": {"arrayValue": {}},
            "email": {"stringValue": "marc@gmail.com"}}""",
    )
    leaInviteCode = createInvite("Léa", "lea@example.com")
    withTimeout(TIMEOUT_MS) { db.waitForPendingWrites().await() }
    // Read once online, so the phone has the circle in its cache
    withTimeout(TIMEOUT_MS) {
      CareCircleRepositoryFirestore(db).observeMembers(aliceUid).first { it.isNotEmpty() }
      CareCircleInviteRepositoryFirestore(db).observePendingInvites(aliceUid).first {
        it.isNotEmpty()
      }
    }
    db.disableNetwork().await()
  }

  @After
  fun tearDown(): Unit = runBlocking {
    withContext(Dispatchers.Main) { store.clear() }
    db.enableNetwork().await()
    FirebaseEmulator.auth.signOut()
  }

  @Test
  fun offline_aMemberInTheCache_isRefused(): Unit = runBlocking {
    val viewModel = withContext(Dispatchers.Main) { formViewModel() }
    val marc = EmailAlreadyInCircle.Member("Marc")
    // His email, someone else, then his email typed differently
    val cases =
        listOf("marc@gmail.com" to marc, "someone@example.com" to null, " MARC@gmail.com" to marc)
    for ((typed, expected) in cases) {
      withContext(Dispatchers.Main) { viewModel.onEmailChange(typed) }

      val state = withTimeout(TIMEOUT_MS) { viewModel.uiState.first { it.duplicate == expected } }

      if (expected != null) assertFalse("typed '$typed'", state.canSave)
      assertEquals(typed, state.email)
    }
  }

  @Test
  fun offline_anInvitation_isRefused_whetherItReachedTheServerOrNot(): Unit = runBlocking {
    // Created offline: only on the phone until it reconnects
    val julieInviteCode = createInvite("Julie", "julie@example.com")
    val viewModel = withContext(Dispatchers.Main) { formViewModel() }
    val cases =
        listOf(
            "lea@example.com" to EmailAlreadyInCircle.PendingInvite("Léa", leaInviteCode),
            "julie@example.com" to EmailAlreadyInCircle.PendingInvite("Julie", julieInviteCode),
        )
    for ((typed, expected) in cases) {
      withContext(Dispatchers.Main) { viewModel.onEmailChange(typed) }

      val state = withTimeout(TIMEOUT_MS) { viewModel.uiState.first { it.duplicate == expected } }

      assertFalse("typed '$typed'", state.canSave)
    }
  }

  /** Alice invites [firstName] at [email], saved on the phone, and returns the invite code. */
  private suspend fun createInvite(firstName: String, email: String): String =
      withTimeout(TIMEOUT_MS) {
        CareCircleInviteRepositoryFirestore(db)
            .createInvite(
                ownerUid = aliceUid,
                ownerFirstName = "Alice",
                details = CareCircleMemberDetails(firstName, relationship = Relationship.FRIEND),
                email = email,
            )
            .inviteCode
      }

  /** The form, with the real repositories on the emulator, cleared after the test. */
  private fun formViewModel(): CareCircleMemberFormViewModel =
      ViewModelProvider(
          store,
          viewModelFactory {
            initializer {
              CareCircleMemberFormViewModel(
                  inviteRepository = CareCircleInviteRepositoryFirestore(db),
                  profileRepository = UserProfileRepositoryFirestore(db),
                  authRepository = AuthRepositoryFirebase { FirebaseEmulator.auth },
                  careCircleRepository = CareCircleRepositoryFirestore(db),
              )
            }
          },
      )[CareCircleMemberFormViewModel::class.java]

  private companion object {
    const val TIMEOUT_MS = 10_000L
  }
}
