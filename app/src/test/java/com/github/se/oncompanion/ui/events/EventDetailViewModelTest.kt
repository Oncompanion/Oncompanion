// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.events

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.carecircle.FollowedPatient
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRegistration
import com.github.se.oncompanion.model.event.EventRegistrationRepository
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.FakeEventRegistrationRepository
import com.github.se.oncompanion.model.event.FakeEventRepository
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

// Robolectric provides the main dispatcher used by viewModelScope
@RunWith(AndroidJUnit4::class)
class EventDetailViewModelTest {

  private val yoga =
      Event(
          id = "yoga",
          title = "Gentle yoga for patients",
          place = "Wellness center, Building B",
          startDateTime = LocalDateTime.of(2026, 10, 10, 10, 0),
          summary = "Summary",
          description = "Description",
      )

  private val rejectedWrites = RejectedWrites()
  private val registrations = FakeEventRegistrationRepository(rejectedWrites)

  private fun signedIn(uid: String = "alice") =
      FakeAuthRepository(onSignIn = { AuthUser(uid = uid) }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  /** A clock showing [time] in Zurich, the zone the test events' times are given in. */
  private fun clockAt(time: LocalDateTime): Clock {
    val zone = ZoneId.of("Europe/Zurich")
    return Clock.fixed(time.atZone(zone).toInstant(), zone)
  }

  /** A clock in Zurich that the tests can move forward. */
  private class TestClock(var time: LocalDateTime) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("Europe/Zurich")

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = time.atZone(zone).toInstant()
  }

  /** The day before [yoga] starts. */
  private val beforeYoga = LocalDateTime.of(2026, 10, 9, 10, 0)

  private fun viewModel(
      event: Event = yoga,
      now: LocalDateTime = beforeYoga,
      auth: AuthRepository = signedIn(),
      events: EventRepository = FakeEventRepository(listOf(event)),
      clock: Clock = clockAt(now),
  ) = EventDetailViewModel(event.id, events, registrations, auth, clock)

  /** Returns [event] from getEvent only once [release] is completed. */
  private class SlowRepository(private val event: Event) : EventRepository {
    val release = CompletableDeferred<Unit>()

    override fun getUpcomingEvents(): Flow<List<Event>> = flowOf(listOf(event))

    override fun observeEvents(from: Instant, until: Instant): Flow<List<Event>> =
        flowOf(emptyList())

    override suspend fun getEvent(id: String): Event? {
      release.await()
      return event.takeIf { it.id == id }
    }
  }

  /** Fails the first time getEvent is called, then returns [event]. */
  private class FailingOnceRepository(private val event: Event) : EventRepository {
    var calls = 0

    override fun getUpcomingEvents(): Flow<List<Event>> = flowOf(listOf(event))

    override fun observeEvents(from: Instant, until: Instant): Flow<List<Event>> =
        flowOf(emptyList())

    override suspend fun getEvent(id: String): Event? {
      if (calls++ == 0) throw IllegalStateException("no network")
      return event
    }
  }

  @Test
  fun existingEvent_isLoaded() {
    val state = viewModel().uiState.value

    assertEquals(yoga, state.event)
    assertFalse(state.isLoading)
    assertFalse(state.hasError)
  }

  @Test
  fun unknownEvent_isNotFound() {
    val state =
        viewModel(events = FakeEventRepository(listOf(yoga.copy(id = "other")))).uiState.value

    assertNull(state.event)
    assertFalse(state.isLoading)
  }

  @Test
  fun defaultRepository_readsFirestore() {
    // Firebase isn't initialized in unit tests: reaching Firestore fails, which a fake wouldn't
    val viewModel = EventDetailViewModel("gentle-yoga")
    ShadowLooper.idleMainLooper()

    assertTrue(viewModel.uiState.value.hasError)
  }

  @Test
  fun stateIsLoading_untilTheEventArrives() {
    val repository = SlowRepository(yoga)
    val viewModel = viewModel(events = repository)

    assertTrue(viewModel.uiState.value.isLoading)

    repository.release.complete(Unit)
    ShadowLooper.idleMainLooper()

    assertEquals(yoga, viewModel.uiState.value.event)
  }

  @Test
  fun failure_givesAnError_andReloadRecovers() {
    val viewModel = viewModel(events = FailingOnceRepository(yoga))

    assertTrue(viewModel.uiState.value.hasError)
    assertFalse(viewModel.uiState.value.isLoading)

    viewModel.loadEvent()

    assertFalse(viewModel.uiState.value.hasError)
    assertEquals(yoga, viewModel.uiState.value.event)
  }

  @Test
  fun upcomingEvent_notConfirmed_canBeConfirmed() {
    assertEquals(RegistrationStatus.CAN_CONFIRM, viewModel().uiState.value.registration)
  }

  @Test
  fun confirming_savesIt_andShowsItConfirmed() {
    val viewModel = viewModel()

    viewModel.confirmRegistration()
    ShadowLooper.idleMainLooper()

    assertEquals(setOf("yoga"), registrations.registeredEventIds("alice"))
    assertEquals(RegistrationStatus.CONFIRMED, viewModel.uiState.value.registration)
  }

  @Test
  fun confirmingRejectedLaterByTheServer_offersToConfirmAgainAndTellsTheUser() {
    registrations.serverRejectsWrites = true
    val viewModel = viewModel()

    viewModel.confirmRegistration()
    ShadowLooper.idleMainLooper()

    // Done without waiting for the server
    assertEquals(RegistrationStatus.CONFIRMED, viewModel.uiState.value.registration)
    assertEquals(0, rejectedWrites.unseen.value)

    registrations.rejectPendingWrites()
    ShadowLooper.idleMainLooper()

    assertEquals(emptySet<String>(), registrations.registeredEventIds("alice"))
    assertEquals(RegistrationStatus.CAN_CONFIRM, viewModel.uiState.value.registration)
    assertEquals(1, rejectedWrites.unseen.value)
  }

  @Test
  fun removing_deletesIt_andOffersToConfirmAgain() {
    registrations.confirm("alice", "yoga")
    val viewModel = viewModel()
    assertEquals(RegistrationStatus.CONFIRMED, viewModel.uiState.value.registration)

    viewModel.requestRemoval()
    viewModel.removeRegistration()
    ShadowLooper.idleMainLooper()

    assertTrue(registrations.registeredEventIds("alice").isEmpty())
    assertEquals(RegistrationStatus.CAN_CONFIRM, viewModel.uiState.value.registration)
    assertFalse(viewModel.uiState.value.isConfirmingRemoval)
  }

  @Test
  fun anotherPatientsConfirmation_isNotShown() {
    registrations.confirm("bob", "yoga")

    assertEquals(RegistrationStatus.CAN_CONFIRM, viewModel().uiState.value.registration)
  }

  @Test
  fun startedEvent_notConfirmed_isClosed_andCantBeConfirmed() {
    val viewModel = viewModel(now = yoga.startDateTime)
    assertEquals(RegistrationStatus.CLOSED, viewModel.uiState.value.registration)

    viewModel.confirmRegistration()
    ShadowLooper.idleMainLooper()

    assertTrue(registrations.registeredEventIds("alice").isEmpty())
    assertEquals(RegistrationStatus.CLOSED, viewModel.uiState.value.registration)
  }

  @Test
  fun confirmation_staysShown_until24HoursAfterTheEnd() {
    val end = LocalDateTime.of(2026, 10, 10, 11, 30)
    val event = yoga.copy(endDateTime = end)
    registrations.confirm("alice", "yoga")

    assertEquals(
        RegistrationStatus.CONFIRMED,
        viewModel(event, now = end.plusHours(24)).uiState.value.registration,
    )
    assertEquals(
        RegistrationStatus.CLOSED,
        viewModel(event, now = end.plusHours(24).plusMinutes(1)).uiState.value.registration,
    )
  }

  @Test
  fun confirmation_withoutEnd_isShownUntil24HoursAfterTheStart() {
    registrations.confirm("alice", "yoga")
    val start = yoga.startDateTime

    assertEquals(
        RegistrationStatus.CONFIRMED,
        viewModel(now = start.plusHours(24)).uiState.value.registration,
    )
    assertEquals(
        RegistrationStatus.CLOSED,
        viewModel(now = start.plusHours(24).plusMinutes(1)).uiState.value.registration,
    )
  }

  @Test
  fun confirmationDuringThe24Hours_canStillBeRemoved() {
    registrations.confirm("alice", "yoga")
    val viewModel = viewModel(now = yoga.startDateTime.plusHours(2))

    viewModel.requestRemoval()
    viewModel.removeRegistration()
    ShadowLooper.idleMainLooper()

    assertTrue(registrations.registeredEventIds("alice").isEmpty())
    // The event has started, so it can't be confirmed again
    assertEquals(RegistrationStatus.CLOSED, viewModel.uiState.value.registration)
  }

  @Test
  fun removing_withoutConfirmation_changesNothing() {
    registrations.confirm("alice", "other")
    val viewModel = viewModel()

    viewModel.requestRemoval()
    viewModel.removeRegistration()
    ShadowLooper.idleMainLooper()

    assertFalse(viewModel.uiState.value.isConfirmingRemoval)
    assertEquals(setOf("other"), registrations.registeredEventIds("alice"))
    assertEquals(RegistrationStatus.CAN_CONFIRM, viewModel.uiState.value.registration)
  }

  @Test
  fun registrationReadFailure_keepsTheEvent_withARegistrationError() {
    registrations.observeError = IllegalStateException("permission denied")

    val state = viewModel().uiState.value

    assertEquals(yoga, state.event)
    assertFalse(state.hasError)
    assertEquals(RegistrationStatus.ERROR, state.registration)
  }

  @Test
  fun registrationWriteFailure_givesARegistrationError_andSavesNothing() {
    val viewModel = viewModel()
    registrations.writeError = IllegalStateException("invalid")

    viewModel.confirmRegistration()

    assertTrue(registrations.registeredEventIds("alice").isEmpty())
    assertEquals(yoga, viewModel.uiState.value.event)
    assertEquals(RegistrationStatus.ERROR, viewModel.uiState.value.registration)
  }

  @Test
  fun signedOut_keepsTheEvent_withARegistrationError() {
    val state = viewModel(auth = FakeAuthRepository()).uiState.value

    assertEquals(yoga, state.event)
    assertEquals(RegistrationStatus.ERROR, state.registration)
  }

  @Test
  fun missingEvent_readsNoRegistration() {
    registrations.observeError = IllegalStateException("must not be observed")

    val state =
        viewModel(events = FakeEventRepository(listOf(yoga.copy(id = "other")))).uiState.value

    assertNull(state.event)
    assertEquals(RegistrationStatus.LOADING, state.registration)
  }

  @Test
  fun reload_afterAnError_alsoReadsTheRegistration() {
    registrations.confirm("alice", "yoga")
    val viewModel = viewModel(events = FailingOnceRepository(yoga))
    assertEquals(RegistrationStatus.LOADING, viewModel.uiState.value.registration)

    viewModel.loadEvent()

    assertEquals(RegistrationStatus.CONFIRMED, viewModel.uiState.value.registration)
  }

  /** A ViewModel showing [yoga] confirmed, with the removal confirmation open. */
  private fun confirmingRemoval(): EventDetailViewModel {
    registrations.confirm("alice", "yoga")
    return viewModel().apply { requestRemoval() }
  }

  @Test
  fun requestingRemoval_asksFirst_andRemovesNothing() {
    val viewModel = confirmingRemoval()

    assertTrue(viewModel.uiState.value.isConfirmingRemoval)
    assertEquals(setOf("yoga"), registrations.registeredEventIds("alice"))
    assertEquals(RegistrationStatus.CONFIRMED, viewModel.uiState.value.registration)
  }

  @Test
  fun cancellingRemoval_closesIt_andKeepsTheConfirmation() {
    val viewModel = confirmingRemoval()

    viewModel.cancelRemoval()
    ShadowLooper.idleMainLooper()

    assertFalse(viewModel.uiState.value.isConfirmingRemoval)
    assertEquals(setOf("yoga"), registrations.registeredEventIds("alice"))
    assertEquals(RegistrationStatus.CONFIRMED, viewModel.uiState.value.registration)
  }

  @Test
  fun removing_withoutAskingFirst_changesNothing() {
    registrations.confirm("alice", "yoga")
    val viewModel = viewModel()

    viewModel.removeRegistration()
    ShadowLooper.idleMainLooper()

    assertEquals(setOf("yoga"), registrations.registeredEventIds("alice"))
    assertEquals(RegistrationStatus.CONFIRMED, viewModel.uiState.value.registration)
  }

  @Test
  fun confirmationRemovedElsewhere_closesTheRemovalConfirmation() {
    val viewModel = confirmingRemoval()

    // e.g. removed on another device
    registrations.remove("alice", "yoga")
    ShadowLooper.idleMainLooper()

    assertFalse(viewModel.uiState.value.isConfirmingRemoval)
    assertEquals(RegistrationStatus.CAN_CONFIRM, viewModel.uiState.value.registration)
  }

  @Test
  fun removalFailure_givesARegistrationError_andKeepsTheConfirmation() {
    val viewModel = confirmingRemoval()
    registrations.writeError = IllegalStateException("invalid")

    viewModel.removeRegistration()

    assertEquals(setOf("yoga"), registrations.registeredEventIds("alice"))
    assertFalse(viewModel.uiState.value.isConfirmingRemoval)
    assertEquals(RegistrationStatus.ERROR, viewModel.uiState.value.registration)
  }

  @Test
  fun eventStartingWhileThePageIsOpen_cantBeConfirmed() {
    val clock = TestClock(yoga.startDateTime.minusMinutes(5))
    val viewModel = viewModel(clock = clock)
    assertEquals(RegistrationStatus.CAN_CONFIRM, viewModel.uiState.value.registration)

    // The patient comes back after the start, before the screen refreshes
    clock.time = yoga.startDateTime.plusMinutes(5)
    viewModel.confirmRegistration()
    ShadowLooper.idleMainLooper()

    assertTrue(registrations.registeredEventIds("alice").isEmpty())
    assertEquals(RegistrationStatus.CLOSED, viewModel.uiState.value.registration)
  }

  @Test
  fun refreshingAfterTheStart_closesTheRegistration() {
    val clock = TestClock(yoga.startDateTime.minusMinutes(5))
    val viewModel = viewModel(clock = clock)

    clock.time = yoga.startDateTime
    viewModel.refreshStatus()

    assertEquals(RegistrationStatus.CLOSED, viewModel.uiState.value.registration)
  }

  @Test
  fun refreshingMoreThanADayAfterTheEvent_hidesTheConfirmation() {
    registrations.confirm("alice", "yoga")
    val clock = TestClock(yoga.startDateTime.plusHours(2))
    val viewModel = viewModel(clock = clock)
    assertEquals(RegistrationStatus.CONFIRMED, viewModel.uiState.value.registration)

    clock.time = yoga.startDateTime.plusHours(24).plusMinutes(1)
    viewModel.refreshStatus()

    assertEquals(RegistrationStatus.CLOSED, viewModel.uiState.value.registration)
  }

  @Test
  fun refreshing_keepsARegistrationError() {
    val viewModel = viewModel()
    registrations.writeError = IllegalStateException("invalid")
    viewModel.confirmRegistration()
    assertEquals(RegistrationStatus.ERROR, viewModel.uiState.value.registration)

    viewModel.refreshStatus()

    assertEquals(RegistrationStatus.ERROR, viewModel.uiState.value.registration)
  }

  @Test
  fun refreshing_beforeTheRegistrationIsKnown_changesNothing() {
    val viewModel = viewModel(events = SlowRepository(yoga))

    viewModel.refreshStatus()

    assertEquals(RegistrationStatus.LOADING, viewModel.uiState.value.registration)
  }

  /** The event page of the caregiver of [patient], who follows "patient". */
  private fun caregiverViewModel(
      patient: FollowedPatient,
      now: LocalDateTime = beforeYoga,
      registrationRepository: EventRegistrationRepository = registrations,
      profiles: UserProfileRepository = patient.profiles,
  ) =
      EventDetailViewModel(
          yoga.id,
          FakeEventRepository(listOf(yoga)),
          registrationRepository,
          patient.auth,
          clockAt(now),
          patientUid = "patient",
          resolveSection = patient.sectionAccess,
          careCircleRepository = patient.circle,
          profileRepository = profiles,
      )

  /** [profiles], whose reads wait for [release], like a slow cache. */
  private class SlowProfiles(private val profiles: UserProfileRepository) :
      UserProfileRepository by profiles {
    val release = CompletableDeferred<Unit>()

    override fun observeProfile(uid: String): Flow<UserProfile?> = flow {
      release.await()
      emitAll(profiles.observeProfile(uid))
    }
  }

  /** [registrations], counting the confirmations saved. */
  private class CountingRegistrations(private val registrations: FakeEventRegistrationRepository) :
      EventRegistrationRepository by registrations {
    var confirms = 0

    override fun confirm(uid: String, eventId: String, by: EventRegistration) {
      confirms++
      registrations.confirm(uid, eventId, by)
    }
  }

  @Test
  fun caregiver_eventsShared_seesThePatientsConfirmation_notTheirOwn() {
    registrations.confirm("patient", "yoga")
    val confirmed = caregiverViewModel(FollowedPatient(CarePermission.EVENTS))
    assertEquals(RegistrationStatus.CONFIRMED, confirmed.uiState.value.registration)

    registrations.remove("patient", "yoga")
    registrations.confirm("caregiver", "yoga")
    ShadowLooper.idleMainLooper()
    assertEquals(RegistrationStatus.CAN_CONFIRM, confirmed.uiState.value.registration)
  }

  @Test
  fun caregiver_eventsNotShared_isToldSo_andReadsNoRegistration() {
    // A read would fail and show an error instead of nothing
    registrations.observeError = IllegalStateException("must not be read")

    val state = caregiverViewModel(FollowedPatient(CarePermission.PLANNING)).uiState.value

    assertEquals(yoga, state.event)
    assertEquals(RegistrationStatus.NOT_SHARED, state.registration)
  }

  @Test
  fun caregiver_accessChanges_hideTheConfirmationLive() {
    registrations.confirm("patient", "yoga")
    val patient = FollowedPatient(CarePermission.EVENTS)
    val viewModel = caregiverViewModel(patient)
    assertEquals(RegistrationStatus.CONFIRMED, viewModel.uiState.value.registration)

    // The patient stops sharing Events, then shares it again, then removes the caregiver
    val steps =
        listOf(
            { patient.shares(CarePermission.PLANNING) } to RegistrationStatus.NOT_SHARED,
            { patient.shares(CarePermission.EVENTS) } to RegistrationStatus.CONFIRMED,
            { patient.removesTheCaregiver() } to RegistrationStatus.HIDDEN,
        )
    for ((change, expected) in steps) {
      change()
      ShadowLooper.idleMainLooper()
      assertEquals(expected, viewModel.uiState.value.registration)
    }
  }

  @Test
  fun caregiver_readRefusedBeforeTheAccessChange_stillEndsNotShared() {
    // The patient stops sharing Events: the server refuses the open read before the access change
    // arrives
    registrations.observeError = IllegalStateException("permission denied")
    val patient = FollowedPatient(CarePermission.EVENTS)
    val viewModel = caregiverViewModel(patient)
    assertEquals(RegistrationStatus.ERROR, viewModel.uiState.value.registration)

    patient.shares(CarePermission.PLANNING)
    ShadowLooper.idleMainLooper()

    assertEquals(RegistrationStatus.NOT_SHARED, viewModel.uiState.value.registration)
  }

  @Test
  fun caregiver_cantConfirmNorRemoveThePatientsRegistration() {
    val patient = FollowedPatient(CarePermission.EVENTS)
    val viewModel = caregiverViewModel(patient)
    assertEquals(RegistrationStatus.CAN_CONFIRM, viewModel.uiState.value.registration)

    viewModel.confirmRegistration()
    assertTrue(registrations.registeredEventIds("patient").isEmpty())
    assertTrue(registrations.registeredEventIds("caregiver").isEmpty())

    registrations.confirm("patient", "yoga")
    ShadowLooper.idleMainLooper()
    viewModel.requestRemoval()
    viewModel.removeRegistration()
    assertFalse(viewModel.uiState.value.isConfirmingRemoval)
    assertEquals(setOf("yoga"), registrations.registeredEventIds("patient"))
    assertEquals(RegistrationStatus.CONFIRMED, viewModel.uiState.value.registration)
  }

  @Test
  fun caregiver_cantConfirm_untilThePatientAllowsIt() {
    val patient = FollowedPatient(CarePermission.EVENTS)
    val viewModel = caregiverViewModel(patient)
    assertFalse(viewModel.uiState.value.caregiverCanConfirm)

    patient.shares(CarePermission.EVENTS, canAdd = setOf(CarePermission.EVENTS))
    ShadowLooper.idleMainLooper()
    assertTrue(viewModel.uiState.value.caregiverCanConfirm)

    // Turning Events off takes the permission away with it
    patient.shares(CarePermission.PLANNING)
    ShadowLooper.idleMainLooper()
    assertFalse(viewModel.uiState.value.caregiverCanConfirm)
  }

  @Test
  fun caregiver_allowedToConfirm_confirmsOnce_signedWithTheirName_andCantRemoveIt() {
    val patient = FollowedPatient(CarePermission.EVENTS, canAdd = setOf(CarePermission.EVENTS))
    val counting = CountingRegistrations(registrations)
    val slowProfiles = SlowProfiles(patient.profiles)
    val viewModel =
        caregiverViewModel(patient, registrationRepository = counting, profiles = slowProfiles)

    // Tapped twice before the name is read: a second write would be refused, replacing the first
    viewModel.confirmRegistration()
    viewModel.confirmRegistration()
    slowProfiles.release.complete(Unit)
    ShadowLooper.idleMainLooper()
    viewModel.confirmRegistration()
    ShadowLooper.idleMainLooper()

    assertEquals(1, counting.confirms)
    assertEquals(
        EventRegistration("caregiver", "Sam"),
        registrations.registration("patient", "yoga"),
    )
    assertTrue(registrations.registeredEventIds("caregiver").isEmpty())
    val state = viewModel.uiState.value
    assertEquals(RegistrationStatus.CONFIRMED, state.registration)
    assertEquals("Sam", state.addedByName)

    viewModel.requestRemoval()
    assertFalse(viewModel.uiState.value.isConfirmingRemoval)
  }

  @Test
  fun caregiver_changesWhileTheirNameIsRead_areKept() {
    // What happens on the patient's side while the caregiver's name is still being read
    val cases =
        listOf<Pair<(FollowedPatient) -> Unit, EventRegistration?>>(
            // The patient stops letting them confirm: nothing is saved
            { p: FollowedPatient -> p.shares(CarePermission.EVENTS) } to null,
            // The patient confirms it themselves: their confirmation is kept, unsigned
            { _: FollowedPatient ->
              registrations.confirm("patient", "yoga")
            } to EventRegistration(),
        )
    for ((change, expected) in cases) {
      registrations.remove("patient", "yoga")
      val patient = FollowedPatient(CarePermission.EVENTS, canAdd = setOf(CarePermission.EVENTS))
      val counting = CountingRegistrations(registrations)
      val slowProfiles = SlowProfiles(patient.profiles)
      val viewModel =
          caregiverViewModel(patient, registrationRepository = counting, profiles = slowProfiles)

      viewModel.confirmRegistration()
      change(patient)
      ShadowLooper.idleMainLooper()
      slowProfiles.release.complete(Unit)
      ShadowLooper.idleMainLooper()

      assertEquals(0, counting.confirms)
      assertEquals(expected, registrations.registration("patient", "yoga"))
      assertNull(viewModel.uiState.value.addedByName)
    }
  }

  @Test
  fun caregiver_allowedToConfirm_cantConfirmAnEventThatStarted() {
    val patient = FollowedPatient(CarePermission.EVENTS, canAdd = setOf(CarePermission.EVENTS))
    val viewModel = caregiverViewModel(patient, now = yoga.startDateTime.plusMinutes(1))
    assertEquals(RegistrationStatus.CLOSED, viewModel.uiState.value.registration)

    viewModel.confirmRegistration()
    ShadowLooper.idleMainLooper()

    assertNull(registrations.registration("patient", "yoga"))
  }

  @Test
  fun patient_seesWhoConfirmedIt_asTheyNamedThem_andConfirmsUnsignedAfterRemoving() {
    val circle =
        FakeCareCircleRepository().apply {
          setMembers("alice", listOf(CareCircleMember(uid = "sam", firstName = "Samuel")))
        }
    registrations.confirm("alice", "yoga", EventRegistration("sam", "Sam"))
    val viewModel =
        EventDetailViewModel(
            yoga.id,
            FakeEventRepository(listOf(yoga)),
            registrations,
            signedIn(),
            clockAt(beforeYoga),
            careCircleRepository = circle,
        )
    ShadowLooper.idleMainLooper()
    assertEquals("Samuel", viewModel.uiState.value.addedByName)
    assertFalse(viewModel.uiState.value.caregiverCanConfirm)

    viewModel.requestRemoval()
    viewModel.removeRegistration()
    ShadowLooper.idleMainLooper()
    viewModel.confirmRegistration()
    ShadowLooper.idleMainLooper()

    assertEquals(EventRegistration(), registrations.registration("alice", "yoga"))
    assertNull(viewModel.uiState.value.addedByName)
  }
}
