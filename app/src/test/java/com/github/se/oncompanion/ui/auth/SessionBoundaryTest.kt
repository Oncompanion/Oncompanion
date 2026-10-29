// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.auth

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.domain.auth.SignOutState
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.medication.scan.ScanPhotoStore
import com.github.se.oncompanion.model.session.FakeCleanupJournal
import com.github.se.oncompanion.model.session.FakeLocalSessionRepository
import com.github.se.oncompanion.ui.prescription.scan.ScanViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Recreates the activity like MainActivity and checks the session's real ViewModel lifecycle. */
@RunWith(AndroidJUnit4::class)
class SessionBoundaryTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  @get:Rule val files = TemporaryFolder()
  private val originalAuth = AuthRepositoryProvider.repository
  private val originalSignOut = SignOutProvider.signOut
  private val auth = FakeAuthRepository()
  private val local = FakeLocalSessionRepository()
  private lateinit var signOut: SignOut
  private lateinit var shown: Draft

  /** Models unsaved form/scan state whose cleanup must run only when its session ends. */
  class Draft : ViewModel() {
    var text = ""
    var cleared = false

    override fun onCleared() {
      cleared = true
    }
  }

  @Before
  fun setUp() {
    runBlocking { auth.signInWithGoogle("one") }
    AuthRepositoryProvider.repository = auth
    signOut = SignOut(auth, local).apply { initialize(FakeCleanupJournal()) }
    SignOutProvider.signOut = signOut
  }

  @After
  fun restore() {
    compose.activityRule.scenario.close()
    AuthRepositoryProvider.repository = originalAuth
    SignOutProvider.signOut = originalSignOut
  }

  private fun show() {
    compose.runOnUiThread {
      compose.activity.setContent {
        SessionBoundary(FakeGoogleCredentialProvider()) { _, _, _ -> shown = viewModel { Draft() } }
      }
    }
    compose.waitForIdle()
  }

  @Test
  fun rotationRetainsUnsavedStateAndSignOutClearsItBeforeNextSession() {
    show()
    val first = shown
    compose.runOnIdle { first.text = "Unsaved appointment and scan draft" }
    compose.activityRule.scenario.recreate()
    show()
    compose.runOnIdle {
      assertSame(first, shown)
      assertFalse(first.cleared)
      assertEquals("Unsaved appointment and scan draft", shown.text)
      signOut.begin {}
    }
    compose.waitUntil { signOut.state.value == SignOutState.COMPLETE }
    compose.runOnIdle { assertTrue(first.cleared) }
    compose.runOnIdle {
      runBlocking { auth.signInWithGoogle("two") }
      signOut.sessionStarted()
    }
    compose.runOnIdle {
      assertNotSame(first, shown)
      assertEquals("", shown.text)
      assertFalse(shown.cleared)
    }
  }

  @Test
  fun cancelSignOutThenRotateRetainsDraftButDirectAccountChangeClearsIt() {
    local.synchronizeAction = { error("Not acknowledged") }
    show()
    val first = shown
    compose.runOnIdle {
      first.text = "Keep this draft"
      signOut.begin {}
    }
    compose.waitUntil { signOut.state.value == SignOutState.NEEDS_DISCARD_CONFIRMATION }
    compose.runOnIdle { signOut.staySignedIn() }
    compose.activityRule.scenario.recreate()
    show()
    compose.runOnIdle {
      assertSame(first, shown)
      assertEquals("Keep this draft", shown.text)
      runBlocking { auth.signInWithGoogle("two") }
    }
    compose.runOnIdle {
      assertTrue(first.cleared)
      assertNotSame(first, shown)
      assertEquals("", shown.text)
    }
  }

  @Test
  fun finishingActivityClearsRetainedSessionStore() {
    show()
    val first = shown
    compose.activityRule.scenario.close()
    assertTrue(first.cleared)
  }

  @Test
  fun scanPhotoSurvivesRecreationAndIsDeletedWhenSessionEnds() {
    val photos = ScanPhotoStore(files.newFolder("scan-cache"))
    lateinit var scan: ScanViewModel
    fun showScan() {
      compose.runOnUiThread {
        compose.activity.setContent {
          SessionBoundary(FakeGoogleCredentialProvider()) { _, _, _ ->
            scan = viewModel { ScanViewModel(photos) }
          }
        }
      }
      compose.waitForIdle()
    }
    showScan()
    val first = scan
    compose.runOnIdle {
      scan.onPermissionGranted()
      scan.onCameraReady()
      scan.capture { it.writeText("private prescription photo") }
    }
    compose.waitForIdle()
    val photo = checkNotNull(scan.uiState.value.photo)
    assertTrue(photo.exists())
    compose.activityRule.scenario.recreate()
    showScan()
    compose.runOnIdle {
      assertSame(first, scan)
      assertEquals(photo, scan.uiState.value.photo)
      assertTrue(photo.exists())
      signOut.begin {}
    }
    compose.waitUntil { signOut.state.value == SignOutState.COMPLETE }
    compose.runOnIdle { assertFalse(photo.exists()) }
  }
}
