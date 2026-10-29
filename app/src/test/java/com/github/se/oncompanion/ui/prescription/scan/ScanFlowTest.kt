// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.scan.FakeTextRecognizer
import com.github.se.oncompanion.model.medication.scan.RecognizedText
import com.github.se.oncompanion.model.medication.scan.ScannedSheets.sheet
import com.github.se.oncompanion.model.medication.scan.TextRecognizerProvider
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.overview.OverviewShortcut
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Scanning a prescription through the app's navigation (US-10, #64), from the prescriptions list to
 * the saved prescription, with a fake camera and a fake text recognizer that reads the fictional
 * test sheets as ML Kit read them on a phone. Refusing the camera is in AppNavHostTest.
 */
@RunWith(AndroidJUnit4::class)
class ScanFlowTest {

  @get:Rule val compose = createComposeRule()

  private val application: Application = ApplicationProvider.getApplicationContext()
  private val uid = "patient"
  private val recognizer = FakeTextRecognizer()
  private val medications = FakeMedicationRepository()

  private val appAuth = AuthRepositoryProvider.repository
  private val appMedications = MedicationRepositoryProvider.repository
  private val appRecognizer = TextRecognizerProvider.recognizer
  private val appCamera = ScanCameraProvider.newCamera

  @Before
  fun setUp() {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = uid) })
    runBlocking { auth.signInWithGoogle("token") }
    AuthRepositoryProvider.repository = auth
    MedicationRepositoryProvider.repository = medications
    TextRecognizerProvider.recognizer = recognizer
    ScanCameraProvider.newCamera = { FakeScanCamera() }
    shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
  }

  @After
  fun tearDown() {
    AuthRepositoryProvider.repository = appAuth
    MedicationRepositoryProvider.repository = appMedications
    TextRecognizerProvider.recognizer = appRecognizer
    ScanCameraProvider.newCamera = appCamera
  }

  private fun string(id: Int) = application.getString(id)

  private fun waitFor(matcher: SemanticsMatcher) =
      compose.waitUntil(TIMEOUT_MS) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
      }

  private fun reason(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

  /** From the Overview to the scan, a photo of a prescription ML Kit reads as [text], then Use. */
  private fun scan(text: RecognizedText) {
    recognizer.result = text
    compose.setContent { AppNavHost(startRoute = Route.OVERVIEW) }
    compose.onNodeWithTag(OverviewShortcut.PRESCRIPTIONS.testTag).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.prescriptions_scan_button).performClick()
    waitFor(hasTestTag(C.Tag.prescription_scan_capture_button) and isEnabled())
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).performClick()
    waitFor(hasTestTag(C.Tag.prescription_scan_use_photo_button))
    compose.onNodeWithTag(C.Tag.prescription_scan_use_photo_button).performClick()
  }

  @Test
  fun aSheetReadRightIsReviewedThenSavedOnlyOnSave() {
    scan(sheet(1))

    waitFor(hasTestTag(C.Tag.prescription_form_screen))
    compose.onNodeWithText(string(R.string.prescription_review_title)).assertExists()
    // Read right: nothing to check
    compose
        .onNodeWithTag(C.Tag.prescription_review_notice)
        .assertTextContains(string(R.string.prescription_review_read), substring = true)
    compose
        .onNodeWithTag(C.Tag.prescription_form_prescribed_by_field)
        .assertTextContains("Anna Martin", substring = true)
    compose
        .onNodeWithTag(C.Tag.prescription_form_medication_name_field)
        .assertTextContains("Ondansetron 8 mg")
    // Nothing is saved before the patient confirms
    assertEquals(emptyList<Any>(), medications.prescriptionsOf(uid))

    compose.onNodeWithTag(C.Tag.prescription_form_save_button).performClick()

    waitFor(hasTestTag(C.Tag.prescriptions_screen))
    val saved = medications.prescriptionsOf(uid).single()
    assertEquals(
        listOf("Ondansetron 8 mg", "Dexamethasone 4 mg"),
        saved.medications.map { it.name },
    )
  }

  @Test
  fun aSheetReadPartlyShowsWhatToCheckUntilItIsCorrected() {
    // The Swiss sheet: its first medication's dosage isn't written ("1-0-0" only)
    scan(sheet(2))

    waitFor(hasTestTag(C.Tag.prescription_form_screen))
    compose
        .onNodeWithTag(C.Tag.prescription_review_notice)
        .assertTextContains(string(R.string.prescription_review_partly_read), substring = true)
        .assertTextContains("6 details need checking", substring = true)
    reason(C.Tag.prescriptionReviewFieldCheck("dosage"))
        .performScrollTo()
        .assertTextContains(string(R.string.prescription_review_not_found))

    compose
        .onNodeWithTag(C.Tag.prescription_form_dosage_field)
        .performScrollTo()
        .performTextInput("1 comprimé")

    waitFor(hasText("5 details need checking", substring = true))
    reason(C.Tag.prescriptionReviewFieldCheck("dosage")).assertDoesNotExist()
    assertEquals(emptyList<Any>(), medications.prescriptionsOf(uid))
  }

  @Test
  fun closingTheReviewSavesNothing() {
    scan(sheet(1))
    waitFor(hasTestTag(C.Tag.prescription_form_screen))

    compose.onNodeWithTag(C.Tag.prescription_form_close_button).performClick()

    waitFor(hasTestTag(C.Tag.prescriptions_screen))
    assertEquals(emptyList<Any>(), medications.prescriptionsOf(uid))
  }

  @Test
  fun nothingReadOffersManualEntryWithAnEmptyForm() {
    scan(FakeTextRecognizer.NO_TEXT)

    waitFor(hasTestTag(C.Tag.prescription_scan_nothing_read))
    compose.onNodeWithTag(C.Tag.prescription_scan_nothing_read_enter_manually_button).performClick()

    waitFor(hasTestTag(C.Tag.prescription_form_screen))
    compose.onNodeWithText(string(R.string.prescription_form_add_title)).assertExists()
    compose.onNodeWithTag(C.Tag.prescription_review_notice).assertDoesNotExist()
  }

  private companion object {
    const val TIMEOUT_MS = 10_000L
  }
}
