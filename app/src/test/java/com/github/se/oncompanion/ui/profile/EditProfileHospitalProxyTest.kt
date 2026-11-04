// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.hospital.*
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.HospitalFieldViewModel
import java.io.IOException
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditProfileHospitalProxyTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val previous = HospitalRepositoryProvider.repository
  private val fixture = HospitalProxyTestFixture()
  private val profiles = FakeUserProfileRepository()

  @After
  fun restoreProvider() {
    HospitalRepositoryProvider.repository = previous
  }

  private fun show(configured: Boolean = true): EditProfileViewModel {
    val auth = FakeAuthRepository { AuthUser("proxy-patient") }
    runBlocking { auth.signInWithGoogle("fake-token") }
    profiles.seed(UserProfile("proxy-patient", Role.PATIENT, "Alex"))
    if (configured)
        HospitalRepositoryProvider.repository =
            HospitalRepositoryProxy(fixture.client(Dispatchers.Main), TestTimeSource())
    else HospitalRepositoryProvider.disable()
    val model = EditProfileViewModel(auth, profiles)
    val hospitalModel = HospitalFieldViewModel()
    compose.setContent { EditProfileScreen({}, {}, model, hospitalModel) }
    compose.waitUntil(5000) { model.uiState.value.status == EditProfileStatus.READY }
    return model
  }

  private fun field() = compose.onNodeWithTag(C.Tag.hospital_field)

  private fun search() =
      compose.onNodeWithTag(C.Tag.hospital_search).performScrollTo().performClick()

  @Test
  fun configuredProfileUsesProxyOnlyAfterSearchAndSelectionSavesTheExistingHospitalText() {
    fixture.body = "[${HospitalProxyTestFixture.HOSPITAL}]"
    val model = show()
    field().performScrollTo().performTextReplacement("Genève")
    compose.runOnIdle { assertTrue(fixture.requests.isEmpty()) }
    search()
    compose
        .onNodeWithTag(C.Tag.hospital_result)
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    compose.runOnIdle {
      assertEquals("Hôpital", model.uiState.value.hospital)
      assertEquals("proxy.example.invalid", fixture.requests.single().first.host)
    }
    compose.onNodeWithTag(C.Tag.edit_profile_save).performScrollTo().performClick()
    compose.waitUntil(5000) { profiles.profiles["proxy-patient"]?.hospital == "Hôpital" }
  }

  @Test
  fun cooldownBlocksNewQueriesButManualTextAndSaveRemainUsable() {
    fixture.status = 429
    fixture.retryAfter = "90"
    val model = show()
    field().performScrollTo().performTextReplacement("Genève")
    search()
    compose.onNodeWithTag(C.Tag.hospital_error).performScrollTo().assertIsDisplayed()
    field().performScrollTo().performTextReplacement("Bern")
    search()
    compose.runOnIdle { assertEquals(1, fixture.requests.size) }
    field().performScrollTo().performTextReplacement("Manual hospital")
    compose.runOnIdle { assertEquals("Manual hospital", model.uiState.value.hospital) }
    compose.onNodeWithTag(C.Tag.edit_profile_save).performScrollTo().performClick()
    compose.waitUntil(5000) { profiles.profiles["proxy-patient"]?.hospital == "Manual hospital" }
  }

  @Test
  fun missingConfigurationLeavesSearchDisabledAndManualEntryEditable() {
    val model = show(configured = false)
    field().performScrollTo().performTextReplacement("My hospital")
    compose.onNodeWithTag(C.Tag.hospital_search).performScrollTo().assertIsNotEnabled()
    compose.runOnIdle {
      assertEquals("My hospital", model.uiState.value.hospital)
      assertTrue(fixture.requests.isEmpty())
    }
  }

  @Test
  fun editingDuringTokenRetrievalCancelsTheOldQueryBeforeANewerSearch() {
    fixture.tokenGate = CompletableDeferred()
    val model = show()
    field().performScrollTo().performTextReplacement("Genève")
    search()
    compose.onNodeWithTag(C.Tag.hospital_loading).performScrollTo().assertIsDisplayed()
    field().performScrollTo().performTextReplacement("Bern")
    fixture.tokenGate!!.complete("id-token")
    compose.waitForIdle()
    compose.onNodeWithTag(C.Tag.hospital_loading).assertDoesNotExist()
    compose.runOnIdle {
      assertEquals("Bern", model.uiState.value.hospital)
      assertTrue(fixture.requests.isEmpty())
    }
    search()
    compose.onNodeWithTag(C.Tag.hospital_empty).performScrollTo().assertIsDisplayed()
    compose.runOnIdle {
      assertEquals("q=Bern&format=jsonv2&limit=10", fixture.requests.single().first.query)
    }
  }

  @Test
  fun offlineFailureLeavesManualEntryAndItsLengthLimitIntact() {
    fixture.failure = IOException("offline")
    val model = show()
    field().performScrollTo().performTextReplacement("Genève")
    search()
    compose.onNodeWithTag(C.Tag.hospital_error).performScrollTo().assertIsDisplayed()
    field().performScrollTo().performTextReplacement("h".repeat(101))
    compose.runOnIdle { assertEquals(100, model.uiState.value.hospital.length) }
    field().performTextReplacement("Manual offline")
    compose.onNodeWithTag(C.Tag.edit_profile_save).performScrollTo().assertIsEnabled()
    compose.runOnIdle { assertEquals(1, fixture.requests.size) }
  }
}
