// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.carecircle.FollowedPatient
import com.github.se.oncompanion.domain.carecircle.followedPatientAccess
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.DeletePhase
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

// Robolectric provides the main dispatcher used by viewModelScope
@RunWith(AndroidJUnit4::class)
class SymptomDetailViewModelTest {

  private val repository = FakeSymptomRepository()

  private val fatigue =
      SymptomEntry(
          id = "s1",
          types = listOf(SymptomType.FATIGUE),
          intensity = 7,
          occurredAt = Instant.parse("2026-10-05T08:00:00Z"),
          notes = "After the walk",
      )

  private fun signedIn(uid: String = "alice") =
      FakeAuthRepository(onSignIn = { AuthUser(uid = uid) }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  private fun viewModel(
      id: String = "s1",
      auth: AuthRepository = signedIn(),
      symptoms: SymptomRepository = repository,
  ) = SymptomDetailViewModel(id, auth, symptoms, careCircleRepository = circle)

  /** The signed-in patient's care circle. */
  private val circle = FakeCareCircleRepository()

  /** A ViewModel showing [fatigue], with the deletion confirmation open. */
  private fun confirmingDelete(
      auth: AuthRepository = signedIn(),
      symptoms: SymptomRepository = repository,
  ): SymptomDetailViewModel {
    repository.seed("alice", fatigue)
    return viewModel(auth = auth, symptoms = symptoms).apply { requestDelete() }
  }

  @Test
  fun existingEntry_isLoaded() {
    repository.seed("alice", fatigue)

    val state = viewModel().uiState.value

    assertEquals(fatigue, state.entry)
    assertFalse(state.isLoading)
    assertFalse(state.hasError)
    assertFalse(state.isNotFound)
  }

  @Test
  fun missingEntry_isNotFound() {
    val state = viewModel("missing").uiState.value

    assertNull(state.entry)
    assertTrue(state.isNotFound)
  }

  @Test
  fun anotherUsersEntry_isNotFound() {
    repository.seed("bob", fatigue)

    assertTrue(viewModel().uiState.value.isNotFound)
  }

  @Test
  fun loading_isNeitherNotFoundNorError() {
    val loading = SymptomDetailUiState()

    assertTrue(loading.isLoading)
    assertFalse(loading.isNotFound)
    assertFalse(loading.copy(isLoading = false, hasError = true).isNotFound)
  }

  @Test
  fun canDelete_onlyAShownEntry() {
    val shown = SymptomDetailUiState(entry = fatigue, isLoading = false)

    assertTrue(shown.canDelete)
    assertFalse(SymptomDetailUiState().canDelete)
    assertFalse(shown.copy(entry = null).canDelete)
    assertFalse(shown.copy(hasError = true).canDelete)
    assertFalse(shown.copy(delete = DeletePhase.DELETING).canDelete)
    assertFalse(shown.copy(delete = DeletePhase.DELETED).canDelete)
  }

  @Test
  fun loadError_showsTheError_andRetryLoads() {
    repository.seed("alice", fatigue)
    repository.observeError = IllegalStateException("no cache")
    val vm = viewModel()
    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isNotFound)

    repository.observeError = null
    vm.load()

    assertFalse(vm.uiState.value.hasError)
    assertEquals(fatigue, vm.uiState.value.entry)
  }

  @Test
  fun signedOut_showsAnError() {
    repository.seed("alice", fatigue)

    val state = viewModel(auth = FakeAuthRepository()).uiState.value

    assertTrue(state.hasError)
    assertFalse(state.isLoading)
  }

  @Test
  fun unreadableUser_showsAnError() {
    val throwing =
        object : AuthRepository by FakeAuthRepository() {
          override val currentUser: AuthUser?
            get() = throw IllegalStateException("Firebase not initialized")

          override fun observeCurrentUser(): Flow<AuthUser?> = throw UnsupportedOperationException()
        }

    assertTrue(viewModel(auth = throwing).uiState.value.hasError)
  }

  @Test
  fun defaultRepositories_canBeCreatedWithoutFirebase() {
    assertTrue(SymptomDetailViewModel("s1").uiState.value.hasError)
  }

  // ----- Delete -----

  @Test
  fun requestDelete_asksForConfirmation_andDeletesNothing() {
    val vm = confirmingDelete()

    assertEquals(DeletePhase.CONFIRMING, vm.uiState.value.delete)
    assertEquals(listOf(fatigue), repository.symptoms("alice"))
  }

  @Test
  fun cancelDelete_closesTheConfirmation_andKeepsTheEntry() {
    val vm = confirmingDelete()

    vm.cancelDelete()

    assertEquals(DeletePhase.IDLE, vm.uiState.value.delete)
    assertEquals(fatigue, vm.uiState.value.entry)
    assertEquals(listOf(fatigue), repository.symptoms("alice"))
  }

  @Test
  fun confirmDelete_deletesTheEntry_andTheScreenIsDone() {
    val vm = confirmingDelete()

    vm.confirmDelete()

    val state = vm.uiState.value
    assertTrue(repository.symptoms("alice").isEmpty())
    assertEquals(DeletePhase.DELETED, state.delete)
    // Still shows the entry until the screen closes, not "no longer exists"
    assertEquals(fatigue, state.entry)
    assertFalse(state.isNotFound)
    assertFalse(state.canDelete)
  }

  @Test
  fun confirmDelete_rejectedLaterByTheServer_bringsTheEntryBackAndTellsTheUser() {
    val rejectedWrites = RejectedWrites()
    val symptoms = FakeSymptomRepository(rejectedWrites = rejectedWrites)
    symptoms.seed("alice", fatigue)
    symptoms.serverRejectsWrites = true
    val vm = viewModel(symptoms = symptoms).apply { requestDelete() }

    vm.confirmDelete()

    // Done without waiting for the server
    assertEquals(DeletePhase.DELETED, vm.uiState.value.delete)
    assertTrue(symptoms.symptoms("alice").isEmpty())
    assertEquals(0, rejectedWrites.unseen.value)

    symptoms.rejectPendingWrites()

    assertEquals(listOf(fatigue), symptoms.symptoms("alice"))
    assertEquals(1, rejectedWrites.unseen.value)
  }

  @Test
  fun confirmDelete_withoutConfirmation_deletesNothing() {
    repository.seed("alice", fatigue)
    val vm = viewModel()

    vm.confirmDelete()

    assertEquals(DeletePhase.IDLE, vm.uiState.value.delete)
    assertEquals(listOf(fatigue), repository.symptoms("alice"))
  }

  @Test
  fun requestDelete_isIgnoredWithoutAnEntry() {
    val notFound = viewModel("missing")
    notFound.requestDelete()
    assertEquals(DeletePhase.IDLE, notFound.uiState.value.delete)

    repository.observeError = IllegalStateException("no cache")
    val error = viewModel()
    error.requestDelete()
    assertEquals(DeletePhase.IDLE, error.uiState.value.delete)
  }

  @Test
  fun deleteError_keepsTheEntryAndTheDialog_andRetryDeletes() {
    val vm = confirmingDelete()
    repository.writeError = IllegalStateException("rejected")

    vm.confirmDelete()

    var state = vm.uiState.value
    assertEquals(DeletePhase.FAILED, state.delete)
    // No loading state in between: the dialog stays open with the error
    assertFalse(state.isLoading)
    assertTrue(state.isDeleteDialogShown)
    assertEquals(fatigue, state.entry)
    assertEquals(listOf(fatigue), repository.symptoms("alice"))

    repository.writeError = null
    vm.confirmDelete()

    state = vm.uiState.value
    assertEquals(DeletePhase.DELETED, state.delete)
    assertTrue(repository.symptoms("alice").isEmpty())
  }

  @Test
  fun deleteError_isClearedWhenTheDialogIsClosed() {
    val vm = confirmingDelete()
    repository.writeError = IllegalStateException("rejected")
    vm.confirmDelete()

    vm.cancelDelete()
    vm.requestDelete()

    assertEquals(DeletePhase.CONFIRMING, vm.uiState.value.delete)
  }

  @Test
  fun signedOutBeforeConfirming_showsTheErrorAndDeletesNothing() {
    val auth = signedIn()
    val vm = confirmingDelete(auth = auth)
    auth.signOut()
    shadowOf(Looper.getMainLooper()).idle()

    vm.confirmDelete()

    // Signing out shows the error right away, and the symptom can't be deleted anymore
    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.canDelete)
    assertEquals(DeletePhase.IDLE, vm.uiState.value.delete)
    assertEquals(listOf(fatigue), repository.symptoms("alice"))
  }

  @Test
  fun whileDeleting_theDialogCantBeClosedOrConfirmedAgain() {
    val deleted = CompletableDeferred<Unit>()
    var deletes = 0
    val slow =
        object : SymptomRepository by repository {
          override suspend fun deleteSymptom(uid: String, id: String) {
            deletes++
            deleted.await()
            repository.deleteSymptom(uid, id)
          }
        }
    val vm = confirmingDelete(symptoms = slow)

    vm.confirmDelete()
    assertEquals(DeletePhase.DELETING, vm.uiState.value.delete)
    assertFalse(vm.uiState.value.canDelete)
    vm.cancelDelete()
    vm.confirmDelete()
    assertEquals(DeletePhase.DELETING, vm.uiState.value.delete)
    assertEquals(1, deletes)

    deleted.complete(Unit)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(DeletePhase.DELETED, vm.uiState.value.delete)
  }

  @Test
  fun deleteDialog_isShownOnlyWhileDeleteCanDoSomething() {
    val confirming =
        SymptomDetailUiState(
            entry = fatigue,
            isLoading = false,
            delete = DeletePhase.CONFIRMING,
        )

    assertTrue(confirming.isDeleteDialogShown)
    assertTrue(confirming.copy(delete = DeletePhase.DELETING).isDeleteDialogShown)
    assertFalse(confirming.copy(delete = DeletePhase.IDLE).isDeleteDialogShown)
    assertFalse(confirming.copy(entry = null).isDeleteDialogShown)
    assertFalse(confirming.copy(isLoading = true).isDeleteDialogShown)
    assertFalse(confirming.copy(hasError = true).isDeleteDialogShown)
  }

  @Test
  fun entryDeletedElsewhereWhileConfirming_closesTheConfirmation() {
    val vm = confirmingDelete()

    // Deleted on another device
    runBlocking { repository.deleteSymptom("alice", "s1") }
    shadowOf(Looper.getMainLooper()).idle()

    var state = vm.uiState.value
    assertTrue(state.isNotFound)
    assertEquals(DeletePhase.IDLE, state.delete)
    assertFalse(state.isDeleteDialogShown)

    // Coming back (e.g. the deletion was rolled back) doesn't reopen the dialog
    repository.seed("alice", fatigue)
    shadowOf(Looper.getMainLooper()).idle()
    state = vm.uiState.value
    assertEquals(fatigue, state.entry)
    assertFalse(state.isDeleteDialogShown)
  }

  @Test
  fun loadErrorAfterAFailedDelete_closesTheConfirmation() {
    val vm = confirmingDelete()
    repository.writeError = IllegalStateException("rejected")
    repository.observeError = IllegalStateException("no cache")

    vm.confirmDelete()

    val state = vm.uiState.value
    assertTrue(state.hasError)
    assertEquals(DeletePhase.IDLE, state.delete)
    assertFalse(state.isDeleteDialogShown)
  }

  @Test
  fun accessLostWhileConfirming_closesTheConfirmation_forGood() {
    val auth = signedIn()
    val vm = confirmingDelete(auth = auth)

    // The access briefly goes away, then comes back with the symptom
    auth.signOut()
    shadowOf(Looper.getMainLooper()).idle()
    runBlocking { auth.signInWithGoogle("token") }
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(fatigue, vm.uiState.value.entry)
    assertEquals(DeletePhase.IDLE, vm.uiState.value.delete)
  }

  // ----- Caregiver mode (US-20) -----

  private fun caregiverViewModel(vararg shared: CarePermission) =
      SymptomDetailViewModel(
          "s1",
          signedIn("caregiver"),
          repository,
          patientUid = "patient",
          resolveSection = followedPatientAccess(*shared),
      )

  @Test
  fun caregiver_symptomsShared_showsThePatientsSymptom_readOnly() {
    repository.seed("patient", fatigue)

    val state = caregiverViewModel(CarePermission.SYMPTOMS).uiState.value

    assertEquals(fatigue, state.entry)
    assertFalse(state.canEdit)
    assertFalse(state.canDelete)
  }

  @Test
  fun caregiver_symptomsNotShared_isNotShared_notNotFound() {
    repository.seed("patient", fatigue)

    val state = caregiverViewModel(CarePermission.PLANNING).uiState.value

    assertTrue(state.isNotShared)
    assertNull(state.entry)
    assertFalse(state.isNotFound)
    assertFalse(state.hasError)
  }

  /** A caregiver's view of "patient"'s symptom, while [patient] changes the access. */
  private fun followedViewModel(patient: FollowedPatient) =
      SymptomDetailViewModel(
          "s1",
          patient.auth,
          repository,
          patientUid = "patient",
          resolveSection = patient.sectionAccess,
          careCircleRepository = patient.circle,
      )

  @Test
  fun caregiver_accessUnknownAgain_hidesTheSymptom() {
    val patient = FollowedPatient(CarePermission.SYMPTOMS)
    repository.seed("patient", fatigue)
    val vm = followedViewModel(patient)
    assertEquals(fatigue, vm.uiState.value.entry)

    // e.g. the membership now only comes from the cache, and the server hasn't confirmed it
    patient.isUnconfirmed()
    shadowOf(Looper.getMainLooper()).idle()

    val state = vm.uiState.value
    assertNull(state.entry)
    assertNull(state.addedByName)
    assertTrue(state.isLoading)
    assertFalse(state.canEdit)
  }

  @Test
  fun caregiver_readRefusedOnceShared_isAnErrorOnly_notNotShared() {
    val patient = FollowedPatient(CarePermission.PLANNING)
    repository.seed("patient", fatigue)
    val vm = followedViewModel(patient)
    assertTrue(vm.uiState.value.isNotShared)

    repository.observeError = IllegalStateException("Permission denied")
    patient.shares(CarePermission.SYMPTOMS)
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isNotShared)
  }

  // ----- Who added the symptom (US-22) -----

  /** Added by "bob", whose own profile says "Bob". */
  private val addedByBob = fatigue.copy(addedBy = "bob", addedByName = "Bob")

  @Test
  fun patient_seesTheNameTheyGaveTheMember_untilTheyAreRemoved() {
    repository.seed("alice", addedByBob)
    circle.setMembers("alice", listOf(CareCircleMember(uid = "bob", firstName = "Robert")))
    val vm = viewModel()
    // The stored name shows first, then the circle's once it is read
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals("Robert", vm.uiState.value.addedByName)
    circle.setMembers("alice", emptyList())
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("Bob", vm.uiState.value.addedByName)
  }

  @Test
  fun patient_circleNeverAnswers_stillSeesTheSymptom_withTheStoredName() {
    // Offline, a circle never read on this device may not answer at all
    repository.seed("alice", addedByBob)
    circle.membersNeverAnswer = true

    val vm = viewModel()

    assertEquals(addedByBob, vm.uiState.value.entry)
    assertEquals("Bob", vm.uiState.value.addedByName)
  }

  @Test
  fun caregiver_seesTheStoredName_neverWhatThePatientCallsThem() {
    val patient = FollowedPatient(CarePermission.SYMPTOMS)
    // The patient calls the caregiver "Sam" in their circle; the symptom stored "Bob"
    repository.seed("patient", addedByBob.copy(addedBy = "caregiver"))

    val vm =
        SymptomDetailViewModel(
            "s1",
            patient.auth,
            repository,
            patientUid = "patient",
            resolveSection = patient.sectionAccess,
            careCircleRepository = patient.circle,
        )

    assertEquals("Bob", vm.uiState.value.addedByName)
  }
}
