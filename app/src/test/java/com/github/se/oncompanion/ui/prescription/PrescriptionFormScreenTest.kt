package com.github.se.oncompanion.ui.prescription

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.medication.ManageMedicationSchedule
import com.github.se.oncompanion.domain.medication.MedicationDraft
import com.github.se.oncompanion.domain.medication.PrescriptionDraft
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
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

@RunWith(AndroidJUnit4::class)
class PrescriptionFormScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

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
          )
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

  /** Shows PrescriptionFormContent like the real screen does: typing updates the state. */
  private fun setContent(state: PrescriptionFormUiState) {
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
    composeTestRule.onNodeWithText("1 tablet · Twice a day · 5 days").assertExists()
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

    composeTestRule.onNodeWithText("Twice a day · 1 day").assertExists()
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
  fun editButtonOfAClosedMedicationReportsItsPosition() {
    setContent(twoMedications.copy(openMedication = 0))

    field(C.Tag.prescriptionFormEditMedication(1)).performClick()

    assertEquals(listOf("open=1"), events)
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
    setContent(filled.copy(isSaving = true))

    node(C.Tag.prescription_form_saving).assertExists()
    node(C.Tag.prescription_form_save_button).assertIsNotEnabled()
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

  // ---------- PrescriptionFormScreen ----------

  private val uid = "patient-1"
  private val zone = ZoneId.of("Europe/Zurich")
  private val clock = Clock.fixed(date.atTime(9, 30).atZone(zone).toInstant(), zone)
  private val medications = FakeMedicationRepository()

  /** Shows PrescriptionFormScreen with its view model, for a signed-in user. */
  private fun setScreen() {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = uid) })
    runBlocking { auth.signInWithGoogle("token") }
    val viewModel = PrescriptionFormViewModel(auth, ManageMedicationSchedule(medications), clock)
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

    composeTestRule.waitUntil(5_000) { "saved" in events }
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
  fun screen_closeLeavesWithoutSaving() {
    setScreen()
    field(C.Tag.prescription_form_medication_name_field).performTextInput("Ondansetron")

    node(C.Tag.prescription_form_close_button).performClick()

    assertEquals(listOf("close"), events)
    assertTrue(medications.prescriptionsOf(uid).isEmpty())
    assertFalse("saved" in events)
  }
}
