// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import com.github.se.oncompanion.domain.medication.ManageMedicationSchedule
import com.github.se.oncompanion.domain.medication.MedicationDraft
import com.github.se.oncompanion.domain.medication.PrescriptionDraft
import com.github.se.oncompanion.domain.medication.scan.MedicationDetail
import com.github.se.oncompanion.domain.medication.scan.ScannedField
import com.github.se.oncompanion.domain.medication.scan.ScannedPrescription
import com.github.se.oncompanion.domain.medication.toDraft
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Waits for [gate] (if set) before adding or updating, to observe the form while it is saving. An
 * update waits after [ManageMedicationSchedule.update] has checked that the prescription exists.
 */
private class GatedMedicationRepository(private val delegate: FakeMedicationRepository) :
    MedicationRepository by delegate {
  var gate: CompletableDeferred<Unit>? = null

  override suspend fun addPrescription(uid: String, prescription: Prescription) {
    gate?.await()
    delegate.addPrescription(uid, prescription)
  }

  override suspend fun updatePrescription(uid: String, prescription: Prescription) {
    gate?.await()
    delegate.updatePrescription(uid, prescription)
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PrescriptionFormViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val uid = "patient-1"
  private val zone = ZoneId.of("Europe/Zurich")
  private val today = LocalDate.of(2026, 10, 8)
  private val clock = Clock.fixed(today.atTime(9, 30).atZone(zone).toInstant(), zone)

  private lateinit var auth: FakeAuthRepository
  private lateinit var fakeMedications: FakeMedicationRepository
  private lateinit var medications: GatedMedicationRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = FakeAuthRepository(onSignIn = { AuthUser(uid = uid) })
    runBlocking { auth.signInWithGoogle("token") }
    fakeMedications = FakeMedicationRepository()
    medications = GatedMedicationRepository(fakeMedications)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel(initialDraft: PrescriptionDraft? = null) =
      PrescriptionFormViewModel(auth, medications, clock = clock, initialDraft = initialDraft)

  private val PrescriptionFormViewModel.state
    get() = uiState.value

  private val PrescriptionFormViewModel.medications
    get() = uiState.value.draft.medications

  /** The medication open for editing. */
  private val PrescriptionFormViewModel.medication
    get() = medications[uiState.value.openMedication]

  /** A form with medications named [names], the last one open. */
  private fun viewModelWith(vararg names: String): PrescriptionFormViewModel {
    val viewModel = viewModel()
    names.forEachIndexed { index, name ->
      if (index > 0) viewModel.addMedication()
      viewModel.onMedicationNameChange(name)
    }
    return viewModel
  }

  // ---------- Starting the form ----------

  @Test
  fun emptyFormStartsTodayWithOneEmptyMedication() {
    val viewModel = viewModel()

    assertEquals("", viewModel.state.draft.prescribedBy)
    assertEquals(today, viewModel.state.draft.prescribedOn)
    assertEquals(listOf(MedicationDraft(startDate = today)), viewModel.medications)
    assertEquals(0, viewModel.state.openMedication)
    assertFalse(viewModel.state.isSaving)
    assertFalse(viewModel.state.isSaved)
    assertNull(viewModel.state.error)
  }

  @Test
  fun formCanStartFromADraft() {
    val draft =
        PrescriptionDraft(
            prescribedBy = "Dr. Martin",
            prescribedOn = today.minusDays(3),
            medications =
                listOf(MedicationDraft(name = "Ondansetron 8 mg", startDate = today.minusDays(2))),
        )

    val viewModel = viewModel(draft)

    assertEquals(draft, viewModel.state.draft)
    assertEquals(0, viewModel.state.openMedication)
    assertTrue(viewModel.state.canSave)
  }

  @Test
  fun textOfAGivenDraftIsCutToTheLimits() {
    val tooLong = "a".repeat(200)
    val draft =
        PrescriptionDraft(
            prescribedBy = tooLong,
            prescribedOn = today,
            medications =
                listOf(
                    MedicationDraft(
                        name = tooLong,
                        dosage = tooLong,
                        frequency = tooLong,
                        startDate = today,
                    )
                ),
        )

    val viewModel = viewModel(draft)

    assertEquals(Prescription.MAX_PRESCRIBED_BY_LENGTH, viewModel.state.draft.prescribedBy.length)
    assertEquals(Medication.MAX_TEXT_LENGTH, viewModel.medication.name.length)
    assertEquals(Medication.MAX_TEXT_LENGTH, viewModel.medication.dosage.length)
    assertEquals(Medication.MAX_TEXT_LENGTH, viewModel.medication.frequency.length)
    assertTrue(viewModel.state.canSave)
    assertTrue(viewModel.state.invalidMedications.isEmpty())
  }

  // ---------- Editing ----------

  @Test
  fun editingAFieldUpdatesTheDraft() {
    val viewModel = viewModel()

    viewModel.onPrescribedByChange("Dr. Martin")
    viewModel.onMedicationNameChange("Ondansetron 8 mg")
    viewModel.onDosageChange("1 tablet")
    viewModel.onFrequencyChange("Twice a day")
    viewModel.onStartDateChange(today.plusDays(2))
    viewModel.onDurationChange("5")

    assertEquals("Dr. Martin", viewModel.state.draft.prescribedBy)
    assertEquals(
        MedicationDraft(
            name = "Ondansetron 8 mg",
            dosage = "1 tablet",
            frequency = "Twice a day",
            startDate = today.plusDays(2),
            durationDays = 5,
        ),
        viewModel.medication,
    )
  }

  @Test
  fun textLongerThanTheLimitIsCut() {
    val viewModel = viewModel()
    val tooLong = "a".repeat(200)

    viewModel.onPrescribedByChange(tooLong)
    viewModel.onMedicationNameChange(tooLong)
    viewModel.onDosageChange(tooLong)
    viewModel.onFrequencyChange(tooLong)

    assertEquals(Prescription.MAX_PRESCRIBED_BY_LENGTH, viewModel.state.draft.prescribedBy.length)
    assertEquals(Medication.MAX_TEXT_LENGTH, viewModel.medication.name.length)
    assertEquals(Medication.MAX_TEXT_LENGTH, viewModel.medication.dosage.length)
    assertEquals(Medication.MAX_TEXT_LENGTH, viewModel.medication.frequency.length)
  }

  @Test
  fun durationKeepsOnlyDigits() {
    val viewModel = viewModel()

    viewModel.onDurationChange("5 days")
    assertEquals(5, viewModel.medication.durationDays)

    viewModel.onDurationChange("1x4")
    assertEquals(14, viewModel.medication.durationDays)
  }

  @Test
  fun durationWithoutDigitsMeansNoEnd() {
    val viewModel = viewModel()
    viewModel.onDurationChange("5")

    viewModel.onDurationChange("")
    assertNull(viewModel.medication.durationDays)

    viewModel.onDurationChange("abc")
    assertNull(viewModel.medication.durationDays)
  }

  @Test
  fun durationIsCutToFourDigits() {
    val viewModel = viewModel()

    viewModel.onDurationChange("123456789012")

    assertEquals(1234, viewModel.medication.durationDays)
  }

  @Test
  fun changingThePrescriptionDateMovesAStartDateTheUserNeverChose() {
    val viewModel = viewModel()

    viewModel.onPrescribedOnChange(today.minusDays(4))

    assertEquals(today.minusDays(4), viewModel.state.draft.prescribedOn)
    assertEquals(today.minusDays(4), viewModel.medication.startDate)
  }

  @Test
  fun changingThePrescriptionDateKeepsAStartDateTheUserChose() {
    val viewModel = viewModel()
    viewModel.onStartDateChange(today.plusDays(1))

    viewModel.onPrescribedOnChange(today.minusDays(4))

    assertEquals(today.minusDays(4), viewModel.state.draft.prescribedOn)
    assertEquals(today.plusDays(1), viewModel.medication.startDate)
  }

  @Test
  fun untouchedStartDateKeepsFollowingThePrescriptionDate() {
    val viewModel = viewModel()

    viewModel.onPrescribedOnChange(today.minusDays(4))
    viewModel.onPrescribedOnChange(today.minusDays(9))

    assertEquals(today.minusDays(9), viewModel.medication.startDate)
  }

  @Test
  fun startDateChosenOnTheSameDayAsThePrescriptionDoesNotMove() {
    val viewModel = viewModel()
    // The user picks the same day on purpose
    viewModel.onStartDateChange(today)

    viewModel.onPrescribedOnChange(today.minusDays(4))

    assertEquals(today.minusDays(4), viewModel.state.draft.prescribedOn)
    assertEquals(today, viewModel.medication.startDate)
  }

  @Test
  fun startDateOfAGivenDraftDoesNotMove() {
    val draft =
        PrescriptionDraft(
            prescribedOn = today,
            medications = listOf(MedicationDraft(name = "Ondansetron 8 mg", startDate = today)),
        )
    val viewModel = viewModel(draft)

    viewModel.onPrescribedOnChange(today.minusDays(4))

    assertEquals(today, viewModel.medication.startDate)
  }

  // ---------- Several medications ----------

  @Test
  fun addMedicationAddsAnEmptyOneAtTheEndAndOpensIt() {
    val viewModel = viewModelWith("Ondansetron")
    viewModel.onPrescribedOnChange(today.minusDays(4))

    viewModel.addMedication()

    assertEquals(2, viewModel.medications.size)
    assertEquals("Ondansetron", viewModel.medications[0].name)
    assertEquals(MedicationDraft(startDate = today.minusDays(4)), viewModel.medications[1])
    assertEquals(1, viewModel.state.openMedication)
  }

  @Test
  fun editingOnlyChangesTheOpenMedication() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone")

    viewModel.onDosageChange("1 tablet")
    viewModel.onDurationChange("3")

    assertEquals(MedicationDraft(name = "Ondansetron", startDate = today), viewModel.medications[0])
    assertEquals(
        MedicationDraft(
            name = "Dexamethasone",
            dosage = "1 tablet",
            startDate = today,
            durationDays = 3,
        ),
        viewModel.medications[1],
    )
  }

  @Test
  fun openMedicationChoosesTheOneToEdit() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone")

    viewModel.openMedication(0)
    viewModel.onFrequencyChange("Twice a day")

    assertEquals(0, viewModel.state.openMedication)
    assertEquals("Twice a day", viewModel.medications[0].frequency)
    assertEquals("", viewModel.medications[1].frequency)
  }

  @Test
  fun openMedicationIgnoresAPositionThatDoesNotExist() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone")

    viewModel.openMedication(2)
    viewModel.openMedication(-1)

    assertEquals(1, viewModel.state.openMedication)
  }

  @Test
  fun medicationsCanBeAddedUpToTheMaximum() {
    val viewModel = viewModel()
    assertTrue(viewModel.state.canAddMedication)

    repeat(Prescription.MAX_MEDICATIONS - 1) { viewModel.addMedication() }
    assertEquals(Prescription.MAX_MEDICATIONS, viewModel.medications.size)
    assertFalse(viewModel.state.canAddMedication)

    viewModel.addMedication()
    assertEquals(Prescription.MAX_MEDICATIONS, viewModel.medications.size)
    assertEquals(Prescription.MAX_MEDICATIONS - 1, viewModel.state.openMedication)
  }

  @Test
  fun eachMedicationFollowsThePrescriptionDateUntilItsStartDateIsChosen() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone")
    // Chooses the start date of the second one only
    viewModel.onStartDateChange(today.plusDays(2))

    viewModel.onPrescribedOnChange(today.minusDays(4))

    assertEquals(today.minusDays(4), viewModel.medications[0].startDate)
    assertEquals(today.plusDays(2), viewModel.medications[1].startDate)
  }

  @Test
  fun removeMedicationRemovesIt() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone", "Paracetamol")

    viewModel.removeMedication(1)

    assertEquals(listOf("Ondansetron", "Paracetamol"), viewModel.medications.map { it.name })
  }

  @Test
  fun theOnlyMedicationCannotBeRemoved() {
    val viewModel = viewModelWith("Ondansetron")
    assertFalse(viewModel.state.canRemoveMedication)

    viewModel.removeMedication(0)

    assertEquals(listOf("Ondansetron"), viewModel.medications.map { it.name })

    viewModel.addMedication()
    assertTrue(viewModel.state.canRemoveMedication)
  }

  @Test
  fun removeMedicationIgnoresAPositionThatDoesNotExist() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone")

    viewModel.removeMedication(2)
    viewModel.removeMedication(-1)

    assertEquals(2, viewModel.medications.size)
  }

  @Test
  fun removingAMedicationBeforeTheOpenOneKeepsTheSameOneOpen() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone", "Paracetamol")

    viewModel.removeMedication(0)

    assertEquals("Paracetamol", viewModel.medication.name)
  }

  @Test
  fun removingAMedicationAfterTheOpenOneKeepsTheSameOneOpen() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone", "Paracetamol")
    viewModel.openMedication(0)

    viewModel.removeMedication(2)

    assertEquals("Ondansetron", viewModel.medication.name)
  }

  @Test
  fun removingTheOpenMedicationOpensTheOneThatTakesItsPlace() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone", "Paracetamol")
    viewModel.openMedication(1)

    viewModel.removeMedication(1)

    assertEquals("Paracetamol", viewModel.medication.name)
  }

  @Test
  fun removingTheOpenLastMedicationOpensThePreviousOne() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone", "Paracetamol")

    viewModel.removeMedication(2)

    assertEquals("Dexamethasone", viewModel.medication.name)
  }

  @Test
  fun afterARemovalEachMedicationStillKnowsIfItsStartDateWasChosen() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone", "Paracetamol")
    viewModel.openMedication(1)
    viewModel.onStartDateChange(today.plusDays(2))

    viewModel.removeMedication(0)
    viewModel.onPrescribedOnChange(today.minusDays(4))

    // Dexamethasone had its start date chosen, Paracetamol hadn't
    assertEquals(
        listOf("Dexamethasone" to today.plusDays(2), "Paracetamol" to today.minusDays(4)),
        viewModel.medications.map { it.name to it.startDate },
    )
  }

  @Test
  fun medicationAddedToAGivenDraftFollowsThePrescriptionDate() {
    val draft =
        PrescriptionDraft(
            prescribedOn = today,
            medications = listOf(MedicationDraft(name = "Ondansetron", startDate = today)),
        )
    val viewModel = viewModel(draft)
    viewModel.addMedication()

    viewModel.onPrescribedOnChange(today.minusDays(4))

    assertEquals(
        listOf(today, today.minusDays(4)),
        viewModel.medications.map { it.startDate },
    )
  }

  @Test
  fun stateSaysWhichStartDatesTheUserChose() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone")
    assertEquals(listOf(false, false), viewModel.state.startDateChosen)

    viewModel.onStartDateChange(today.plusDays(2))
    assertEquals(listOf(false, true), viewModel.state.startDateChosen)

    viewModel.removeMedication(0)
    assertEquals(listOf(true), viewModel.state.startDateChosen)
  }

  @Test
  fun everyStartDateOfAGivenDraftCountsAsChosen() {
    val draft =
        PrescriptionDraft(
            prescribedOn = today,
            medications =
                listOf(
                    MedicationDraft(name = "Ondansetron", startDate = today),
                    MedicationDraft(name = "Dexamethasone", startDate = today),
                ),
        )
    val viewModel = viewModel(draft)
    assertEquals(listOf(true, true), viewModel.state.startDateChosen)

    viewModel.addMedication()
    assertEquals(listOf(true, true, false), viewModel.state.startDateChosen)
  }

  @Test
  fun stateBuiltFromADraftAloneCountsEveryStartDateAsChosen() {
    val draft =
        PrescriptionDraft(
            prescribedOn = today,
            medications =
                listOf(MedicationDraft(startDate = today), MedicationDraft(startDate = today)),
        )

    assertEquals(listOf(true, true), PrescriptionFormUiState(draft).startDateChosen)
  }

  @Test
  fun nameCountsAsTouchedOnceItIsEdited() {
    val viewModel = viewModel()
    assertEquals(listOf(false), viewModel.state.nameTouched)

    viewModel.onMedicationNameChange("O")

    assertEquals(listOf(true), viewModel.state.nameTouched)
  }

  @Test
  fun nameCountsAsTouchedOnceItsMedicationIsLeftForAnother() {
    val viewModel = viewModel()

    viewModel.addMedication()
    assertEquals(listOf(true, false), viewModel.state.nameTouched)

    viewModel.openMedication(0)
    assertEquals(listOf(true, true), viewModel.state.nameTouched)
  }

  @Test
  fun touchedNamesFollowTheirMedicationWhenAnotherIsRemoved() {
    val viewModel = viewModel()
    viewModel.addMedication()
    viewModel.addMedication()
    assertEquals(listOf(true, true, false), viewModel.state.nameTouched)

    viewModel.removeMedication(0)

    assertEquals(listOf(true, false), viewModel.state.nameTouched)
  }

  @Test
  fun everyNameOfAGivenDraftCountsAsTouched() {
    val draft =
        PrescriptionDraft(
            prescribedOn = today,
            medications =
                listOf(MedicationDraft(startDate = today), MedicationDraft(startDate = today)),
        )

    assertEquals(listOf(true, true), viewModel(draft).state.nameTouched)
    assertEquals(listOf(true, true), PrescriptionFormUiState(draft).nameTouched)
  }

  // ---------- Save enabled ----------

  @Test
  fun cannotSaveWithoutAMedicationName() {
    val viewModel = viewModel()
    assertFalse(viewModel.state.canSave)

    viewModel.onMedicationNameChange("   ")
    assertFalse(viewModel.state.canSave)

    viewModel.onMedicationNameChange("Ondansetron 8 mg")
    assertTrue(viewModel.state.canSave)

    viewModel.onMedicationNameChange("")
    assertFalse(viewModel.state.canSave)
  }

  @Test
  fun cannotSaveWithADurationOfZeroDaysOrOverTheMaximum() {
    val viewModel = viewModel()
    viewModel.onMedicationNameChange("Ondansetron 8 mg")

    viewModel.onDurationChange("0")
    assertFalse(viewModel.state.canSave)

    viewModel.onDurationChange((Medication.MAX_DURATION_DAYS + 1).toString())
    assertFalse(viewModel.state.canSave)

    viewModel.onDurationChange(Medication.MAX_DURATION_DAYS.toString())
    assertTrue(viewModel.state.canSave)
  }

  @Test
  fun cannotSaveWhileAnAddedMedicationHasNoName() {
    val viewModel = viewModelWith("Ondansetron")

    viewModel.addMedication()
    assertFalse(viewModel.state.canSave)

    viewModel.onMedicationNameChange("Dexamethasone")
    assertTrue(viewModel.state.canSave)
  }

  @Test
  fun stateSaysWhichMedicationsBlockSave() {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone", "Paracetamol")
    assertTrue(viewModel.state.invalidMedications.isEmpty())

    // No name for the first one, a duration of zero days for the third
    viewModel.openMedication(0)
    viewModel.onMedicationNameChange(" ")
    viewModel.openMedication(2)
    viewModel.onDurationChange("0")

    assertEquals(setOf(0, 2), viewModel.state.invalidMedications)
    assertFalse(viewModel.state.canSave)

    viewModel.onDurationChange("3")
    assertEquals(setOf(0), viewModel.state.invalidMedications)
  }

  @Test
  fun newEmptyMedicationBlocksSave() {
    val viewModel = viewModelWith("Ondansetron")

    viewModel.addMedication()

    assertEquals(setOf(1), viewModel.state.invalidMedications)
  }

  // ---------- Saving ----------

  @Test
  fun saveStoresAllTheMedications() = runTest {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone")

    viewModel.save()
    advanceUntilIdle()

    val saved = fakeMedications.prescriptionsOf(uid).single()
    assertEquals(listOf("Ondansetron", "Dexamethasone"), saved.medications.map { it.name })
    assertTrue(viewModel.state.isSaved)
  }

  @Test
  fun saveStoresThePrescriptionOfTheSignedInUser() = runTest {
    val viewModel = viewModel()
    viewModel.onPrescribedByChange(" Dr. Martin ")
    viewModel.onMedicationNameChange("Ondansetron 8 mg")
    viewModel.onDurationChange("5")

    viewModel.save()
    advanceUntilIdle()

    val saved = fakeMedications.prescriptionsOf(uid).single()
    assertEquals("Dr. Martin", saved.prescribedBy)
    assertEquals(today, saved.prescribedOn)
    assertEquals("Ondansetron 8 mg", saved.medications.single().name)
    assertEquals(today, saved.medications.single().startDate)
    assertEquals(5, saved.medications.single().durationDays)
    assertTrue(viewModel.state.isSaved)
    assertFalse(viewModel.state.isSaving)
    assertNull(viewModel.state.error)
  }

  @Test
  fun saveDoesNothingWhileTheFormIsInvalid() = runTest {
    val viewModel = viewModel()

    viewModel.save()
    advanceUntilIdle()

    assertTrue(fakeMedications.prescriptionsOf(uid).isEmpty())
    assertFalse(viewModel.state.isSaved)
    assertFalse(viewModel.state.isSaving)
    assertNull(viewModel.state.error)
  }

  @Test
  fun formIsSavingUntilThePrescriptionIsStored() = runTest {
    val gate = CompletableDeferred<Unit>()
    medications.gate = gate
    val viewModel = viewModel()
    viewModel.onMedicationNameChange("Ondansetron 8 mg")

    viewModel.save()
    advanceUntilIdle()

    assertTrue(viewModel.state.isSaving)
    assertFalse(viewModel.state.isSaved)
    assertFalse(viewModel.state.canSave)

    gate.complete(Unit)
    advanceUntilIdle()

    assertFalse(viewModel.state.isSaving)
    assertTrue(viewModel.state.isSaved)
  }

  /** Every change the user can make to the form. */
  private fun PrescriptionFormViewModel.editEverything() {
    onPrescribedByChange("Dr. House")
    onPrescribedOnChange(today.minusDays(9))
    onMedicationNameChange("Paracetamol")
    onDosageChange("2 tablets")
    onFrequencyChange("Daily")
    onStartDateChange(today.plusDays(9))
    onDurationChange("9")
    addMedication()
    removeMedication(0)
  }

  @Test
  fun editsAreIgnoredWhileSaving() = runTest {
    val gate = CompletableDeferred<Unit>()
    medications.gate = gate
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone")
    viewModel.save()
    advanceUntilIdle()
    val saving = viewModel.state

    viewModel.editEverything()

    assertEquals(saving, viewModel.state)

    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(
        listOf("Ondansetron", "Dexamethasone"),
        fakeMedications.prescriptionsOf(uid).single().medications.map { it.name },
    )
  }

  @Test
  fun editsAreIgnoredOnceSaved() = runTest {
    val viewModel = viewModelWith("Ondansetron", "Dexamethasone")
    viewModel.save()
    advanceUntilIdle()
    val saved = viewModel.state
    assertTrue(saved.isSaved)

    viewModel.editEverything()

    assertEquals(saved, viewModel.state)
  }

  @Test
  fun editsWorkAgainAfterAFailedSave() = runTest {
    fakeMedications.writeError = IOException("disk full")
    val viewModel = viewModelWith("Ondansetron")
    viewModel.save()
    advanceUntilIdle()

    viewModel.onMedicationNameChange("Dexamethasone")

    assertEquals("Dexamethasone", viewModel.medication.name)
  }

  @Test
  fun savingTwiceStoresOnePrescription() = runTest {
    val gate = CompletableDeferred<Unit>()
    medications.gate = gate
    val viewModel = viewModel()
    viewModel.onMedicationNameChange("Ondansetron 8 mg")

    viewModel.save()
    viewModel.save()
    gate.complete(Unit)
    advanceUntilIdle()
    viewModel.save()
    advanceUntilIdle()

    assertEquals(1, fakeMedications.prescriptionsOf(uid).size)
  }

  @Test
  fun saveWhenNobodyIsSignedInShowsAnError() = runTest {
    auth.signOut()
    val viewModel = viewModel()
    viewModel.onMedicationNameChange("Ondansetron 8 mg")

    viewModel.save()
    advanceUntilIdle()

    assertEquals(PrescriptionFormError.NOT_SIGNED_IN, viewModel.state.error)
    assertFalse(viewModel.state.isSaving)
    assertFalse(viewModel.state.isSaved)
    assertTrue(fakeMedications.prescriptionsOf(uid).isEmpty())
  }

  @Test
  fun failedSaveShowsAnErrorAndCanBeTriedAgain() = runTest {
    fakeMedications.writeError = IOException("disk full")
    val viewModel = viewModel()
    viewModel.onMedicationNameChange("Ondansetron 8 mg")

    viewModel.save()
    advanceUntilIdle()

    assertEquals(PrescriptionFormError.SAVE_FAILED, viewModel.state.error)
    assertFalse(viewModel.state.isSaving)
    assertFalse(viewModel.state.isSaved)
    assertEquals("Ondansetron 8 mg", viewModel.medication.name)

    fakeMedications.writeError = null
    viewModel.save()
    advanceUntilIdle()

    assertTrue(viewModel.state.isSaved)
    assertNull(viewModel.state.error)
    assertEquals(1, fakeMedications.prescriptionsOf(uid).size)
  }

  @Test
  fun clearErrorRemovesTheError() = runTest {
    auth.signOut()
    val viewModel = viewModel()
    viewModel.onMedicationNameChange("Ondansetron 8 mg")
    viewModel.save()
    advanceUntilIdle()

    viewModel.clearError()

    assertNull(viewModel.state.error)
  }

  // ---------- Reviewing a scan ----------

  /** Two scanned medications, Ondansetron then Dexaméthasone, with [toCheck] marked. */
  private fun reviewing(vararg toCheck: ScannedField): PrescriptionFormViewModel {
    val draft =
        PrescriptionDraft(
            prescribedBy = "Dr. Martin",
            prescribedOn = today,
            medications =
                listOf(
                    MedicationDraft(name = "Ondansetron 8 mg", dosage = "1 cp", startDate = today),
                    MedicationDraft(name = "Dexaméthasone 4 mg", startDate = today),
                ),
        )
    return PrescriptionFormViewModel(
        auth,
        medications,
        clock = clock,
        scan = ScannedPrescription(draft, toCheck.toSet()),
    )
  }

  private fun of(position: Int, detail: MedicationDetail) =
      ScannedField.OfMedication(position, detail)

  @Test
  fun aScanStartsAReviewWithTheDetailsToCheck() {
    val viewModel = reviewing(ScannedField.PrescribedOn, of(1, MedicationDetail.FREQUENCY))

    assertTrue(viewModel.state.isScanned)
    assertEquals(
        setOf(ScannedField.PrescribedOn, of(1, MedicationDetail.FREQUENCY)),
        viewModel.state.toCheck,
    )
    assertEquals("Dr. Martin", viewModel.state.draft.prescribedBy)
    assertEquals(2, viewModel.medications.size)
    // Details to check don't block saving: the patient may find them right
    assertTrue(viewModel.state.canSave)
  }

  @Test
  fun aFormNotFromAScanHasNothingToCheck() {
    val viewModel = viewModel()

    assertFalse(viewModel.state.isScanned)
    assertTrue(viewModel.state.toCheck.isEmpty())
    assertEquals(0, viewModel.state.leftOut)
  }

  @Test
  fun theMedicationsTheScanLeftOutAreKeptForTheNotice() {
    val draft =
        PrescriptionDraft(
            prescribedOn = today,
            medications = listOf(MedicationDraft(name = "Ondansetron 8 mg", startDate = today)),
        )

    val viewModel =
        PrescriptionFormViewModel(
            auth,
            medications,
            clock = clock,
            scan = ScannedPrescription(draft, emptySet(), leftOut = 5),
        )

    assertEquals(5, viewModel.state.leftOut)
  }

  @Test
  fun editingADetailToCheckMeansItIsChecked() {
    val viewModel =
        reviewing(
            ScannedField.PrescribedBy,
            ScannedField.PrescribedOn,
            of(0, MedicationDetail.NAME),
            of(0, MedicationDetail.DOSAGE),
            of(0, MedicationDetail.FREQUENCY),
            of(0, MedicationDetail.DURATION),
        )

    viewModel.onPrescribedByChange("Dr. Martine")
    viewModel.onPrescribedOnChange(today.minusDays(1))
    viewModel.onMedicationNameChange("Ondansétron 8 mg")
    viewModel.onDosageChange("1 comprimé")
    viewModel.onFrequencyChange("2x/jour")
    viewModel.onDurationChange("5")

    assertTrue(viewModel.state.toCheck.isEmpty())
  }

  @Test
  fun editingOneDetailLeavesTheOthersToCheck() {
    val viewModel =
        reviewing(
            ScannedField.PrescribedOn,
            of(0, MedicationDetail.DOSAGE),
            of(0, MedicationDetail.FREQUENCY),
            of(1, MedicationDetail.DOSAGE),
        )

    // The first medication is open: its dosage is checked, not the second medication's
    viewModel.onDosageChange("1 comprimé")

    assertEquals(
        setOf(
            ScannedField.PrescribedOn,
            of(0, MedicationDetail.FREQUENCY),
            of(1, MedicationDetail.DOSAGE),
        ),
        viewModel.state.toCheck,
    )
  }

  @Test
  fun startDateIsNotADetailToCheck() {
    val viewModel = reviewing(of(0, MedicationDetail.DURATION))

    viewModel.onStartDateChange(today.plusDays(1))

    assertEquals(setOf(of(0, MedicationDetail.DURATION)), viewModel.state.toCheck)
  }

  @Test
  fun removingAMedicationDropsItsDetailsToCheckAndMovesTheNextOnesUp() {
    val viewModel =
        reviewing(
            ScannedField.PrescribedBy,
            of(0, MedicationDetail.NAME),
            of(1, MedicationDetail.FREQUENCY),
        )
    viewModel.addMedication()

    viewModel.removeMedication(0)

    // Dexaméthasone is now first: its frequency is still to check, now at position 0
    assertEquals("Dexaméthasone 4 mg", viewModel.medications[0].name)
    assertEquals(
        setOf(ScannedField.PrescribedBy, of(0, MedicationDetail.FREQUENCY)),
        viewModel.state.toCheck,
    )
  }

  @Test
  fun removingALaterMedicationKeepsTheEarlierDetailsToCheck() {
    val viewModel = reviewing(of(0, MedicationDetail.NAME), of(1, MedicationDetail.DOSAGE))

    viewModel.removeMedication(1)

    assertEquals(setOf(of(0, MedicationDetail.NAME)), viewModel.state.toCheck)
  }

  @Test
  fun openingAnotherMedicationKeepsTheDetailsToCheck() {
    val viewModel = reviewing(of(0, MedicationDetail.NAME), of(1, MedicationDetail.DOSAGE))

    viewModel.openMedication(1)

    assertEquals(
        setOf(of(0, MedicationDetail.NAME), of(1, MedicationDetail.DOSAGE)),
        viewModel.state.toCheck,
    )
  }

  @Test
  fun aReviewWithDetailsToCheckIsSavedOnlyOnSave() = runTest {
    val viewModel = reviewing(ScannedField.PrescribedOn, of(1, MedicationDetail.DOSAGE))
    advanceUntilIdle()
    assertTrue(fakeMedications.prescriptionsOf(uid).isEmpty())

    viewModel.save()
    advanceUntilIdle()

    val saved = fakeMedications.prescriptionsOf(uid).single()
    assertEquals(
        listOf("Ondansetron 8 mg", "Dexaméthasone 4 mg"),
        saved.medications.map { it.name },
    )
  }

  @Test
  fun anAddedMedicationHasNothingToCheck() {
    val viewModel = reviewing(of(1, MedicationDetail.NAME))

    viewModel.addMedication()
    viewModel.onMedicationNameChange("Paracétamol 1 g")

    assertEquals(setOf(of(1, MedicationDetail.NAME)), viewModel.state.toCheck)
  }

  // ---------- Editing a saved prescription ----------

  private val saved =
      Prescription(
          id = "presc-1",
          prescribedBy = "Dr. Martin",
          prescribedOn = today.minusDays(3),
          medications =
              listOf(
                  Medication(
                      id = "med-1",
                      prescriptionId = "presc-1",
                      name = "Ondansetron 8 mg",
                      dosage = "1 tablet",
                      frequency = "Twice a day",
                      startDate = today.minusDays(3),
                      durationDays = 5,
                  ),
                  Medication(
                      id = "med-2",
                      prescriptionId = "presc-1",
                      name = "Dexamethasone",
                      startDate = today.minusDays(2),
                  ),
              ),
          createdAt = Instant.parse("2026-10-05T08:00:00Z"),
      )

  /** A form opened to edit the prescription [id], not loaded yet. */
  private fun editViewModel(id: String = saved.id) =
      PrescriptionFormViewModel(auth, medications, clock = clock, prescriptionId = id)

  /** A form opened to edit [saved], loaded. */
  private fun TestScope.loadedEditViewModel(): PrescriptionFormViewModel {
    fakeMedications.seed(uid, saved)
    return editViewModel().also { advanceUntilIdle() }
  }

  @Test
  fun newFormIsReadyAndNotEditing() = runTest {
    // Nothing is read for a new prescription, so a failing read changes nothing
    fakeMedications.readError = IOException("unavailable")

    val viewModel = viewModel()
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertFalse(viewModel.state.isEditing)
    assertEquals(PrescriptionFormLoad.READY, PrescriptionFormUiState(viewModel.state.draft).load)
  }

  @Test
  fun formGivenOnlyARepositoryReadsAndWritesThroughIt() = runTest {
    fakeMedications.seed(uid, saved)
    // No use case given: it must default to the same repository, never to the production one
    val viewModel =
        PrescriptionFormViewModel(
            auth,
            medicationRepository = fakeMedications,
            clock = clock,
            prescriptionId = saved.id,
        )
    advanceUntilIdle()
    viewModel.onPrescribedByChange("Dr. House")

    viewModel.save()
    advanceUntilIdle()

    assertTrue(viewModel.state.isSaved)
    assertEquals("Dr. House", fakeMedications.prescriptionsOf(uid).single().prescribedBy)
  }

  @Test
  fun editFormIsLoadingUntilThePrescriptionIsRead() = runTest {
    fakeMedications.seed(uid, saved)

    val viewModel = editViewModel()

    assertEquals(PrescriptionFormLoad.LOADING, viewModel.state.load)
    assertTrue(viewModel.state.isEditing)
    assertFalse(viewModel.state.canSave)

    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertTrue(viewModel.state.isEditing)
  }

  @Test
  fun nothingCanBeEditedOrSavedWhileLoading() = runTest {
    fakeMedications.seed(uid, saved)
    val viewModel = editViewModel()
    val loading = viewModel.state

    viewModel.editEverything()
    viewModel.save()

    assertEquals(loading, viewModel.state)

    advanceUntilIdle()
    assertEquals(saved.toDraft(), viewModel.state.draft)
    assertEquals(listOf(saved), fakeMedications.prescriptionsOf(uid))
  }

  @Test
  fun editFormIsPrefilledWithTheSavedPrescription() = runTest {
    val viewModel = loadedEditViewModel()

    assertEquals(saved.toDraft(), viewModel.state.draft)
    assertEquals(listOf("med-1", "med-2"), viewModel.medications.map { it.id })
    assertEquals(0, viewModel.state.openMedication)
    assertTrue(viewModel.state.canSave)
    assertTrue(viewModel.state.invalidMedications.isEmpty())
    assertFalse(viewModel.state.isSaved)
    assertNull(viewModel.state.error)
  }

  @Test
  fun savedStartDatesCountAsChosenAndSavedNamesAsTouched() = runTest {
    val viewModel = loadedEditViewModel()
    assertEquals(listOf(true, true), viewModel.state.startDateChosen)
    assertEquals(listOf(true, true), viewModel.state.nameTouched)

    viewModel.onPrescribedOnChange(today.minusDays(9))

    assertEquals(
        listOf(today.minusDays(3), today.minusDays(2)),
        viewModel.medications.map { it.startDate },
    )
  }

  @Test
  fun prescriptionThatDoesNotExistIsNotFound() = runTest {
    fakeMedications.seed(uid, saved)

    val viewModel = editViewModel("unknown")
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.NOT_FOUND, viewModel.state.load)
    assertTrue(viewModel.state.isEditing)
    assertFalse(viewModel.state.canSave)
    assertNull(viewModel.state.error)
  }

  @Test
  fun prescriptionOfAnotherUserIsNotFound() = runTest {
    fakeMedications.seed("patient-2", saved)

    val viewModel = editViewModel()
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.NOT_FOUND, viewModel.state.load)
  }

  @Test
  fun failureToLoadIsReportedAsALoadFailure() = runTest {
    fakeMedications.seed(uid, saved)
    fakeMedications.readError = IOException("unavailable")

    val viewModel = editViewModel()
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.FAILED, viewModel.state.load)
    assertTrue(viewModel.state.isEditing)
    assertFalse(viewModel.state.canSave)
    // Not a failed save
    assertNull(viewModel.state.error)
  }

  @Test
  fun nothingIsLoadedWhenNobodyIsSignedIn() = runTest {
    fakeMedications.seed(uid, saved)
    auth.signOut()

    val viewModel = editViewModel()
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.NOT_SIGNED_IN, viewModel.state.load)
    assertFalse(viewModel.state.canSave)
  }

  @Test
  fun nothingCanBeEditedOrSavedWhenThePrescriptionIsNotLoaded() = runTest {
    val viewModel = editViewModel("unknown")
    advanceUntilIdle()
    val notFound = viewModel.state

    viewModel.editEverything()
    viewModel.save()
    advanceUntilIdle()

    assertEquals(notFound, viewModel.state)
    assertTrue(fakeMedications.prescriptionsOf(uid).isEmpty())
  }

  @Test
  fun editFormValidatesLikeANewOne() = runTest {
    val viewModel = loadedEditViewModel()
    val tooLong = "a".repeat(200)

    // Text is cut to the same limits
    viewModel.onPrescribedByChange(tooLong)
    viewModel.onMedicationNameChange(tooLong)
    assertEquals(Prescription.MAX_PRESCRIBED_BY_LENGTH, viewModel.state.draft.prescribedBy.length)
    assertEquals(Medication.MAX_TEXT_LENGTH, viewModel.medication.name.length)
    assertTrue(viewModel.state.canSave)

    // A name is required
    viewModel.onMedicationNameChange("  ")
    assertFalse(viewModel.state.canSave)
    assertEquals(setOf(0), viewModel.state.invalidMedications)
    viewModel.onMedicationNameChange("Ondansetron")

    // Same duration limits
    viewModel.onDurationChange("0")
    assertFalse(viewModel.state.canSave)
    viewModel.onDurationChange((Medication.MAX_DURATION_DAYS + 1).toString())
    assertFalse(viewModel.state.canSave)
    viewModel.onDurationChange(Medication.MAX_DURATION_DAYS.toString())
    assertTrue(viewModel.state.canSave)

    // An added medication needs a name, and at least one medication stays
    viewModel.addMedication()
    assertEquals(setOf(2), viewModel.state.invalidMedications)
    assertFalse(viewModel.state.canSave)
    viewModel.removeMedication(2)
    viewModel.removeMedication(1)
    viewModel.removeMedication(0)
    assertEquals(1, viewModel.medications.size)
    assertFalse(viewModel.state.canRemoveMedication)
  }

  @Test
  fun editFormWithAnInvalidDraftSavesNothing() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onMedicationNameChange("")

    viewModel.save()
    advanceUntilIdle()

    assertEquals(listOf(saved), fakeMedications.prescriptionsOf(uid))
    assertFalse(viewModel.state.isSaved)
  }

  @Test
  fun saveUpdatesThePrescriptionInsteadOfAddingOne() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange(" Dr. House ")
    viewModel.onPrescribedOnChange(today.minusDays(1))
    viewModel.onDosageChange("2 tablets")

    viewModel.save()
    advanceUntilIdle()

    val expected =
        saved.copy(
            prescribedBy = "Dr. House",
            prescribedOn = today.minusDays(1),
            medications =
                listOf(saved.medications[0].copy(dosage = "2 tablets"), saved.medications[1]),
        )
    assertEquals(listOf(expected), fakeMedications.prescriptionsOf(uid))
    assertTrue(viewModel.state.isSaved)
    assertFalse(viewModel.state.isSaving)
    assertNull(viewModel.state.error)
  }

  @Test
  fun saveKeepsTheSavedMedicationsAddsTheNewOnesAndRemovesTheOthers() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.removeMedication(0)
    viewModel.addMedication()
    viewModel.onMedicationNameChange("Paracetamol")

    viewModel.save()
    advanceUntilIdle()

    val updated = fakeMedications.prescriptionsOf(uid).single()
    assertEquals(saved.id, updated.id)
    assertEquals(listOf("Dexamethasone", "Paracetamol"), updated.medications.map { it.name })
    assertEquals(saved.medications[1], updated.medications[0])
    assertFalse(updated.medications[1].id in listOf("med-1", "med-2", saved.id))
    assertEquals(saved.id, updated.medications[1].prescriptionId)
  }

  @Test
  fun failedUpdateShowsAnErrorAndCanBeTriedAgain() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange("Dr. House")
    fakeMedications.writeError = IOException("disk full")

    viewModel.save()
    advanceUntilIdle()

    assertEquals(PrescriptionFormError.SAVE_FAILED, viewModel.state.error)
    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertFalse(viewModel.state.isSaving)
    assertFalse(viewModel.state.isSaved)
    assertEquals("Dr. House", viewModel.state.draft.prescribedBy)
    assertEquals(listOf(saved), fakeMedications.prescriptionsOf(uid))

    fakeMedications.writeError = null
    viewModel.save()
    advanceUntilIdle()

    assertTrue(viewModel.state.isSaved)
    assertNull(viewModel.state.error)
    assertEquals(
        listOf(saved.copy(prescribedBy = "Dr. House")),
        fakeMedications.prescriptionsOf(uid),
    )
  }

  @Test
  fun updateWhenNobodyIsSignedInAnymoreShowsAnError() = runTest {
    val viewModel = loadedEditViewModel()
    auth.signOut()

    viewModel.save()
    advanceUntilIdle()

    assertEquals(PrescriptionFormError.NOT_SIGNED_IN, viewModel.state.error)
    assertFalse(viewModel.state.isSaved)
    assertEquals(listOf(saved), fakeMedications.prescriptionsOf(uid))
  }

  @Test
  fun prescriptionDeletedBeforeSavingIsNotSavedAndNoLongerFound() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange("Dr. House")
    // Deleted from the detail screen of another device while the form is open
    fakeMedications.deletePrescription(uid, saved.id)

    viewModel.save()
    advanceUntilIdle()

    assertFalse(viewModel.state.isSaved)
    assertFalse(viewModel.state.isSaving)
    assertEquals(PrescriptionFormLoad.NOT_FOUND, viewModel.state.load)
    assertFalse(viewModel.state.canSave)
    assertNull(viewModel.state.error)
    assertTrue(fakeMedications.prescriptionsOf(uid).isEmpty())
  }

  @Test
  fun failureToCheckThePrescriptionBeforeSavingIsAFailedSave() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange("Dr. House")
    fakeMedications.readError = IOException("unavailable")

    viewModel.save()
    advanceUntilIdle()

    assertEquals(PrescriptionFormError.SAVE_FAILED, viewModel.state.error)
    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertFalse(viewModel.state.isSaved)
    assertEquals(listOf(saved), fakeMedications.prescriptionsOf(uid))
  }

  // ---------- Following the saved prescription ----------

  @Test
  fun prescriptionThatArrivesLaterFillsTheFormByItself() = runTest {
    // Offline on a device that has never loaded it: not found until the connection comes back
    val viewModel = editViewModel()
    advanceUntilIdle()
    assertEquals(PrescriptionFormLoad.NOT_FOUND, viewModel.state.load)

    fakeMedications.seed(uid, saved)
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertTrue(viewModel.state.isEditing)
    assertEquals(saved.toDraft(), viewModel.state.draft)
    assertTrue(viewModel.state.canSave)
  }

  @Test
  fun prescriptionDeletedWhileCorrectingIsNoLongerFoundRightAway() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange("Dr. House")

    // Deleted from another device while the form is open
    fakeMedications.deletePrescription(uid, saved.id)
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.NOT_FOUND, viewModel.state.load)
    assertFalse(viewModel.state.canSave)
    assertFalse(viewModel.state.isSaved)
    assertNull(viewModel.state.error)
  }

  @Test
  fun changeMadeElsewhereRefreshesAFormTheUserHasNotTouched() = runTest {
    val viewModel = loadedEditViewModel()
    val changed = saved.copy(prescribedBy = "Dr. Leroy", medications = saved.medications.take(1))

    fakeMedications.updatePrescription(uid, changed)
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertEquals(changed.toDraft(), viewModel.state.draft)
    assertEquals(listOf(true), viewModel.state.nameTouched)
  }

  @Test
  fun changeMadeElsewhereDoesNotReplaceWhatTheUserIsCorrecting() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange("Dr. House")
    val corrected = viewModel.state

    fakeMedications.updatePrescription(uid, saved.copy(prescribedBy = "Dr. Leroy"))
    advanceUntilIdle()

    assertEquals(corrected, viewModel.state)
  }

  @Test
  fun lookingAtAnotherMedicationDoesNotCountAsCorrecting() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.openMedication(1)
    val changed = saved.copy(prescribedBy = "Dr. Leroy")

    fakeMedications.updatePrescription(uid, changed)
    advanceUntilIdle()

    assertEquals(changed.toDraft(), viewModel.state.draft)
  }

  @Test
  fun samePrescriptionEmittedAgainKeepsTheMedicationTheUserOpened() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.openMedication(1)
    val opened = viewModel.state

    // Only the save time changes, e.g. once the server confirms the prescription
    fakeMedications.seed(uid, saved.copy(createdAt = saved.createdAt!!.plusSeconds(60)))
    advanceUntilIdle()

    assertEquals(opened, viewModel.state)
  }

  @Test
  fun prescriptionThatComesBackAfterADeletionStartsFromWhatIsSaved() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange("Dr. House")
    fakeMedications.deletePrescription(uid, saved.id)
    advanceUntilIdle()
    val restored = saved.copy(prescribedBy = "Dr. Leroy")

    fakeMedications.seed(uid, restored)
    advanceUntilIdle()

    // What was typed before the deletion is gone
    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertEquals(restored.toDraft(), viewModel.state.draft)

    // The user hasn't changed the restored prescription: a change made elsewhere shows again
    val changedAgain = restored.copy(prescribedBy = "Dr. Grey")
    fakeMedications.updatePrescription(uid, changedAgain)
    advanceUntilIdle()
    assertEquals(changedAgain.toDraft(), viewModel.state.draft)
  }

  @Test
  fun savedFormKeepsWhatWasSavedWhenItsOwnSaveIsEmitted() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange("Dr. House")

    viewModel.save()
    advanceUntilIdle()

    assertTrue(viewModel.state.isSaved)
    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertEquals("Dr. House", viewModel.state.draft.prescribedBy)
  }

  @Test
  fun savedFormIgnoresWhatHappensToThePrescriptionAfterwards() = runTest {
    // Untouched, so only the guard on a saved form keeps the next values out
    val viewModel = loadedEditViewModel()
    viewModel.save()
    advanceUntilIdle()
    val done = viewModel.state
    assertTrue(done.isSaved)

    fakeMedications.updatePrescription(uid, saved.copy(prescribedBy = "Dr. Leroy"))
    advanceUntilIdle()
    fakeMedications.deletePrescription(uid, saved.id)
    advanceUntilIdle()

    assertEquals(done, viewModel.state)
  }

  @Test
  fun prescriptionDeletedWhileSavingIsNotReportedAsSaved() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange("Dr. House")
    val gate = CompletableDeferred<Unit>()
    medications.gate = gate
    viewModel.save()
    // The use case has checked that the prescription exists and is about to write
    advanceUntilIdle()

    fakeMedications.deletePrescription(uid, saved.id)
    advanceUntilIdle()
    gate.complete(Unit)
    advanceUntilIdle()

    assertFalse(viewModel.state.isSaved)
    assertFalse(viewModel.state.isSaving)
    assertEquals(PrescriptionFormLoad.NOT_FOUND, viewModel.state.load)
    assertTrue(fakeMedications.prescriptionsOf(uid).isEmpty())
  }

  @Test
  fun changeMadeElsewhereWhileSavingKeepsShowingWhatIsSaved() = runTest {
    // Untouched, so without the guard on saving the change would refresh the form
    val viewModel = loadedEditViewModel()
    val gate = CompletableDeferred<Unit>()
    medications.gate = gate
    viewModel.save()
    advanceUntilIdle()
    val saving = viewModel.state
    assertTrue(saving.isSaving)

    fakeMedications.updatePrescription(uid, saved.copy(prescribedBy = "Dr. Leroy"))
    advanceUntilIdle()

    assertEquals(saving, viewModel.state)

    gate.complete(Unit)
    advanceUntilIdle()

    // The last save wins: this one reached the repository after the other device's
    assertTrue(viewModel.state.isSaved)
    assertEquals("Dr. Martin", fakeMedications.prescriptionsOf(uid).single().prescribedBy)
  }

  @Test
  fun correctionCannotBeChangedWhileItIsSaving() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange("Dr. House")
    val gate = CompletableDeferred<Unit>()
    medications.gate = gate
    viewModel.save()
    advanceUntilIdle()
    val saving = viewModel.state

    viewModel.editEverything()

    assertEquals(saving, viewModel.state)
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals("Dr. House", fakeMedications.prescriptionsOf(uid).single().prescribedBy)
  }

  @Test
  fun changeMadeElsewhereKeepsAFailedSaveToShow() = runTest {
    val viewModel = loadedEditViewModel()
    fakeMedications.writeError = IOException("disk full")
    viewModel.save()
    advanceUntilIdle()
    assertEquals(PrescriptionFormError.SAVE_FAILED, viewModel.state.error)
    fakeMedications.writeError = null
    val changed = saved.copy(prescribedBy = "Dr. Leroy")

    fakeMedications.seed(uid, changed)
    advanceUntilIdle()

    assertEquals(changed.toDraft(), viewModel.state.draft)
    assertEquals(PrescriptionFormError.SAVE_FAILED, viewModel.state.error)
  }

  @Test
  fun correctionUndoneByTheUserFollowsChangesMadeElsewhereAgain() = runTest {
    val viewModel = loadedEditViewModel()
    // Typed then erased: the form shows what is saved again, so there is nothing to lose
    viewModel.onPrescribedByChange("Dr. MartinX")
    viewModel.onPrescribedByChange("Dr. Martin")
    val changed = saved.copy(prescribedBy = "Dr. Leroy")

    fakeMedications.updatePrescription(uid, changed)
    advanceUntilIdle()

    assertEquals(changed.toDraft(), viewModel.state.draft)
  }

  @Test
  fun readFailureWhileCorrectingIsReportedAsALoadFailure() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange("Dr. House")

    fakeMedications.failRunningReads(IOException("permission lost"))
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.FAILED, viewModel.state.load)
    assertFalse(viewModel.state.canSave)
  }

  // ---------- Loading again ----------

  @Test
  fun loadAfterAFailureReadsThePrescriptionAgain() = runTest {
    fakeMedications.seed(uid, saved)
    fakeMedications.readError = IOException("unavailable")
    val viewModel = editViewModel()
    advanceUntilIdle()
    fakeMedications.readError = null

    viewModel.load()

    assertEquals(PrescriptionFormLoad.LOADING, viewModel.state.load)

    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertTrue(viewModel.state.isEditing)
    assertEquals(saved.toDraft(), viewModel.state.draft)
    assertTrue(viewModel.state.canSave)
  }

  @Test
  fun loadAfterAFailureCanFailAgain() = runTest {
    fakeMedications.readError = IOException("unavailable")
    val viewModel = editViewModel()
    advanceUntilIdle()

    viewModel.load()
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.FAILED, viewModel.state.load)
  }

  @Test
  fun loadAfterAFailureWhileCorrectingStartsFromWhatIsSaved() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange("Dr. House")
    fakeMedications.failRunningReads(IOException("permission lost"))
    advanceUntilIdle()

    viewModel.load()
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertEquals(saved.toDraft(), viewModel.state.draft)
  }

  @Test
  fun loadAfterNotFoundFindsThePrescriptionOnceItExists() = runTest {
    val viewModel = editViewModel()
    advanceUntilIdle()
    assertEquals(PrescriptionFormLoad.NOT_FOUND, viewModel.state.load)
    fakeMedications.seed(uid, saved)

    viewModel.load()
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertEquals(saved.toDraft(), viewModel.state.draft)
  }

  @Test
  fun loadAfterSigningInAgainReadsThePrescription() = runTest {
    fakeMedications.seed(uid, saved)
    auth.signOut()
    val viewModel = editViewModel()
    advanceUntilIdle()
    auth.signInWithGoogle("token")

    viewModel.load()
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertEquals(saved.toDraft(), viewModel.state.draft)
  }

  @Test
  fun loadWhileLoadingReadsThePrescriptionOnce() = runTest {
    fakeMedications.seed(uid, saved)
    val viewModel = editViewModel()

    viewModel.load()
    viewModel.load()
    advanceUntilIdle()

    assertEquals(PrescriptionFormLoad.READY, viewModel.state.load)
    assertEquals(saved.toDraft(), viewModel.state.draft)
    assertEquals(1, fakeMedications.prescriptionObservers)
  }

  @Test
  fun loadKeepsWhatTheUserIsCorrecting() = runTest {
    val viewModel = loadedEditViewModel()
    viewModel.onPrescribedByChange("Dr. House")
    val corrected = viewModel.state

    viewModel.load()
    advanceUntilIdle()

    assertEquals(corrected, viewModel.state)
  }

  @Test
  fun loadDoesNothingForANewPrescription() = runTest {
    fakeMedications.readError = IOException("unavailable")
    val viewModel = viewModelWith("Ondansetron")
    val typed = viewModel.state

    viewModel.load()
    advanceUntilIdle()

    assertEquals(typed, viewModel.state)
    assertFalse(viewModel.state.isEditing)
  }
}
