// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.profile

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises patient hospital editing and display through the real emulator-backed repository. */
@RunWith(AndroidJUnit4::class)
class HospitalProfileTest {
  @get:Rule val compose = createComposeRule()
  private val originalRepository = UserProfileRepositoryProvider.repository

  /** Restores networking and authentication after the offline profile interaction. */
  @After
  fun restoreEmulator(): Unit = runBlocking {
    UserProfileRepositoryProvider.repository = originalRepository
    FirebaseEmulator.firestore.enableNetwork().await()
    EmulatorTestData.signOut()
  }

  /** Real navigation reloads a server-accepted hospital into a fresh editor, then clears it. */
  @Test
  fun savedHospitalSurvivesProfileNavigationServerSyncAndReopeningEditor() {
    val db = FirebaseEmulator.firestore
    val repository = UserProfileRepositoryFirestore(db)
    val uid = runBlocking {
      withTimeout(10_000) {
        db.enableNetwork().await()
        val owner = EmulatorTestData.createUser("hospital-navigation")
        // Exercise a profile created before hospital was an allowed field.
        db.collection("users")
            .document(owner)
            .set(
                mapOf(
                    "role" to "PATIENT",
                    "firstName" to "Alice",
                    "createdAt" to FieldValue.serverTimestamp(),
                )
            )
            .await()
        owner
      }
    }
    UserProfileRepositoryProvider.repository = repository
    compose.setContent { OncompanionTheme { AppNavHost(startRoute = Route.PROFILE) } }
    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag(C.Tag.profile_edit).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(C.Tag.profile_hospital).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.profile_edit).performClick()
    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag(C.Tag.hospital_field).fetchSemanticsNodes().isNotEmpty()
    }
    compose
        .onNodeWithTag(C.Tag.hospital_field)
        .performScrollTo()
        .performTextInput("Manual hospital")
    compose.onNodeWithTag(C.Tag.hospital_search).performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag(C.Tag.edit_profile_save).performScrollTo().performClick()

    // A local-only assertion can pass just before obsolete rules roll the write back.
    runBlocking {
      withTimeout(10_000) {
        db.waitForPendingWrites().await()
        val stored = db.collection("users").document(uid).get(Source.SERVER).await()
        assertEquals("Manual hospital", stored.getString("hospital"))
        assertFalse(stored.metadata.hasPendingWrites())
        assertEquals("Manual hospital", repository.getProfile(uid)!!.hospital)
      }
    }
    compose.onNodeWithTag(C.Tag.profile_hospital).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Manual hospital").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.profile_edit).performClick()
    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag(C.Tag.hospital_field).fetchSemanticsNodes().isNotEmpty()
    }
    compose
        .onNodeWithTag(C.Tag.hospital_field)
        .performScrollTo()
        .assertTextContains("Manual hospital")
        .performTextClearance()
    compose.onNodeWithTag(C.Tag.hospital_search).performScrollTo().assertIsNotEnabled()
    compose
        .onNodeWithTag(C.Tag.edit_profile_save)
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    runBlocking {
      withTimeout(10_000) {
        db.waitForPendingWrites().await()
        assertNull(db.collection("users").document(uid).get(Source.SERVER).await().get("hospital"))
      }
    }
    compose.onNodeWithTag(C.Tag.profile_screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.profile_hospital).assertDoesNotExist()
  }

  /** Manual-only M1 entry is saved locally offline and displayed; clearing also persists. */
  @Test
  fun manualHospitalEntryAndClearingWorkOfflineWithSearchDisabled() {
    val db = FirebaseEmulator.firestore
    val repository = UserProfileRepositoryFirestore(db)
    val uid = runBlocking {
      withTimeout(10_000) {
        db.enableNetwork().await()
        val owner = EmulatorTestData.createUser("hospital-profile")
        repository.createProfile(UserProfile(owner, Role.PATIENT, "Alice"))
        db.waitForPendingWrites().await()
        repository.getProfile(owner)
        db.disableNetwork().await()
        owner
      }
    }
    val edit = EditProfileViewModel(AuthRepositoryProvider.repository, repository)
    val profile = ProfileViewModel(AuthRepositoryProvider.repository, repository)
    var editing by mutableStateOf(true)
    compose.setContent {
      OncompanionTheme {
        if (editing) {
          EditProfileScreen(onBack = {}, onSaved = { editing = false }, viewModel = edit)
        } else {
          ProfileScreen(
              onBack = {},
              onSignedOut = {},
              onEdit = { editing = true },
              viewModel = profile,
          )
        }
      }
    }
    val field = compose.onNodeWithTag(C.Tag.hospital_field)
    field.performScrollTo().assertIsEnabled().performTextInput("Manual hospital")
    compose
        .onNodeWithTag(C.Tag.hospital_search)
        .performScrollTo()
        .assertIsDisplayed()
        .assertIsNotEnabled()
        .performTouchInput { click() }
    compose.onNodeWithTag(C.Tag.hospital_results).assertDoesNotExist()
    captureScreenshot("hospital-profile-edit.png")
    compose.onNodeWithTag(C.Tag.edit_profile_save).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.profile_hospital).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Manual hospital").assertIsDisplayed()
    captureScreenshot("hospital-profile-read.png")
    runBlocking { assertEquals("Manual hospital", repository.getCachedProfile(uid)!!.hospital) }
    compose.onNodeWithTag(C.Tag.profile_edit).performClick()
    // Reopening creates new semantics nodes; reacquire the field after the screen change.
    compose
        .onNodeWithTag(C.Tag.hospital_field)
        .performScrollTo()
        .assertTextContains("Manual hospital")
        .performTextClearance()
    compose
        .onNodeWithTag(C.Tag.edit_profile_save)
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    compose.onNodeWithTag(C.Tag.profile_hospital).assertDoesNotExist()
    runBlocking {
      assertNull(repository.getCachedProfile(uid)!!.hospital)
      db.enableNetwork().await()
      withTimeout(10_000) { db.waitForPendingWrites().await() }
      assertNull(repository.getProfile(uid)!!.hospital)
    }
  }

  // Keep device-rendered artifacts for the screen-change PR review.
  private fun captureScreenshot(name: String) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.cacheDir, name).outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
