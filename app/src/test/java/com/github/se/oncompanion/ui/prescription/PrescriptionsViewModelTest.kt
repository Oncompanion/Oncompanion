// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.carecircle.FollowedPatient
import com.github.se.oncompanion.domain.carecircle.followedPatientAccess
import com.github.se.oncompanion.domain.medication.ManageMedicationSchedule
import com.github.se.oncompanion.domain.medication.toDraft
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.medication.SeenPrescriptions
import com.github.se.oncompanion.model.medication.SeenPrescriptionsInMemory
import com.github.se.oncompanion.ui.VirtualClock
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric provides android.util.Log, used when a read fails
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PrescriptionsViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val uid = "alice"
  private val repository = FakeMedicationRepository()

  /** The time the ViewModel sees; starts on Monday 12 October 2026, 12:00 in UTC. */
  private var current: Clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC)

  /** Follows [current], so a test can move the time after creating the ViewModel. */
  private val clock =
      object : Clock() {
        override fun getZone(): ZoneId = current.zone

        override fun withZone(zone: ZoneId): Clock = current.withZone(zone)

        override fun instant(): Instant = current.instant()
      }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun signedIn(uid: String = this.uid) =
      FakeAuthRepository(onSignIn = { AuthUser(uid = uid) }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  private fun viewModel(auth: AuthRepository = signedIn()) =
      PrescriptionsViewModel(auth, repository, clock)

  /** A valid prescription [id] of [prescribedOn] with one medication per name. */
  private fun prescription(id: String, prescribedOn: String, vararg names: String) =
      LocalDate.parse(prescribedOn).let { date ->
        Prescription(
            id = id,
            prescribedOn = date,
            medications =
                names.mapIndexed { index, name ->
                  Medication(id = "$id-$index", prescriptionId = id, name = name, startDate = date)
                },
        )
      }

  private val PrescriptionsViewModel.ids
    get() = uiState.value.prescriptions.map { it.id }

  private val PrescriptionsViewModel.thisMonthIds
    get() = uiState.value.thisMonth.map { it.id }

  private val PrescriptionsViewModel.earlierIds
    get() = uiState.value.earlier.map { it.id }

  @Test
  fun startsLoading_untilThePrescriptionsArrive() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("p1", "2026-09-29", "Ondansetron 8 mg"))
        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasError)
        assertEquals(emptyList<Prescription>(), viewModel.uiState.value.prescriptions)

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf("p1"), viewModel.ids)
      }

  @Test
  fun noPrescriptions_isLoadedWithoutError() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(
            PrescriptionsUiState(
                today = LocalDate.of(2026, 10, 12),
                isLoading = false,
                canAdd = true,
            ),
            viewModel.uiState.value,
        )
        assertEquals(emptyList<Prescription>(), viewModel.uiState.value.thisMonth)
        assertEquals(emptyList<Prescription>(), viewModel.uiState.value.earlier)
      }

  @Test
  fun prescriptions_areTheSignedInUsersOnes_mostRecentlyPrescribedFirst() =
      runTest(dispatcher) {
        val september = prescription("sep", "2026-09-29", "Ondansetron 8 mg", "Dexamethasone 4 mg")
        val october = prescription("oct", "2026-10-06", "Paracetamol 500 mg")
        val august = prescription("aug", "2026-08-12", "Metoclopramide 10 mg")
        listOf(september, october, august).forEach { repository.seed(uid, it) }
        repository.seed("bob", prescription("bob", "2026-10-07", "Ibuprofen 400 mg"))

        val viewModel = viewModel()
        advanceUntilIdle()

        // With their medications, in the order they were saved
        assertEquals(listOf(october, september, august), viewModel.uiState.value.prescriptions)
      }

  @Test
  fun readError_showsTheError() =
      runTest(dispatcher) {
        repository.readError = IllegalStateException("no cache")

        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasError)
        assertFalse(viewModel.uiState.value.isLoading)
      }

  @Test
  fun signedOut_showsAnError_withoutReading() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("p1", "2026-09-29", "Ondansetron 8 mg"))

        val viewModel = viewModel(FakeAuthRepository())
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasError)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(emptyList<String>(), viewModel.ids)
      }

  @Test
  fun retryAfterError_loadsThePrescriptions() =
      runTest(dispatcher) {
        repository.readError = IllegalStateException("no cache")
        repository.seed(uid, prescription("p1", "2026-09-29", "Ondansetron 8 mg"))
        val viewModel = viewModel()
        advanceUntilIdle()

        repository.readError = null
        viewModel.load()

        // Loading again, without the old error
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasError)

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasError)
        assertEquals(listOf("p1"), viewModel.ids)
      }

  @Test
  fun savedPrescription_appearsWithoutReloading() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("old", "2026-09-29", "Ondansetron 8 mg"))
        val viewModel = viewModel()
        advanceUntilIdle()

        repository.addPrescription(uid, prescription("new", "2026-10-06", "Paracetamol 500 mg"))
        advanceUntilIdle()

        assertEquals(listOf("new", "old"), viewModel.ids)
      }

  @Test
  fun deletedPrescription_disappearsWithoutReloading() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("p1", "2026-09-29", "Ondansetron 8 mg"))
        val viewModel = viewModel()
        advanceUntilIdle()

        repository.deletePrescription(uid, "p1")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.ids)
        assertFalse(viewModel.uiState.value.hasError)
      }

  @Test
  fun reloading_keepsFollowingTheChanges() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.load()
        advanceUntilIdle()

        repository.addPrescription(uid, prescription("p1", "2026-09-29", "Ondansetron 8 mg"))
        advanceUntilIdle()

        assertEquals(listOf("p1"), viewModel.ids)
        assertFalse(viewModel.uiState.value.hasError)
      }

  // ----- This month / Earlier -----

  @Test
  fun thisMonth_startsOnTheFirstDayOfTheCurrentMonth() =
      runTest(dispatcher) {
        listOf(
                prescription("today", "2026-10-12", "A"),
                prescription("first", "2026-10-01", "B"),
                prescription("dayBefore", "2026-09-30", "C"),
                prescription("lastYear", "2025-10-20", "D"),
            )
            .forEach { repository.seed(uid, it) }

        val viewModel = viewModel()
        advanceUntilIdle()

        // Both keep the order of the list, most recently prescribed first
        assertEquals(listOf("today", "first"), viewModel.thisMonthIds)
        assertEquals(listOf("dayBefore", "lastYear"), viewModel.earlierIds)
      }

  @Test
  fun prescriptionDatedLaterThisMonthOrAfter_countsAsThisMonth() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("endOfMonth", "2026-10-31", "A"))
        repository.seed(uid, prescription("nextMonth", "2026-11-02", "B"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("nextMonth", "endOfMonth"), viewModel.thisMonthIds)
        assertEquals(emptyList<String>(), viewModel.earlierIds)
      }

  @Test
  fun nothingThisMonth_leavesThatGroupEmpty() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("old", "2026-08-20", "A"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.thisMonthIds)
        assertEquals(listOf("old"), viewModel.earlierIds)
      }

  @Test
  fun nothingEarlier_leavesThatGroupEmpty() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("recent", "2026-10-06", "A"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("recent"), viewModel.thisMonthIds)
        assertEquals(emptyList<String>(), viewModel.earlierIds)
      }

  @Test
  fun today_isCountedInTheClocksTimeZone() =
      runTest(dispatcher) {
        // 23:30 UTC on 30 September is already 1 October in Zurich
        current = Clock.fixed(Instant.parse("2026-09-30T23:30:00Z"), ZoneId.of("Europe/Zurich"))
        repository.seed(uid, prescription("september", "2026-09-29", "A"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 10, 1), viewModel.uiState.value.today)
        assertEquals(listOf("september"), viewModel.earlierIds)
      }

  @Test
  fun followToday_refreshesTheDateRightAway() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        // The date changed while the list was in the background
        current = Clock.fixed(Instant.parse("2026-11-01T00:05:00Z"), ZoneOffset.UTC)
        val following = viewModel.followToday()
        runCurrent()

        assertEquals(LocalDate.of(2026, 11, 1), viewModel.uiState.value.today)
        following.cancel()
      }

  @Test
  fun followToday_movesLastMonthToEarlierAtMidnight() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("october", "2026-10-06", "A"))
        val clock =
            VirtualClock(testScheduler, Instant.parse("2026-10-31T23:59:00Z"), ZoneOffset.UTC)
        val viewModel = PrescriptionsViewModel(signedIn(), repository, clock)
        advanceUntilIdle()
        val following = viewModel.followToday()
        runCurrent()
        assertEquals(listOf("october"), viewModel.thisMonthIds)

        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(LocalDate.of(2026, 11, 1), viewModel.uiState.value.today)
        assertEquals(emptyList<String>(), viewModel.thisMonthIds)
        assertEquals(listOf("october"), viewModel.earlierIds)
        following.cancel()
      }

  @Test
  fun defaultRepositories_canBeCreatedWithoutFirebase() =
      runTest(dispatcher) {
        // Like the app's NavHost does: without Firebase nobody is signed in
        val viewModel = PrescriptionsViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasError)
      }

  // ----- Caregiver mode (US-20) -----

  private fun caregiverViewModel(vararg shared: CarePermission) =
      PrescriptionsViewModel(
          signedIn("caregiver"),
          repository,
          clock,
          patientUid = "patient",
          resolveSection = followedPatientAccess(*shared),
      )

  @Test
  fun caregiver_prescriptionsShared_showsThePatients_withoutAdding() =
      runTest(dispatcher) {
        repository.seed("patient", prescription("p1", "2026-10-01", "Ondansetron 8 mg"))
        repository.seed("caregiver", prescription("own", "2026-10-01", "Paracetamol 1 g"))

        val viewModel = caregiverViewModel(CarePermission.PRESCRIPTIONS)
        advanceUntilIdle()

        assertEquals(listOf("p1"), viewModel.ids)
        assertFalse(viewModel.uiState.value.canAdd)
        assertFalse(viewModel.uiState.value.isNotShared)
      }

  @Test
  fun caregiver_prescriptionsNotShared_isNotShared_withNone() =
      runTest(dispatcher) {
        repository.seed("patient", prescription("p1", "2026-10-01", "Ondansetron 8 mg"))

        val viewModel = caregiverViewModel(CarePermission.SYMPTOMS)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isNotShared)
        assertEquals(emptyList<String>(), viewModel.ids)
        assertFalse(viewModel.uiState.value.hasError)
        assertFalse(viewModel.uiState.value.canAdd)
      }

  private fun caregiverViewModel(patient: FollowedPatient) =
      PrescriptionsViewModel(
          patient.auth,
          repository,
          clock,
          patientUid = "patient",
          resolveSection = patient.sectionAccess,
      )

  @Test
  fun caregiver_neverGetsTheAddButton_whateverTheAccess() =
      runTest(dispatcher) {
        // Each way a caregiver can be without the patient's prescriptions
        val cases =
            listOf<Pair<String, (FollowedPatient) -> Unit>>(
                "not confirmed yet" to { it.isUnconfirmed() },
                "removed from the circle" to { it.removesTheCaregiver() },
                "prescriptions not shared" to { it.shares(CarePermission.SYMPTOMS) },
                "circle unreadable" to
                    {
                      it.circle.observeError = IllegalStateException("offline")
                    },
            )
        for ((case, setUp) in cases) {
          val viewModel =
              caregiverViewModel(FollowedPatient(CarePermission.PRESCRIPTIONS).also(setUp))
          advanceUntilIdle()

          assertFalse(case, viewModel.uiState.value.canAdd)
        }
      }

  @Test
  fun caregiver_noAccessThenNotShared_saysNotShared_insteadOfTheError() =
      runTest(dispatcher) {
        val patient = FollowedPatient().apply { removesTheCaregiver() }
        val viewModel = caregiverViewModel(patient)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasError)

        // The patient adds the caregiver back, without their prescriptions
        patient.shares(CarePermission.SYMPTOMS)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasError)
        assertTrue(viewModel.uiState.value.isNotShared)
      }

  @Test
  fun loadError_thePatientCanStillAdd() =
      runTest(dispatcher) {
        repository.readError = IllegalStateException("no cache")

        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasError)
        assertTrue(viewModel.uiState.value.canAdd)
      }

  @Test
  fun caregiver_allowedToAdd_getsTheAddButton_untilTheListFails() =
      runTest(dispatcher) {
        val canAdd = setOf(CarePermission.PRESCRIPTIONS)
        for (fails in listOf(false, true)) {
          repository.readError = if (fails) IllegalStateException("denied") else null
          val viewModel =
              PrescriptionsViewModel(
                  signedIn("caregiver"),
                  repository,
                  clock,
                  patientUid = "patient",
                  resolveSection =
                      followedPatientAccess(CarePermission.PRESCRIPTIONS, canAdd = canAdd),
              )
          advanceUntilIdle()

          // After a failed read, the access may have been taken away: no adding
          assertEquals("fails=$fails", !fails, viewModel.uiState.value.canAdd)
        }
      }

  // ----- A prescription a caregiver added (US-43) -----

  @Test
  fun addedByACaregiver_thePatientSeesTheNameTheyGave_theCaregiverTheStoredOne() =
      runTest(dispatcher) {
        val added =
            prescription("p1", "2026-10-01", "Ondansetron 8 mg")
                .copy(addedBy = "caregiver", addedByName = "Sam")
        val circle =
            FakeCareCircleRepository().apply {
              setMembers(uid, listOf(CareCircleMember(uid = "caregiver", firstName = "Samuel")))
            }
        repository.seed(uid, added)
        repository.seed("patient", added)
        val patient =
            PrescriptionsViewModel(signedIn(), repository, clock, careCircleRepository = circle)
        val caregiver =
            PrescriptionsViewModel(
                signedIn("caregiver"),
                repository,
                clock,
                patientUid = "patient",
                resolveSection = followedPatientAccess(CarePermission.PRESCRIPTIONS),
                careCircleRepository = circle,
            )
        advanceUntilIdle()

        assertEquals("Samuel", patient.uiState.value.prescriptions.single().addedByName)
        assertEquals("Sam", caregiver.uiState.value.prescriptions.single().addedByName)
      }

  @Test
  fun waitingForThePatient_isGroupedFirst_allForThePatient_onlyTheirOwnForACaregiver() =
      runTest(dispatcher) {
        // Sam's and Lea's prescriptions wait for the patient; Sam's older one was confirmed
        val sams =
            prescription("sams", "2026-10-02", "Ondansetron 8 mg")
                .copy(
                    addedBy = "caregiver",
                    addedByName = "Sam",
                    confirmation = PrescriptionConfirmation.PENDING,
                )
        val leas =
            prescription("leas", "2026-09-02", "Metoclopramide")
                .copy(
                    addedBy = "lea",
                    addedByName = "Lea",
                    confirmation = PrescriptionConfirmation.PENDING,
                )
        val confirmed =
            prescription("confirmed", "2026-10-01", "Paracetamol")
                .copy(addedBy = "caregiver", addedByName = "Sam")
        for (owner in listOf(uid, "patient")) {
          listOf(sams, leas, confirmed).forEach { repository.seed(owner, it) }
        }
        val patient = viewModel()
        val caregiver =
            PrescriptionsViewModel(
                signedIn("caregiver"),
                repository,
                clock,
                patientUid = "patient",
                resolveSection = followedPatientAccess(CarePermission.PRESCRIPTIONS),
            )
        advanceUntilIdle()

        // Waiting first, then this month and earlier, without the waiting ones
        val cases =
            listOf(
                patient to Triple(listOf("sams", "leas"), listOf("confirmed"), emptyList()),
                // Their accepted one stays under "Added by you" until they close it (#396)
                caregiver to
                    Triple(listOf("sams", "confirmed"), emptyList<String>(), listOf("leas")),
            )
        for ((viewModel, expected) in cases) {
          val state = viewModel.uiState.value
          assertEquals(expected.first, state.waiting.map { it.id })
          assertEquals(expected.second, viewModel.thisMonthIds)
          assertEquals(expected.third, viewModel.earlierIds)
        }
      }

  @Test
  fun rejectedPrescription_isGone_forThePatientAndTheirCaregivers() =
      runTest(dispatcher) {
        val rejected =
            prescription("rejected", "2026-10-02", "Ondansetron 8 mg")
                .copy(
                    addedBy = "caregiver",
                    addedByName = "Sam",
                    confirmation = PrescriptionConfirmation.REJECTED,
                )
        val kept = prescription("kept", "2026-10-01", "Paracetamol")
        for (owner in listOf(uid, "patient")) {
          listOf(rejected, kept).forEach { repository.seed(owner, it) }
        }
        val patient = viewModel()
        val caregiver =
            PrescriptionsViewModel(
                signedIn("caregiver"),
                repository,
                clock,
                patientUid = "patient",
                resolveSection = followedPatientAccess(CarePermission.PRESCRIPTIONS),
            )
        advanceUntilIdle()

        for (viewModel in listOf(patient, caregiver)) assertEquals(listOf("kept"), viewModel.ids)
      }

  // ----- The caregiver sees what the patient decided (#396) -----

  /** A prescription "caregiver" (or [by]) added for "patient", in [confirmation]. */
  private fun added(
      id: String,
      confirmation: PrescriptionConfirmation,
      by: String = "caregiver",
  ) =
      prescription(id, "2026-10-02", "Ondansetron 8 mg")
          .copy(addedBy = by, addedByName = "Sam", confirmation = confirmation)

  private fun caregiverList(
      seen: SeenPrescriptions = SeenPrescriptionsInMemory(),
      medications: MedicationRepository = repository,
  ) =
      PrescriptionsViewModel(
          signedIn("caregiver"),
          medications,
          clock,
          patientUid = "patient",
          resolveSection = followedPatientAccess(CarePermission.PRESCRIPTIONS),
          seenPrescriptions = seen,
      )

  @Test
  fun caregiver_seesTheirOwnWaitingAcceptedAndRejected_andClosesTheDecidedOnes() =
      runTest(dispatcher) {
        repository.seed("patient", added("waiting", PrescriptionConfirmation.PENDING))
        repository.seed("patient", added("accepted", PrescriptionConfirmation.CONFIRMED))
        // Another caregiver's, waiting and rejected: never under "Added by you"
        repository.seed("patient", added("leas", PrescriptionConfirmation.PENDING, by = "lea"))
        for ((id, by) in listOf("rejected" to "caregiver", "leasRejected" to "lea")) {
          repository.rejectPrescription("patient", added(id, PrescriptionConfirmation.PENDING, by))
        }
        val viewModel = caregiverList()
        advanceUntilIdle()
        fun waiting() = viewModel.uiState.value.waiting.map { it.id to it.confirmation }
        assertEquals(
            setOf(
                "waiting" to PrescriptionConfirmation.PENDING,
                "accepted" to PrescriptionConfirmation.CONFIRMED,
                "rejected" to PrescriptionConfirmation.REJECTED,
            ),
            waiting().toSet(),
        )
        assertEquals(listOf("leas"), viewModel.thisMonthIds)

        // Closing: nothing for one still waiting, the accepted one joins the others, the rejected
        // one is deleted
        for (id in listOf("waiting", "accepted", "rejected")) viewModel.dismiss(id)
        advanceUntilIdle()

        assertEquals(listOf("waiting" to PrescriptionConfirmation.PENDING), waiting())
        assertEquals(setOf("accepted", "leas"), viewModel.thisMonthIds.toSet())
        assertEquals(listOf("leasRejected"), repository.rejectedOf("patient").map { it.id })
      }

  @Test
  fun caregiver_closedAcceptance_isRemembered_andRejectedOnesThatCantBeRead_leaveTheList() =
      runTest(dispatcher) {
        repository.seed("patient", added("accepted", PrescriptionConfirmation.CONFIRMED))
        val seen = SeenPrescriptionsInMemory()
        // Closed for another patient's prescription with the same ID: not this one
        seen.markSeen("caregiver", "anotherPatient", "accepted")
        val before = caregiverList(seen).also { advanceUntilIdle() }
        assertEquals(listOf("accepted"), before.uiState.value.waiting.map { it.id })
        before.dismiss("accepted")
        // The rejected ones can't be read: the list still shows
        val unreadable =
            object : MedicationRepository by repository {
              override fun observeRejectedPrescriptions(uid: String, addedBy: String) =
                  flow<List<Prescription>> { throw IllegalStateException("denied") }
            }

        val reopened = caregiverList(seen, unreadable)
        advanceUntilIdle()

        assertFalse(reopened.uiState.value.hasError)
        assertTrue(reopened.uiState.value.waiting.isEmpty())
        assertEquals(listOf("accepted"), reopened.thisMonthIds)
      }

  @Test
  fun closing_doesNothing_forThePatient_anUnknownOne_orWhenDeletingFails() =
      runTest(dispatcher) {
        repository.rejectPrescription(
            "patient",
            added("rejected", PrescriptionConfirmation.PENDING),
        )
        repository.seed(uid, added("own", PrescriptionConfirmation.CONFIRMED))
        val patient = viewModel()
        val caregiver = caregiverList()
        advanceUntilIdle()

        // The patient closes nothing; the caregiver can't close what isn't theirs to close
        patient.dismiss("own")
        caregiver.dismiss("missing")
        // Deleting the rejected one fails on the device: it stays, and the list keeps working
        repository.writeError = IllegalStateException("disk full")
        caregiver.dismiss("rejected")
        advanceUntilIdle()

        assertEquals(listOf("own"), patient.ids)
        assertEquals(listOf("rejected"), repository.rejectedOf("patient").map { it.id })
        assertEquals(listOf("rejected"), caregiver.uiState.value.waiting.map { it.id })
        assertFalse(caregiver.uiState.value.hasError)
      }

  @Test
  fun caregiverSignedOut_seesNoRejectedOnesNorClosedOnes() =
      runTest(dispatcher) {
        repository.rejectPrescription(
            "patient",
            added("rejected", PrescriptionConfirmation.PENDING),
        )
        val viewModel =
            PrescriptionsViewModel(
                FakeAuthRepository(),
                repository,
                clock,
                patientUid = "patient",
                resolveSection = followedPatientAccess(CarePermission.PRESCRIPTIONS),
                seenPrescriptions = SeenPrescriptionsInMemory(),
            )
        advanceUntilIdle()

        viewModel.dismiss("rejected")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.waiting.isEmpty())
        assertEquals(listOf("rejected"), repository.rejectedOf("patient").map { it.id })
      }

  @Test
  fun addedByACaregiver_followsARename_andOnceTheyLeave_staysPendingWithTheStoredName() =
      runTest(dispatcher) {
        val sams =
            prescription("sams", "2026-10-01", "Ondansetron 8 mg")
                .copy(
                    addedBy = "caregiver",
                    addedByName = "Sam",
                    confirmation = PrescriptionConfirmation.PENDING,
                )
        repository.seed(uid, sams)
        val circle = FakeCareCircleRepository()
        fun named(firstName: String) =
            circle.setMembers(
                uid,
                listOf(CareCircleMember(uid = "caregiver", firstName = firstName)),
            )
        named("Samuel")
        val viewModel =
            PrescriptionsViewModel(signedIn(), repository, clock, careCircleRepository = circle)
        advanceUntilIdle()
        fun shown() = viewModel.uiState.value.waiting.single()

        // The patient renames them while the list is open, then removes them from the circle
        val steps =
            listOf({ named("Sammy") } to "Sammy", { circle.setMembers(uid, emptyList()) } to "Sam")
        assertEquals("Samuel", shown().addedByName)
        for ((change, name) in steps) {
          change()
          advanceUntilIdle()
          assertEquals(name, shown().addedByName)
        }

        // Still waiting for the patient, never reminded, and the patient can still confirm it
        assertEquals(PrescriptionConfirmation.PENDING, shown().confirmation)
        assertTrue(repository.observeMedications(uid).first().isEmpty())
        assertTrue(ManageMedicationSchedule(repository).update(uid, "sams", sams.toDraft()))
        val confirmed = repository.prescriptionsOf(uid).single()
        assertEquals(PrescriptionConfirmation.CONFIRMED, confirmed.confirmation)
        assertEquals("Sam", confirmed.addedByName)
      }

  @Test
  fun caregiver_rejectedOneArrivingBeforeTheWaitingOneLeaves_showsOnce() =
      runTest(dispatcher) {
        // The waiting copy is still there when its rejected copy arrives: two listeners, apart
        val waiting = added("sams", PrescriptionConfirmation.PENDING)
        repository.seed("patient", waiting)
        val bothAtOnce =
            object : MedicationRepository by repository {
              override fun observeRejectedPrescriptions(uid: String, addedBy: String) =
                  flowOf(listOf(waiting.copy(confirmation = PrescriptionConfirmation.REJECTED)))
            }
        val viewModel = caregiverList(medications = bothAtOnce)
        advanceUntilIdle()

        // Once, as rejected: the list's keys stay unique
        assertEquals(listOf("sams"), viewModel.ids)
        assertEquals(
            PrescriptionConfirmation.REJECTED,
            viewModel.uiState.value.waiting.single().confirmation,
        )
      }
}
