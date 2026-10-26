// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrescriptionDetailScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private val ondansetron =
      Medication(
          id = "m1",
          prescriptionId = "chemo",
          name = "Ondansetron 8 mg",
          dosage = "1 tablet",
          frequency = "Twice a day",
          startDate = LocalDate.of(2026, 9, 29),
          durationDays = 5,
      )

  /** Only the name: no dosage, no frequency, no end. */
  private val dexamethasone =
      Medication(
          id = "m2",
          prescriptionId = "chemo",
          name = "Dexamethasone 4 mg",
          startDate = LocalDate.of(2026, 10, 1),
      )

  private val chemo =
      Prescription(
          id = "chemo",
          prescribedBy = "Dr. Martin",
          prescribedOn = LocalDate.of(2026, 9, 29),
          medications = listOf(ondansetron, dexamethasone),
      )

  private val loading = PrescriptionDetailUiState()
  private val shown = PrescriptionDetailUiState(prescription = chemo, isLoading = false)

  private fun setContent(
      state: PrescriptionDetailUiState,
      onBack: () -> Unit = {},
      onRetry: () -> Unit = {},
      onEdit: () -> Unit = {},
  ) {
    composeTestRule.setContent {
      PrescriptionDetailContent(
          uiState = state,
          onBack = onBack,
          onRetry = onRetry,
          onEdit = onEdit,
      )
    }
  }

  private fun assertTextInside(text: String, parentTag: String) {
    composeTestRule
        .onNode(hasText(text) and hasAnyAncestor(hasTestTag(parentTag)), useUnmergedTree = true)
        .assertExists()
  }

  private fun signedIn(uid: String) =
      FakeAuthRepository(onSignIn = { AuthUser(uid = uid) }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  // ----- States -----

  @Test
  fun everyState_showsTheTitleAndABackButtonThatCallsBack() {
    var backs = 0
    setContent(loading, onBack = { backs++ })

    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_screen).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.prescription_detail_title))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_back_button).performClick()

    assertEquals(1, backs)
  }

  @Test
  fun loading_showsOnlyTheProgress() {
    setContent(loading)

    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_content).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_error).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_not_found).assertDoesNotExist()
  }

  @Test
  fun error_showsRetry_whichCallsBack() {
    var retries = 0
    setContent(
        PrescriptionDetailUiState(isLoading = false, hasError = true),
        onRetry = { retries++ },
    )

    assertTextInside(
        context.getString(R.string.prescription_detail_error_title),
        C.Tag.prescription_detail_error,
    )
    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_content).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_retry_button).performClick()

    assertEquals(1, retries)
  }

  @Test
  fun error_neverBlamesTheConnection() {
    setContent(PrescriptionDetailUiState(isLoading = false, hasError = true))

    composeTestRule.onNodeWithText("connection", substring = true).assertDoesNotExist()
  }

  @Test
  fun notFound_saysItNoLongerExists_withoutRetry() {
    setContent(PrescriptionDetailUiState(isLoading = false))

    assertTextInside(
        context.getString(R.string.prescription_detail_not_found_title),
        C.Tag.prescription_detail_not_found,
    )
    assertTextInside(
        context.getString(R.string.prescription_detail_not_found_hint),
        C.Tag.prescription_detail_not_found,
    )
    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_retry_button).assertDoesNotExist()
  }

  // ----- The prescription (Figma: "US-11 / Prescription detail") -----

  @Test
  fun shown_headlineListsTheMedicationNames() {
    setContent(shown)

    assertTextInside("Ondansetron 8 mg, Dexamethasone 4 mg", C.Tag.prescription_detail_content)
  }

  @Test
  fun shown_givesWhoPrescribedItAndWhen_withTheirLabels() {
    setContent(shown)

    assertTextInside("Dr. Martin", C.Tag.prescription_detail_prescribed_by)
    assertTextInside(
        context.getString(R.string.prescription_detail_prescribed_by),
        C.Tag.prescription_detail_prescribed_by,
    )
    assertTextInside("29 Sep 2026", C.Tag.prescription_detail_date)
    assertTextInside(
        context.getString(R.string.prescription_detail_date),
        C.Tag.prescription_detail_date,
    )
  }

  @Test
  fun withoutPrescriber_thereIsNoPrescribedByRow() {
    setContent(shown.copy(prescription = chemo.copy(prescribedBy = null)))

    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_prescribed_by).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_date).assertIsDisplayed()
  }

  @Test
  fun blankPrescriber_isTreatedAsMissing() {
    setContent(shown.copy(prescription = chemo.copy(prescribedBy = "  ")))

    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_prescribed_by).assertDoesNotExist()
  }

  @Test
  fun medications_haveAHeadingThenEachOneInOrder() {
    setContent(shown)

    composeTestRule
        .onNode(
            hasTestTag(C.Tag.prescription_detail_medications_header) and isHeading(),
            useUnmergedTree = true,
        )
        .assertExists()
    val header = yOf(C.Tag.prescription_detail_medications_header)
    val first = yOf(C.Tag.prescriptionDetailMedication("m1"))
    val second = yOf(C.Tag.prescriptionDetailMedication("m2"))
    assert(header < first && first < second) { "Expected header, m1, m2: $header, $first, $second" }
  }

  @Test
  fun medication_showsEverySavedField() {
    setContent(shown)

    val tag = C.Tag.prescriptionDetailMedication("m1")
    assertTextInside("Ondansetron 8 mg", tag)
    assertTextInside("1 tablet · Twice a day · 5 days", tag)
    assertTextInside("Starts 29 Sep 2026", tag)
  }

  @Test
  fun medicationWithOnlyItsName_showsNoEmptyDetailsLine() {
    setContent(shown)

    val tag = C.Tag.prescriptionDetailMedication("m2")
    assertTextInside("Dexamethasone 4 mg", tag)
    assertTextInside("Starts 1 Oct 2026", tag)
    composeTestRule
        .onNode(hasText("·", substring = true) and hasAnyAncestor(hasTestTag(tag)))
        .assertDoesNotExist()
  }

  @Test
  fun oneDayDuration_isSingular() {
    val oneDay = ondansetron.copy(dosage = null, frequency = null, durationDays = 1)
    setContent(shown.copy(prescription = chemo.copy(medications = listOf(oneDay))))

    assertTextInside("1 day", C.Tag.prescriptionDetailMedication("m1"))
  }

  @Test
  fun longPrescription_canBeScrolledToItsLastMedication() {
    val many = (1..20).map { ondansetron.copy(id = "m$it", name = "Medication $it") }
    setContent(shown.copy(prescription = chemo.copy(medications = many)))

    composeTestRule
        .onNodeWithTag(C.Tag.prescription_detail_content)
        .performScrollToNode(hasTestTag(C.Tag.prescriptionDetailMedication("m20")))

    composeTestRule.onNodeWithTag(C.Tag.prescriptionDetailMedication("m20")).assertIsDisplayed()
  }

  // ----- Edit (US-12) -----

  @Test
  fun shown_editButtonOpensTheForm() {
    var edits = 0
    setContent(shown, onEdit = { edits++ })

    composeTestRule
        .onNodeWithTag(C.Tag.prescription_detail_edit_button)
        .assertIsDisplayed()
        .performClick()

    assertEquals(1, edits)
    assertTextInside(
        context.getString(R.string.prescription_detail_edit),
        C.Tag.prescription_detail_edit_button,
    )
  }

  @Test
  fun editButton_onlyWhileThePrescriptionIsShown() {
    listOf(
            loading,
            PrescriptionDetailUiState(isLoading = false, hasError = true),
            PrescriptionDetailUiState(isLoading = false),
            // A read error after it was shown: what is on screen may be out of date
            shown.copy(hasError = true),
        )
        .forEach { state -> assertEquals("Edit for $state", false, state.canEdit) }
    setContent(loading)

    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_edit_button).assertDoesNotExist()
  }

  @Test
  fun editButton_staysOnScreenWithALongPrescription() {
    val many = (1..20).map { ondansetron.copy(id = "m$it", name = "Medication $it") }
    setContent(shown.copy(prescription = chemo.copy(medications = many)))

    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_edit_button).assertIsDisplayed()
  }

  // ----- With the ViewModel -----

  @Test
  fun screen_showsTheSignedInUsersPrescription_andPassesBack() {
    val repository = FakeMedicationRepository().apply { seed("alice", chemo) }
    var backs = 0
    val edited = mutableListOf<String>()
    val viewModel = PrescriptionDetailViewModel("chemo", signedIn("alice"), repository)
    composeTestRule.setContent {
      PrescriptionDetailScreen(
          viewModel = viewModel,
          onBack = { backs++ },
          onEdit = { edited += it },
      )
    }

    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_content).assertIsDisplayed()
    assertTextInside("Dr. Martin", C.Tag.prescription_detail_prescribed_by)
    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_back_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_edit_button).performClick()

    composeTestRule.runOnIdle {
      assertEquals(1, backs)
      assertEquals(listOf("chemo"), edited)
    }
  }

  @Test
  fun screen_retryAfterError_showsThePrescription() {
    val repository =
        FakeMedicationRepository().apply {
          seed("alice", chemo)
          readError = IllegalStateException("no cache")
        }
    val viewModel = PrescriptionDetailViewModel("chemo", signedIn("alice"), repository)
    composeTestRule.setContent {
      PrescriptionDetailScreen(viewModel = viewModel, onBack = {}, onEdit = {})
    }
    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_error).assertIsDisplayed()

    repository.readError = null
    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_retry_button).performClick()

    composeTestRule.onNodeWithTag(C.Tag.prescription_detail_content).assertIsDisplayed()
  }

  private fun yOf(tag: String) =
      composeTestRule
          .onNodeWithTag(tag, useUnmergedTree = true)
          .fetchSemanticsNode()
          .positionInRoot
          .y
}
