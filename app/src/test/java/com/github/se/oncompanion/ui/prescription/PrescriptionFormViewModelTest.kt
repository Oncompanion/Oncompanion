package com.github.se.oncompanion.ui.prescription

import com.github.se.oncompanion.domain.medication.ManageMedicationSchedule
import com.github.se.oncompanion.domain.medication.MedicationDraft
import com.github.se.oncompanion.domain.medication.PrescriptionDraft
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** Waits for [gate] (if set) before saving, to observe the form while it is saving. */
private class GatedMedicationRepository(private val delegate: FakeMedicationRepository) :
    MedicationRepository by delegate {
  var gate: CompletableDeferred<Unit>? = null

  override suspend fun addPrescription(uid: String, prescription: Prescription) {
    gate?.await()
    delegate.addPrescription(uid, prescription)
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
      PrescriptionFormViewModel(auth, ManageMedicationSchedule(medications), clock, initialDraft)

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
}
