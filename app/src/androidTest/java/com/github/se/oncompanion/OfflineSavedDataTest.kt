package com.github.se.oncompanion

import android.content.Context
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.overview.OverviewScreen
import com.github.se.oncompanion.ui.profile.ProfileScreen
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Data loaded once stays available without a connection (#32, US-2). Firestore keeps its cache on
 * the device's disk by default on Android: these tests check that the saved profile is still shown
 * offline, also after the app restarts.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSavedDataTest {

  @get:Rule val compose = createComposeRule()

  private lateinit var uid: String

  private val db
    get() = FirebaseEmulator.firestore

  @Before
  fun setUp(): Unit = runBlocking {
    db.enableNetwork().await()
    // An email account: it has no Google name, so a first name shown on screen can only come from
    // the saved profile
    uid = EmulatorTestData.createUser("sam")
    UserProfileRepositoryFirestore(db).createProfile(savedProfile(uid))
    withTimeout(TIMEOUT_MS) { db.waitForPendingWrites().await() }
    db.disableNetwork().await()
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    FirebaseEmulator.auth.signOut()
  }

  @Test
  fun profileScreenShowsTheSavedProfileOffline() {
    compose.setContent {
      OncompanionTheme { ProfileScreen(onBack = {}, onSignedOut = {}, onEdit = {}) }
    }

    // The tag is on the field's container; the value is a child text
    compose.waitUntil(TIMEOUT_MS) {
      compose
          .onAllNodes(hasTestTag(C.Tag.profile_first_name) and hasAnyDescendant(hasText("Sam")))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    compose.onNodeWithTag(C.Tag.profile_missing).assertDoesNotExist()
  }

  @Test
  fun overviewGreetsWithTheSavedFirstNameOffline() {
    compose.setContent {
      OncompanionTheme {
        val navController = rememberNavController()
        OverviewScreen(navigationActions = NavigationActions(navController))
      }
    }

    compose.waitUntil(TIMEOUT_MS) {
      compose
          .onAllNodes(hasTestTag(C.Tag.overview_greeting) and hasText("Sam", substring = true))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
  }

  @Test
  fun savedProfileSurvivesARestartOffline(): Unit = runBlocking {
    // A separate Firebase app, so its Firestore can be stopped and started again like after the
    // app is closed, without touching the instance the other tests share
    val context = ApplicationProvider.getApplicationContext<Context>()
    val app =
        FirebaseApp.initializeApp(
            context,
            FirebaseApp.getInstance().options,
            "restart-${UUID.randomUUID()}",
        )
    try {
      val auth =
          FirebaseAuth.getInstance(app).apply {
            useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.AUTH_PORT)
          }
      val restartUid =
          auth
              .createUserWithEmailAndPassword("restart-${UUID.randomUUID()}@example.com", "secret1")
              .await()
              .user!!
              .uid
      val before = connectedFirestore(app)
      UserProfileRepositoryFirestore(before).createProfile(savedProfile(restartUid))
      withTimeout(TIMEOUT_MS) { before.waitForPendingWrites().await() }
      val saved = UserProfileRepositoryFirestore(before).getProfile(restartUid)

      // "Close the app": the Firestore instance is stopped, only its disk cache remains
      before.terminate().await()
      val after = connectedFirestore(app)
      after.disableNetwork().await()

      val offline =
          withTimeout(TIMEOUT_MS) { UserProfileRepositoryFirestore(after).getProfile(restartUid) }
      assertEquals(saved, offline)
      assertEquals(
          saved,
          withTimeout(TIMEOUT_MS) {
            UserProfileRepositoryFirestore(after).getCachedProfile(restartUid)
          },
      )
      after.terminate().await()
    } finally {
      app.delete()
    }
  }

  /**
   * A new Firestore instance of [app], connected to the emulator and left with default settings.
   */
  private fun connectedFirestore(app: FirebaseApp): FirebaseFirestore =
      FirebaseFirestore.getInstance(app).apply {
        useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FIRESTORE_PORT)
      }

  private fun savedProfile(uid: String) =
      UserProfile(
          uid = uid,
          role = Role.PATIENT,
          firstName = "Sam",
          familyName = "Rossi",
          cancerType = "Lymphoma",
      )

  private companion object {
    const val TIMEOUT_MS = 10_000L
  }
}
