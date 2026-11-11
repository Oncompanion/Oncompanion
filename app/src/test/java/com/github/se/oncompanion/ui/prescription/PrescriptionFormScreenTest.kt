// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.medication.MedicationDraft
import com.github.se.oncompanion.domain.medication.PrescriptionDraft
import com.github.se.oncompanion.domain.medication.scan.MedicationDetail
import com.github.se.oncompanion.domain.medication.scan.ScannedField
import com.github.se.oncompanion.domain.medication.toDraft
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.SMALL_LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsAboveKeyboard
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.SavePhase
import com.github.se.oncompanion.ui.showKeyboard
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class PrescriptionFormScreenTest {

  // An activity rule, so the phone can be rotated (the activity recreated)
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun string(id: Int) = context.getString(id)

  private val date = LocalDate.of(2026, 9, 29)
  private val ondansetron =
      MedicationDraft(
          name = "Ondansetron 8 mg",
          dosage = "1 tablet",
          frequency = "Twice a day",
          startDate = date.plusDays(1),
          durationDays = 5,
      )
  private val dexamethasone = MedicationDraft(name = "Dexamethasone 4 mg", startDate = date)

  private val empty =
      PrescriptionFormUiState(
          PrescriptionDraft(
              prescribedOn = date,
              medications = listOf(MedicationDraft(startDate = date)),
          ),
          nameTouched = listOf(false),
      )

  private val filled =
      PrescriptionFormUiState(
          PrescriptionDraft(
              prescribedBy = "Dr. Martin",
              prescribedOn = date,
              medications = listOf(ondansetron),
          )
      )
  private val twoMedications =
      filled.copy(
          draft = filled.draft.copy(medications = listOf(ondansetron, dexamethasone)),
          openMedication = 1,
      )

  private var uiState by mutableStateOf(empty)
  private val events = mutableListOf<String>()

  /** Changes the open medication of the state, as the view model would. */
  private fun editOpenMedication(transform: (MedicationDraft) -> MedicationDraft) {
    val medications =
        uiState.draft.medications.mapIndexed { position, medication ->
          if (position == uiState.openMedication) transform(medication) else medication
        }
    uiState = uiState.copy(draft = uiState.draft.copy(medications = medications))
  }

  /**
   * Shows PrescriptionFormContent like the real screen does: typing updates the state. A caregiver
   * adds for [caregiverOf].
   */
  private fun setContent(state: PrescriptionFormUiState, caregiverOf: String? = null) {
    uiState = state
    composeTestRule.setContent {
      PrescriptionFormContent(
          uiState = uiState,
          onClose = { events += "close" },
          onPrescribedByChange = {
            events += "prescribedBy=$it"
            uiState = uiState.copy(draft = uiState.draft.copy(prescribedBy = it))
          },
          onPrescribedOnChange = { events += "prescribedOn=$it" },
          onMedicationNameChange = { name ->
            events += "name=$name"
            editOpenMedication { it.copy(name = name) }
            uiState =
                uiState.copy(
                    nameTouched =
                        uiState.nameTouched.mapIndexed { position, touched ->
                          touched || position == uiState.openMedication
                        }
                )
          },
          onDosageChange = { dosage ->
            events += "dosage=$dosage"
            editOpenMedication { it.copy(dosage = dosage) }
          },
          onFrequencyChange = { frequency ->
            events += "frequency=$frequency"
            editOpenMedication { it.copy(frequency = frequency) }
          },
          onStartDateChange = { events += "startDate=$it" },
          onDurationChange = { duration ->
            events += "duration=$duration"
            editOpenMedication { it.copy(durationDays = duration.toIntOrNull()) }
          },
          onAddMedication = { events += "add" },
          onOpenMedication = { events += "open=$it" },
          onRemoveMedication = { events += "remove=$it" },
          onSave = { events += "save" },
          onErrorShown = {
            events += "errorShown"
            uiState = uiState.copy(error = null)
          },
          onRetry = { events += "retry" },
          onKeepEditing = { events += "keepEditing" },
          onSaveAnyway = { events += "saveAnyway" },
          onReject = {
            events += "reject"
            uiState = uiState.copy(isConfirmingRejection = true)
          },
          onConfirmRejection = { events += "confirmRejection" },
          onCancelRejection = {
            events += "cancelRejection"
            uiState = uiState.copy(isConfirmingRejection = false)
          },
          caregiverOf = caregiverOf,
      )
    }
  }

  private fun waitForText(text: String) {
    composeTestRule.waitUntil(5_000) {
      composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun waitForNoText(text: String) {
    composeTestRule.waitUntil(10_000) {
      composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun field(tag: String) = node(tag).performScrollTo()

  // ---------- Empty form ----------

  @Test
  fun emptyFormShowsEveryFieldAndCannotBeSaved() {
    setContent(empty)

    node(C.Tag.prescription_form_screen).assertExists()
    composeTestRule.onNodeWithText(string(R.string.prescription_form_add_title)).assertExists()
    field(C.Tag.prescription_form_prescribed_by_field).assertExists()
    field(C.Tag.prescription_form_date_field).assertTextContains("29 Sep 2026")
    field(C.Tag.prescription_form_medication_title).assertTextContains("Medication 1")
    field(C.Tag.prescription_form_medication_name_field).assertExists()
    field(C.Tag.prescription_form_dosage_field).assertExists()
    field(C.Tag.prescription_form_frequency_field).assertExists()
    field(C.Tag.prescription_form_start_date_field).assertTextContains("29 Sep 2026")
    field(C.Tag.prescription_form_duration_field).assertExists()
    field(C.Tag.prescription_form_add_medication_button).assertIsEnabled()
    node(C.Tag.prescription_form_save_button).assertIsNotEnabled()
    node(C.Tag.prescription_form_saving).assertDoesNotExist()
  }

  @Test
  fun theOnlyMedicationHasNoRemoveButton() {
    setContent(empty)

    node(C.Tag.prescription_form_remove_medication_button).assertDoesNotExist()
  }

  // ---------- Filled form ----------

  @Test
  fun filledFormShowsWhatWasEnteredAndCanBeSaved() {
    setContent(filled)

    field(C.Tag.prescription_form_prescribed_by_field).assertTextContains("Dr. Martin")
    field(C.Tag.prescription_form_date_field).assertTextContains("29 Sep 2026")
    field(C.Tag.prescription_form_medication_name_field).assertTextContains("Ondansetron 8 mg")
    field(C.Tag.prescription_form_dosage_field).assertTextContains("1 tablet")
    field(C.Tag.prescription_form_frequency_field).assertTextContains("Twice a day")
    field(C.Tag.prescription_form_start_date_field).assertTextContains("30 Sep 2026")
    field(C.Tag.prescription_form_duration_field).assertTextContains("5")
    node(C.Tag.prescription_form_save_button).assertIsEnabled()
  }

  @Test
  fun typingInAFieldReportsTheNewText() {
    setContent(empty)

    field(C.Tag.prescription_form_prescribed_by_field).performTextInput("Dr. Martin")
    field(C.Tag.prescription_form_medication_name_field).performTextInput("Ondansetron")
    field(C.Tag.prescription_form_dosage_field).performTextInput("1 tablet")
    field(C.Tag.prescription_form_frequency_field).performTextInput("Daily")
    field(C.Tag.prescription_form_duration_field).performTextInput("5")

    assertEquals(
        listOf(
            "prescribedBy=Dr. Martin",
            "name=Ondansetron",
            "dosage=1 tablet",
            "frequency=Daily",
            "duration=5",
        ),
        events,
    )
  }

  // ---------- Warnings ----------

  @Test
  fun medicationNameShowsRequiredUntilItIsLeftEmpty() {
    setContent(empty)
    val missing = string(R.string.prescription_form_name_missing)

    composeTestRule.onNodeWithText(string(R.string.prescription_form_required)).assertExists()
    composeTestRule.onNodeWithText(missing).assertDoesNotExist()

    field(C.Tag.prescription_form_medication_name_field).performTextInput("Ond")
    field(C.Tag.prescription_form_medication_name_field).performTextClearance()

    composeTestRule.onNodeWithText(missing).assertExists()
  }

  @Test
  fun durationOutOfRangeShowsAWarningOnTheField() {
    setContent(filled)
    val warning =
        context.getString(R.string.prescription_form_duration_invalid, Medication.MAX_DURATION_DAYS)
    composeTestRule.onNodeWithText(warning).assertDoesNotExist()

    field(C.Tag.prescription_form_duration_field).performTextClearance()
    field(C.Tag.prescription_form_duration_field).performTextInput("0")
    composeTestRule.onNodeWithText(warning).assertExists()

    field(C.Tag.prescription_form_duration_field).performTextClearance()
    field(C.Tag.prescription_form_duration_field).performTextInput("9999")
    composeTestRule.onNodeWithText(warning).assertExists()

    field(C.Tag.prescription_form_duration_field).performTextClearance()
    composeTestRule.onNodeWithText(warning).assertDoesNotExist()
  }

  @Test
  fun durationFieldNamesItsUnitInTheSingularForOneDay() {
    val oneDay = filled.draft.copy(medications = listOf(ondansetron.copy(durationDays = 1)))
    setContent(filled.copy(draft = oneDay))

    composeTestRule.onNodeWithText("day").assertExists()
    composeTestRule.onNodeWithText("days").assertDoesNotExist()
  }

  @Test
  fun closedMedicationThatCannotBeSavedSaysWhy() {
    val unnamed = MedicationDraft(startDate = date)
    val wrongDuration = ondansetron.copy(durationDays = 0)
    setContent(
        twoMedications.copy(
            draft =
                twoMedications.draft.copy(
                    medications = listOf(unnamed, wrongDuration, dexamethasone)
                ),
            openMedication = 2,
        )
    )

    field(C.Tag.prescriptionFormMedicationRow(0))
        .assertTextContains(string(R.string.prescription_form_name_missing))
    field(C.Tag.prescriptionFormMedicationRow(1))
        .assertTextContains(string(R.string.prescription_form_duration_check))
  }

  @Test
  fun saveAndCloseReportTheirClicks() {
    setContent(filled)

    node(C.Tag.prescription_form_save_button).performClick()
    node(C.Tag.prescription_form_close_button).performClick()

    assertEquals(listOf("save", "close"), events)
  }

  // ---------- Dates ----------

  @Test
  fun confirmingTheDatePickerReportsThePrescriptionDate() {
    setContent(filled)

    field(C.Tag.prescription_form_date_field).performClick()
    node(C.Tag.prescription_form_date_picker).assertExists()
    node(C.Tag.prescription_form_date_picker_confirm).performClick()

    assertEquals(listOf("prescribedOn=2026-09-29"), events)
    node(C.Tag.prescription_form_date_picker).assertDoesNotExist()
  }

  @Test
  fun choosingAnotherDayInTheDatePickerReportsThatDay() {
    setContent(filled)

    field(C.Tag.prescription_form_date_field).performClick()
    composeTestRule.onNodeWithText("Thursday, September 17, 2026").performClick()
    node(C.Tag.prescription_form_date_picker_confirm).performClick()

    assertEquals(listOf("prescribedOn=2026-09-17"), events)
  }

  @Test
  fun confirmingTheDatePickerReportsTheStartDate() {
    setContent(filled)

    field(C.Tag.prescription_form_start_date_field).performClick()
    node(C.Tag.prescription_form_date_picker_confirm).performClick()

    assertEquals(listOf("startDate=2026-09-30"), events)
  }

  @Test
  fun cancellingTheDatePickerReportsNothing() {
    setContent(filled)

    field(C.Tag.prescription_form_date_field).performClick()
    node(C.Tag.prescription_form_date_picker_cancel).performClick()

    assertTrue(events.isEmpty())
    node(C.Tag.prescription_form_date_picker).assertDoesNotExist()
  }

  // ---------- Several medications ----------

  @Test
  fun onlyTheOpenMedicationShowsItsFields() {
    setContent(twoMedications)

    field(C.Tag.prescription_form_medication_title).assertTextContains("Medication 2")
    field(C.Tag.prescription_form_medication_name_field).assertTextContains("Dexamethasone 4 mg")
    node(C.Tag.prescriptionFormMedicationRow(1)).assertDoesNotExist()
  }

  @Test
  fun closedMedicationShowsItsNameAndASummary() {
    setContent(twoMedications)

    field(C.Tag.prescriptionFormMedicationRow(0)).assertExists()
    composeTestRule.onNodeWithText("Ondansetron 8 mg").assertExists()
    // The start date is in the row too, short like the rest of it (Figma)
    composeTestRule.onNodeWithText("1 tablet · Twice a day · from 30 Sep · 5 days").assertExists()
  }

  @Test
  fun closedMedicationSummaryLeavesOutEmptyFields() {
    val state =
        twoMedications.copy(
            draft =
                twoMedications.draft.copy(
                    medications =
                        listOf(ondansetron.copy(dosage = " ", durationDays = 1), dexamethasone)
                )
        )
    setContent(state)

    composeTestRule.onNodeWithText("Twice a day · from 30 Sep · 1 day").assertExists()
  }

  @Test
  fun closedMedicationWithoutANameShowsItsNumber() {
    val state =
        twoMedications.copy(
            draft =
                twoMedications.draft.copy(
                    medications = listOf(MedicationDraft(startDate = date), dexamethasone)
                )
        )
    setContent(state)

    composeTestRule.onNode(hasText("Medication 1")).assertExists()
  }

  @Test
  fun closedMedicationBlockedByAnotherFieldDoesNotBlameTheDuration() {
    val longDosage = ondansetron.copy(dosage = "a".repeat(Medication.MAX_TEXT_LENGTH + 1))
    setContent(
        twoMedications.copy(
            draft = twoMedications.draft.copy(medications = listOf(longDosage, dexamethasone))
        )
    )

    field(C.Tag.prescriptionFormMedicationRow(0))
        .assertTextContains(string(R.string.prescription_form_medication_check))
    composeTestRule
        .onNodeWithText(string(R.string.prescription_form_duration_check))
        .assertDoesNotExist()
  }

  @Test
  fun closedMedicationIsOneButtonForScreenReaders() {
    setContent(twoMedications)
    val label = context.getString(R.string.prescription_form_edit_medication, "Ondansetron 8 mg")

    // The row itself is the button; the pencil is no longer a second one doing the same
    field(C.Tag.prescriptionFormMedicationRow(0))
        .assert(
            SemanticsMatcher("opens the medication when activated") {
              it.config.getOrNull(SemanticsActions.OnClick)?.label == label
            }
        )
    composeTestRule.onNodeWithContentDescription(label).assertDoesNotExist()
  }

  @Test
  fun tappingAClosedMedicationReportsItsPosition() {
    setContent(twoMedications)

    field(C.Tag.prescriptionFormMedicationRow(0)).performClick()

    assertEquals(listOf("open=0"), events)
  }

  @Test
  fun removeButtonReportsTheOpenMedication() {
    setContent(twoMedications)

    field(C.Tag.prescription_form_remove_medication_button).performClick()

    assertEquals(listOf("remove=1"), events)
  }

  @Test
  fun addMedicationReportsItsClick() {
    setContent(filled)

    field(C.Tag.prescription_form_add_medication_button).performClick()

    assertEquals(listOf("add"), events)
  }

  @Test
  fun addMedicationIsDisabledWhenThePrescriptionIsFull() {
    val full = List(Prescription.MAX_MEDICATIONS) { ondansetron }
    setContent(filled.copy(draft = filled.draft.copy(medications = full)))

    field(C.Tag.prescription_form_add_medication_button).assertIsNotEnabled()
  }

  // ---------- Saving and errors ----------

  @Test
  fun savingShowsASpinnerAndDisablesSave() {
    setContent(filled.copy(save = SavePhase.SAVING))

    node(C.Tag.prescription_form_saving).assertExists()
    node(C.Tag.prescription_form_save_button).assertIsNotEnabled()
  }

  @Test
  fun closeIsDisabledWhileSaving() {
    setContent(filled.copy(save = SavePhase.SAVING))

    node(C.Tag.prescription_form_close_button).assertIsNotEnabled()
  }

  @Test
  fun closeIsDisabledOnceSaved() {
    setContent(filled.copy(save = SavePhase.SAVED))

    node(C.Tag.prescription_form_close_button).assertIsNotEnabled()
  }

  @Test
  fun closeIsEnabledWhileTheFormIsBeingFilled() {
    setContent(empty)

    node(C.Tag.prescription_form_close_button).assertIsEnabled()
  }

  @Test
  fun saveFailureIsShownOnce() {
    setContent(filled.copy(error = PrescriptionFormError.SAVE_FAILED))
    val message = string(R.string.prescription_form_error_save_failed)

    waitForText(message)
    waitForNoText(message)

    assertEquals(listOf("errorShown"), events)
  }

  @Test
  fun signedOutErrorHasItsOwnMessage() {
    setContent(filled.copy(error = PrescriptionFormError.NOT_SIGNED_IN))

    waitForText(string(R.string.prescription_form_error_not_signed_in))
  }

  // ---------- Edit mode ----------

  /** The form fields and Save, none of which may show until the saved prescription is read. */
  private fun assertNoForm() {
    node(C.Tag.prescription_form_prescribed_by_field).assertDoesNotExist()
    node(C.Tag.prescription_form_medication_name_field).assertDoesNotExist()
    node(C.Tag.prescription_form_add_medication_button).assertDoesNotExist()
    node(C.Tag.prescription_form_save_button).assertDoesNotExist()
  }

  @Test
  fun editingShowsTheEditTitleAndTheSavedPrescription() {
    setContent(filled.copy(isEditing = true))

    composeTestRule.onNodeWithText(string(R.string.prescription_form_edit_title)).assertExists()
    composeTestRule
        .onNodeWithText(string(R.string.prescription_form_add_title))
        .assertDoesNotExist()
    field(C.Tag.prescription_form_prescribed_by_field).assertTextContains("Dr. Martin")
    node(C.Tag.prescription_form_save_button).assertIsEnabled()
  }

  @Test
  fun loadingShowsASpinnerInsteadOfTheForm() {
    setContent(filled.copy(isEditing = true, load = PrescriptionFormLoad.LOADING))

    node(C.Tag.prescription_form_loading).assertExists()
    composeTestRule.onNodeWithText(string(R.string.prescription_form_edit_title)).assertExists()
    assertNoForm()
  }

  @Test
  fun closeLeavesWhileLoading() {
    setContent(filled.copy(isEditing = true, load = PrescriptionFormLoad.LOADING))

    node(C.Tag.prescription_form_close_button).assertIsEnabled().performClick()

    assertEquals(listOf("close"), events)
  }

  @Test
  fun notFoundSaysThePrescriptionNoLongerExists() {
    setContent(filled.copy(isEditing = true, load = PrescriptionFormLoad.NOT_FOUND))

    node(C.Tag.prescription_form_not_found).assertExists()
    composeTestRule
        .onNodeWithText(string(R.string.prescription_form_not_found_title))
        .assertExists()
    // Nothing to retry: the form fills in by itself if the prescription arrives later
    node(C.Tag.prescription_form_retry_button).assertDoesNotExist()
    node(C.Tag.prescription_form_loading).assertDoesNotExist()
    assertNoForm()
  }

  @Test
  fun signedOutWhileLoadingAsksToSignInAgain() {
    setContent(filled.copy(isEditing = true, load = PrescriptionFormLoad.NOT_SIGNED_IN))

    node(C.Tag.prescription_form_not_signed_in).assertExists()
    composeTestRule
        .onNodeWithText(string(R.string.prescription_form_not_signed_in_title))
        .assertExists()
    node(C.Tag.prescription_form_retry_button).assertDoesNotExist()
    assertNoForm()
  }

  @Test
  fun loadFailureOffersToTryAgain() {
    setContent(filled.copy(isEditing = true, load = PrescriptionFormLoad.FAILED))

    node(C.Tag.prescription_form_load_error).assertExists()
    composeTestRule
        .onNodeWithText(string(R.string.prescription_form_load_error_title))
        .assertExists()
    // A Firestore error isn't a connection problem: offline, the cache answers
    composeTestRule.onNodeWithText("connection", substring = true).assertDoesNotExist()
    assertNoForm()

    node(C.Tag.prescription_form_retry_button).performClick()

    assertEquals(listOf("retry"), events)
  }

  // ---------- Reviewing a scan ----------

  private fun of(position: Int, detail: MedicationDetail) =
      ScannedField.OfMedication(position, detail)

  private fun notice() = node(C.Tag.prescription_review_notice)

  // The reasons are merged into their field or row: found in the unmerged tree
  private fun fieldCheck(field: String) =
      composeTestRule.onNodeWithTag(
          C.Tag.prescriptionReviewFieldCheck(field),
          useUnmergedTree = true,
      )

  private fun rowCheck(position: Int) =
      composeTestRule.onNodeWithTag(
          C.Tag.prescriptionReviewRowCheck(position),
          useUnmergedTree = true,
      )

  @Test
  fun theTopBarSitsOnThePaperLakeBand_whenAdding_correctingOrReviewing() = topBarSitsOnTheBand()

  // Sideways, the top bar scrolls with the form: the band goes with it
  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun theTopBarSitsOnThePaperLakeBand_inLandscape() = topBarSitsOnTheBand()

  private fun topBarSitsOnTheBand() {
    setContent(empty)
    listOf(
            empty to R.string.prescription_form_add_title,
            filled.copy(isEditing = true) to R.string.prescription_form_edit_title,
            filled.copy(isScanned = true) to R.string.prescription_review_title,
        )
        .forEach { (state, title) ->
          uiState = state
          composeTestRule.waitForIdle()
          val band = node(C.Tag.prescription_form_band).fetchSemanticsNode().boundsInRoot
          val bar = composeTestRule.onNodeWithText(string(title)).fetchSemanticsNode().boundsInRoot
          val close = node(C.Tag.prescription_form_close_button).fetchSemanticsNode().boundsInRoot
          assertEquals(0f, band.top)
          assertTrue("title $bar in the band $band", band.contains(bar.center))
          assertTrue("close $close in the band $band", band.contains(close.center))
        }
  }

  @Test
  fun aScanIsReviewedWithWhatToCheckInTheNotice() {
    // Dexamethasone is open; Ondansetron's frequency, on its closed row, is to check
    setContent(
        twoMedications.copy(
            isScanned = true,
            toCheck = setOf(ScannedField.PrescribedOn, of(0, MedicationDetail.FREQUENCY)),
        )
    )

    composeTestRule.onNodeWithText(string(R.string.prescription_review_title)).assertExists()
    notice().assertTextContains(string(R.string.prescription_review_read), substring = true)
    notice().assertTextContains("2 details need checking", substring = true)
    notice()
        .assertTextContains(
            string(R.string.prescription_review_correct_anything),
            substring = true,
        )
    fieldCheck("prescribed_on")
        .assertTextContains(string(R.string.prescription_review_date_hard_to_read))
    rowCheck(0).assertTextContains(string(R.string.prescription_review_check_frequency))
  }

  @Test
  fun detailsTheScanDidNotFindAreSaidToBeMissing() {
    // No doctor, and Dexamethasone (closed, second) has no dosage
    setContent(
        twoMedications.copy(
            draft = twoMedications.draft.copy(prescribedBy = ""),
            openMedication = 0,
            isScanned = true,
            toCheck = setOf(ScannedField.PrescribedBy, of(1, MedicationDetail.DOSAGE)),
        )
    )

    notice().assertTextContains(string(R.string.prescription_review_partly_read), substring = true)
    fieldCheck("prescribed_by").assertTextContains(string(R.string.prescription_review_not_found))
    rowCheck(1).assertTextContains(string(R.string.prescription_review_dosage_not_found))
  }

  @Test
  fun aDateTheScanDidNotFindSaysTodayIsUsed() {
    setContent(
        twoMedications.copy(
            isScanned = true,
            toCheck = setOf(ScannedField.PrescribedOn),
            dateFound = false,
        )
    )

    // Today's date stands in for the missing one: only part of the prescription was read
    notice().assertTextContains(string(R.string.prescription_review_partly_read), substring = true)
    fieldCheck("prescribed_on")
        .assertTextContains(string(R.string.prescription_review_date_not_found))
  }

  @Test
  fun everyDetailOfTheOpenMedicationCanBeToCheck() {
    setContent(
        filled.copy(
            isScanned = true,
            toCheck = MedicationDetail.entries.map { of(0, it) }.toSet(),
        )
    )
    val mayDiffer = string(R.string.prescription_review_may_differ)

    for (detail in listOf("name", "dosage", "frequency", "duration")) {
      fieldCheck(detail).assertTextContains(mayDiffer)
    }
    // Not the color alone: each field to check has an icon screen readers name
    composeTestRule
        .onAllNodes(hasContentDescription(string(R.string.prescription_review_needs_checking)))
        .assertCountEquals(4)
    notice().assertTextContains("4 details need checking", substring = true)
  }

  @Test
  fun anEmptyDurationToCheckWasNotFound() {
    setContent(
        filled.copy(
            draft = filled.draft.copy(medications = listOf(ondansetron.copy(durationDays = null))),
            isScanned = true,
            toCheck = setOf(of(0, MedicationDetail.DURATION)),
        )
    )

    fieldCheck("duration").assertTextContains(string(R.string.prescription_review_not_found))
  }

  @Test
  fun theNoticeCountsTheDetailsOfEveryMedication_andTheOpenOneHasNoRowLine() {
    // Dexamethasone is open: its frequency is on its field, Ondansetron's dosage on its row
    setContent(
        twoMedications.copy(
            isScanned = true,
            toCheck =
                setOf(
                    ScannedField.PrescribedBy,
                    of(0, MedicationDetail.DOSAGE),
                    of(1, MedicationDetail.FREQUENCY),
                ),
        )
    )

    notice().assertTextContains("3 details need checking", substring = true)
    fieldCheck("frequency").assertExists()
    rowCheck(0).assertExists()
    rowCheck(1).assertDoesNotExist()
  }

  @Test
  fun detailsToCheckDoNotBlockSave() {
    setContent(
        filled.copy(
            isScanned = true,
            toCheck = setOf(ScannedField.PrescribedOn, of(0, MedicationDetail.DOSAGE)),
        )
    )

    node(C.Tag.prescription_form_save_button).assertIsEnabled().performClick()

    assertEquals(listOf("save"), events)
  }

  @Test
  fun aRowWithSeveralDetailsToCheckSaysHowMany() {
    setContent(
        twoMedications.copy(
            isScanned = true,
            toCheck = setOf(of(0, MedicationDetail.DOSAGE), of(0, MedicationDetail.DURATION)),
        )
    )

    rowCheck(0).assertTextContains("Please check 2 details")
  }

  @Test
  fun aMissingNameOrAnInvalidDurationComesBeforeTheDetailToCheck() {
    setContent(
        filled.copy(
            draft =
                filled.draft.copy(
                    medications = listOf(ondansetron.copy(name = "", durationDays = 0))
                ),
            isScanned = true,
            toCheck = setOf(of(0, MedicationDetail.NAME), of(0, MedicationDetail.DURATION)),
        )
    )

    // Both block Save: their red warning is shown, not the reason to check them
    composeTestRule.onNodeWithText(string(R.string.prescription_form_name_missing)).assertExists()
    composeTestRule
        .onNodeWithText(
            context.getString(
                R.string.prescription_form_duration_invalid,
                Medication.MAX_DURATION_DAYS,
            )
        )
        .assertExists()
    fieldCheck("name").assertDoesNotExist()
    fieldCheck("duration").assertDoesNotExist()
  }

  @Test
  fun aClosedMedicationThatBlocksSaveShowsItsProblemNotWhatToCheck() {
    setContent(
        twoMedications.copy(
            draft =
                twoMedications.draft.copy(
                    medications = listOf(ondansetron.copy(name = ""), dexamethasone)
                ),
            isScanned = true,
            toCheck = setOf(of(0, MedicationDetail.FREQUENCY)),
        )
    )

    rowCheck(0).assertDoesNotExist()
  }

  @Test
  fun onceEverythingIsCheckedTheNoticeNoLongerCountsDetails() {
    setContent(filled.copy(isScanned = true))

    notice()
        .assertTextEquals(
            string(R.string.prescription_review_read) +
                " " +
                string(R.string.prescription_review_correct_anything)
        )
    composeTestRule
        .onAllNodes(hasContentDescription(string(R.string.prescription_review_needs_checking)))
        .assertCountEquals(0)
  }

  @Test
  fun theNoticeSaysHowManyMedicationsWereLeftOut() {
    setContent(filled.copy(isScanned = true, leftOut = 5))

    notice()
        .assertTextContains(
            "5 more medications were read but not added: a prescription holds 20 at most.",
            substring = true,
        )
  }

  @Test
  fun theNoticeSaysWhenOneMedicationWasLeftOut() {
    setContent(filled.copy(isScanned = true, leftOut = 1))

    notice()
        .assertTextContains(
            "1 more medication was read but not added: a prescription holds 20 at most.",
            substring = true,
        )
  }

  @Test
  fun aFormNotFromAScanIsNotAReview() {
    setContent(filled)

    notice().assertDoesNotExist()
    composeTestRule.onNodeWithText(string(R.string.prescription_form_add_title)).assertExists()
  }

  // ---------- Keyboard ----------

  @Test
  fun keyboardOpenOnDurationKeepsTheFieldAndSaveAboveIt() {
    setContent(filled)
    val keyboardHeight = 600

    // Focus without a tap: a tap can open the text handle, a window of its own
    composeTestRule.onNodeWithTag(C.Tag.prescription_form_duration_field).performScrollTo()
    composeTestRule.onNodeWithTag(C.Tag.prescription_form_duration_field).requestFocus()
    composeTestRule.showKeyboard(keyboardHeight)

    val save = composeTestRule.onNodeWithTag(C.Tag.prescription_form_save_button)
    save.assertIsAboveKeyboard(keyboardHeight)
    // The field being typed is scrolled into what is left between the top bar and Save
    val saveTop = save.fetchSemanticsNode().boundsInRoot.top
    val duration = composeTestRule.onNodeWithTag(C.Tag.prescription_form_duration_field)
    assertTrue(duration.fetchSemanticsNode().boundsInRoot.bottom <= saveTop)
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscapeLoadingKeepsTheTitleAndCloseOnScreen() {
    setContent(filled.copy(isEditing = true, load = PrescriptionFormLoad.LOADING))

    composeTestRule
        .onNodeWithText(string(R.string.prescription_form_edit_title))
        .assertIsFullyVisible()
    node(C.Tag.prescription_form_close_button).assertIsFullyVisible()
    node(C.Tag.prescription_form_loading).assertExists()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscapeKeyboardOpenOnDurationKeepsTheWholeFieldVisible() {
    setContent(filled)
    val keyboardHeight = 500
    val duration = composeTestRule.onNodeWithTag(C.Tag.prescription_form_duration_field)

    // Focus without a tap: a tap can open the text handle, a window of its own
    duration.performScrollTo().requestFocus()
    composeTestRule.showKeyboard(keyboardHeight)

    duration.performScrollTo().assertIsFullyVisible()
    duration.assertIsAboveKeyboard(keyboardHeight)
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscapeKeyboardOpenSaveAndCloseCanBeScrolledTo() {
    setContent(filled)
    val keyboardHeight = 500
    composeTestRule.showKeyboard(keyboardHeight)

    val save = composeTestRule.onNodeWithTag(C.Tag.prescription_form_save_button)
    save.performScrollTo().assertIsFullyVisible()
    save.assertIsAboveKeyboard(keyboardHeight)
    composeTestRule
        .onNodeWithTag(C.Tag.prescription_form_close_button)
        .performScrollTo()
        .assertIsFullyVisible()
  }

  // ---------- The patient reviews a caregiver's prescription (US-43) ----------

  private val toConfirm = filled.copy(isEditing = true, isToConfirm = true)

  @Test
  fun toConfirm_asksToCheckIt_thenConfirmOrReject() {
    setContent(toConfirm)

    composeTestRule.onNodeWithText(string(R.string.prescription_confirm_title)).assertExists()
    node(C.Tag.prescription_confirm_notice).assertIsDisplayed()
    node(C.Tag.prescription_form_save_button)
        .assertTextContains(string(R.string.prescription_confirm_save))
        .performClick()
    node(C.Tag.prescription_form_reject_button).performClick()

    // Rejecting asks first: Cancel keeps it, Reject rejects it
    node(C.Tag.prescription_reject_dialog).assertIsDisplayed()
    node(C.Tag.prescription_reject_cancel).performClick()
    node(C.Tag.prescription_reject_dialog).assertDoesNotExist()
    node(C.Tag.prescription_form_reject_button).performClick()
    node(C.Tag.prescription_reject_confirm).performClick()
    assertEquals(
        listOf("save", "reject", "cancelRejection", "reject", "confirmRejection"),
        events,
    )
  }

  @Test
  fun rejectionThatDidntHappen_saysWhy() {
    setContent(toConfirm)
    for ((error, message) in
        listOf(
            PrescriptionFormError.REJECT_FAILED to R.string.prescription_form_error_reject_failed,
            PrescriptionFormError.ALREADY_CONFIRMED to R.string.prescription_form_already_confirmed,
        )) {
      composeTestRule.runOnIdle { uiState = toConfirm.copy(error = error) }
      waitForText(string(message))
    }
  }

  @Test
  fun aPrescriptionNotToConfirm_hasNoReject() {
    setContent(filled.copy(isEditing = true))

    node(C.Tag.prescription_form_reject_button).assertDoesNotExist()
    node(C.Tag.prescription_confirm_notice).assertDoesNotExist()
    node(C.Tag.prescription_form_save_button)
        .assertTextContains(string(R.string.prescription_form_save))
  }

  // ---------- A caregiver adds for the patient (US-43) ----------

  @Test
  fun caregiver_titleSaysWhosePrescriptionsItGoesTo() {
    setContent(filled, caregiverOf = "Anne")
    val cases =
        listOf(
            filled to R.string.prescription_form_add_title_for,
            filled.copy(isScanned = true) to R.string.prescription_review_title_for,
        )
    for ((state, title) in cases) {
      uiState = state
      composeTestRule.onNodeWithText(context.getString(title, "Anne")).assertExists()
    }
    composeTestRule
        .onNodeWithText(string(R.string.prescription_form_add_title))
        .assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun toConfirm_landscape_rejectAndConfirmCanBeReached() {
    setContent(toConfirm)

    node(C.Tag.prescription_form_reject_button).performScrollTo().assertIsFullyVisible()
    node(C.Tag.prescription_form_save_button).performScrollTo().assertIsFullyVisible()
  }

  private fun rejectDialog_isFullyVisible() {
    setContent(toConfirm.copy(isConfirmingRejection = true))

    node(C.Tag.prescription_reject_confirm).assertIsFullyVisible()
    node(C.Tag.prescription_reject_cancel).assertIsFullyVisible()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun rejectDialog_landscape_isFullyVisible() = rejectDialog_isFullyVisible()

  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun rejectDialog_smallLandscape_isFullyVisible() = rejectDialog_isFullyVisible()

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun caregiver_landscapeKeyboardOpen_titleCloseAndSaveCanBeScrolledTo() {
    setContent(filled.copy(isScanned = true), caregiverOf = "Anne")
    val keyboardHeight = 500
    composeTestRule.showKeyboard(keyboardHeight)

    composeTestRule
        .onNodeWithText(context.getString(R.string.prescription_review_title_for, "Anne"))
        .performScrollTo()
        .assertIsFullyVisible()
    node(C.Tag.prescription_form_close_button).performScrollTo().assertIsFullyVisible()
    val save = node(C.Tag.prescription_form_save_button)
    save.performScrollTo().assertIsFullyVisible()
    save.assertIsAboveKeyboard(keyboardHeight)
  }

  // ---------- PrescriptionFormScreen ----------

  private val uid = "patient-1"
  private val zone = ZoneId.of("Europe/Zurich")
  private val clock = Clock.fixed(date.atTime(9, 30).atZone(zone).toInstant(), zone)
  private val medications = FakeMedicationRepository()

  /**
   * Shows PrescriptionFormScreen with its view model, for a signed-in user, correcting the saved
   * prescription [prescriptionId] if given.
   */
  private fun setScreen(prescriptionId: String? = null) {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = uid) })
    runBlocking { auth.signInWithGoogle("token") }
    val viewModel =
        PrescriptionFormViewModel(
            auth,
            medications,
            clock = clock,
            prescriptionId = prescriptionId,
        )
    composeTestRule.setContent {
      PrescriptionFormScreen(
          viewModel = viewModel,
          onClose = { events += "close" },
          onSaved = { events += "saved" },
      )
    }
  }

  @Test
  fun screen_savesWhatWasTypedAndLeaves() {
    setScreen()
    node(C.Tag.prescription_form_save_button).assertIsNotEnabled()

    field(C.Tag.prescription_form_prescribed_by_field).performTextInput("Dr. Martin")
    field(C.Tag.prescription_form_medication_name_field).performTextInput("Ondansetron 8 mg")
    field(C.Tag.prescription_form_duration_field).performTextInput("5")
    node(C.Tag.prescription_form_save_button).assertIsEnabled().performClick()

    // Saving runs on the main thread: once Compose is idle, the form has left
    composeTestRule.waitForIdle()
    val saved = medications.prescriptionsOf(uid).single()
    assertEquals("Dr. Martin", saved.prescribedBy)
    assertEquals(date, saved.prescribedOn)
    assertEquals("Ondansetron 8 mg", saved.medications.single().name)
    assertEquals(5, saved.medications.single().durationDays)
    assertEquals(listOf("saved"), events)
  }

  @Test
  fun screen_addingAndRemovingAMedicationUpdatesTheForm() {
    setScreen()
    field(C.Tag.prescription_form_medication_name_field).performTextInput("Ondansetron")

    field(C.Tag.prescription_form_add_medication_button).performClick()

    field(C.Tag.prescription_form_medication_title).assertTextContains("Medication 2")
    field(C.Tag.prescriptionFormMedicationRow(0)).assertTextContains("Ondansetron")
    node(C.Tag.prescription_form_save_button).assertIsNotEnabled()

    field(C.Tag.prescription_form_remove_medication_button).performClick()

    field(C.Tag.prescription_form_medication_name_field).assertTextContains("Ondansetron")
    node(C.Tag.prescription_form_remove_medication_button).assertDoesNotExist()
    node(C.Tag.prescription_form_save_button).assertIsEnabled()
  }

  @Test
  fun screen_blankNameIsStillFlaggedAfterOpeningAnotherMedication() {
    setScreen()
    val missing = string(R.string.prescription_form_name_missing)
    field(C.Tag.prescription_form_medication_name_field).performTextInput("Ond")
    field(C.Tag.prescription_form_medication_name_field).performTextClearance()

    // Leaves the first medication for a new one, then comes back to it
    field(C.Tag.prescription_form_add_medication_button).performClick()
    field(C.Tag.prescriptionFormMedicationRow(0)).performClick()

    // Both the open medication and the closed new one say their name is missing
    composeTestRule.onAllNodesWithText(missing).assertCountEquals(2)
    composeTestRule.onNodeWithText(string(R.string.prescription_form_required)).assertDoesNotExist()
  }

  @Test
  fun screen_closeLeavesWithoutSaving() {
    setScreen()
    field(C.Tag.prescription_form_medication_name_field).performTextInput("Ondansetron")

    node(C.Tag.prescription_form_close_button).performClick()

    assertEquals(listOf("close"), events)
    assertTrue(medications.prescriptionsOf(uid).isEmpty())
    assertFalse("saved" in events)
  }

  private val saved =
      Prescription(
          id = "rx-1",
          prescribedBy = "Dr. Martin",
          prescribedOn = date,
          medications =
              listOf(
                  Medication(
                      id = "med-1",
                      prescriptionId = "rx-1",
                      name = "Ondansetron 8 mg",
                      startDate = date,
                  )
              ),
      )

  @Test
  fun screen_editingStartsFromTheSavedPrescriptionAndSavesTheCorrection() {
    medications.seed(uid, saved)
    setScreen(prescriptionId = saved.id)

    composeTestRule.onNodeWithText(string(R.string.prescription_form_edit_title)).assertExists()
    field(C.Tag.prescription_form_prescribed_by_field).assertTextContains("Dr. Martin")
    field(C.Tag.prescription_form_prescribed_by_field).performTextClearance()
    field(C.Tag.prescription_form_prescribed_by_field).performTextInput("Dr. Weber")
    node(C.Tag.prescription_form_save_button).performClick()

    composeTestRule.waitForIdle()
    val corrected = medications.prescriptionsOf(uid).single()
    assertEquals("Dr. Weber", corrected.prescribedBy)
    // The saved medication stays the same medication
    assertEquals("med-1", corrected.medications.single().id)
    assertEquals(listOf("saved"), events)
  }

  @Test
  fun screen_tryAgainReadsThePrescriptionAgainAfterAFailure() {
    medications.seed(uid, saved)
    medications.readError = IllegalStateException("Firestore failed")
    setScreen(prescriptionId = saved.id)
    node(C.Tag.prescription_form_load_error).assertExists()

    medications.readError = null
    node(C.Tag.prescription_form_retry_button).performClick()

    node(C.Tag.prescription_form_load_error).assertDoesNotExist()
    field(C.Tag.prescription_form_prescribed_by_field).assertTextContains("Dr. Martin")
  }

  // ---------- A prescription already saved (US-39) ----------

  private val savedBefore =
      Prescription(
          id = "presc-1",
          prescribedBy = "Dr. Martin",
          prescribedOn = LocalDate.of(2026, 9, 29),
          medications =
              listOf("Ondansetron 8 mg", "Dexamethasone 4 mg").mapIndexed { i, name ->
                Medication("med-$i", "presc-1", name, startDate = LocalDate.of(2026, 9, 29))
              },
      )

  @Test
  fun alreadySaved_saysWhichPrescription_andOffersBothChoices() {
    val messages =
        mapOf(
            savedBefore to
                "Dr. Martin's prescription from 29 Sep 2026, with the same 2 medications",
            savedBefore.copy(prescribedBy = null, medications = savedBefore.medications.take(1)) to
                "Your prescription from 29 Sep 2026, with the same medication",
        )
    setContent(empty)
    for ((prescription, message) in messages) {
      uiState = empty.copy(alreadySaved = prescription)
      composeTestRule.onNodeWithTag(C.Tag.prescription_already_saved_dialog).assertIsDisplayed()
      composeTestRule
          .onNodeWithText(string(R.string.prescription_already_saved_title))
          .assertIsDisplayed()
      composeTestRule.onNodeWithText(message, substring = true).assertIsDisplayed()
    }
    composeTestRule.onNodeWithTag(C.Tag.prescription_already_saved_keep_editing).performClick()
    composeTestRule.onNodeWithTag(C.Tag.prescription_already_saved_save_anyway).performClick()
    // Back, like tapping outside, goes back to the form
    Espresso.pressBack()
    composeTestRule.runOnIdle {
      assertEquals(listOf("keepEditing", "saveAnyway", "keepEditing"), events)
    }
  }

  /**
   * The longest message (a prescriber and several medications) still leaves both choices visible.
   */
  private fun alreadySaved_showsBothChoicesSideways() {
    setContent(empty.copy(alreadySaved = savedBefore))
    composeTestRule.onNodeWithTag(C.Tag.prescription_already_saved_dialog).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(C.Tag.prescription_already_saved_keep_editing)
        .assertIsFullyVisible()
    composeTestRule
        .onNodeWithTag(C.Tag.prescription_already_saved_save_anyway)
        .assertIsFullyVisible()
  }

  @Config(qualifiers = LANDSCAPE_PHONE)
  @Test
  fun alreadySaved_onALandscapePhone_showsBothChoices() = alreadySaved_showsBothChoicesSideways()

  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  @Test
  fun alreadySaved_onASmallLandscapePhone_showsBothChoices() =
      alreadySaved_showsBothChoicesSideways()

  @Test
  fun aRenewal_isSaidAboveTheForm_withOrWithoutAPrescriber() {
    val notices =
        mapOf(
            savedBefore to
                "Same medications as Dr. Martin's prescription from 29 Sep 2026: this looks like a renewal.",
            savedBefore.copy(prescribedBy = null) to
                "Same medications as your prescription from 29 Sep 2026: this looks like a renewal.",
        )
    setContent(empty)
    composeTestRule.onNodeWithTag(C.Tag.prescription_renewal_notice).assertDoesNotExist()
    for ((prescription, notice) in notices) {
      uiState = empty.copy(renewalOf = prescription)
      composeTestRule.onNodeWithTag(C.Tag.prescription_renewal_notice).assertIsDisplayed()
      composeTestRule.onNodeWithText(notice, substring = true).assertIsDisplayed()
    }
  }

  @Test
  fun caregiver_isWarnedAboutThePatientsPrescription_notTheirOwn() {
    // A prescription without a doctor is named after the patient, in the dialog and the notice
    val withoutDoctor = savedBefore.copy(prescribedBy = null)
    setContent(empty.copy(renewalOf = withoutDoctor), caregiverOf = "Anne")
    composeTestRule
        .onNodeWithText(
            "Same medications as Anne's prescription from 29 Sep 2026",
            substring = true,
        )
        .assertIsDisplayed()

    uiState = empty.copy(alreadySaved = withoutDoctor)
    composeTestRule
        .onNodeWithText(context.getString(R.string.prescription_already_saved_title_for, "Anne"))
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithText("Anne's prescription from 29 Sep 2026", substring = true)
        .assertIsDisplayed()
    for (yours in listOf("Already in your prescriptions", "Your prescription from")) {
      composeTestRule.onNodeWithText(yours, substring = true).assertDoesNotExist()
    }
  }

  @Test
  fun phoneRotated_whileAskingAboutAPrescriptionAlreadySaved_stillAsks() {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "patient-1") })
    runBlocking { auth.signInWithGoogle("token") }
    val medications = FakeMedicationRepository().apply { seed("patient-1", savedBefore) }
    val draft =
        savedBefore.toDraft().let {
          it.copy(medications = it.medications.map { m -> m.copy(id = null) })
        }
    val viewModel = PrescriptionFormViewModel(auth, medications, initialDraft = draft)
    val show = {
      composeTestRule.activity.setContent {
        PrescriptionFormScreen(viewModel = viewModel, onClose = {}, onSaved = {})
      }
    }
    composeTestRule.runOnUiThread(show)
    composeTestRule.onNodeWithTag(C.Tag.prescription_form_save_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.prescription_already_saved_dialog).assertIsDisplayed()

    // Rotating recreates the activity; the form's ViewModel survives it
    composeTestRule.activityRule.scenario.recreate()
    composeTestRule.runOnUiThread(show)

    composeTestRule.onNodeWithTag(C.Tag.prescription_already_saved_dialog).assertIsDisplayed()
    assertEquals(listOf(savedBefore), medications.prescriptionsOf("patient-1"))
  }
}
