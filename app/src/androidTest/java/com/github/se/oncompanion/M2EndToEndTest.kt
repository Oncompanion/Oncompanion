// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.domain.auth.SignOutState
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepositoryFirestore
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.GoogleCredentialProvider
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.utils.EmulatorGoogleSignIn
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.EndToEndTestDriver
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Source
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2's two-account journey through real screens and Firebase emulators. Only Google's account
 * picker is replaced. Revocation reuses the same accounts, sign-in helpers and cleanup rather than
 * relying on another test running first.
 */
@RunWith(AndroidJUnit4::class)
class M2EndToEndTest {
  @get:Rule val compose = createAndroidComposeRule<AppHostActivity>()

  private val ui = EndToEndTestDriver(compose)

  private val run = UUID.randomUUID().toString()
  private val patient = Account("patient", "Sam")
  private val caregiver = Account("caregiver", "Julie")
  // The patient's private contact name differs from the caregiver's own account name.
  private val circleName = "Juju"
  private val credentials = AccountPicker()
  private val patientNotes = "Patient entry $run"
  private val caregiverNotes = "Caregiver entry $run"
  private lateinit var inviteCode: String

  // Record identities as soon as Auth creates them, even if onboarding or the next UI wait fails.
  private val authListener = FirebaseAuth.AuthStateListener { auth ->
    for (account in listOf(patient, caregiver)) {
      auth.currentUser?.takeIf { it.email == account.email }?.let { account.uid = it.uid }
    }
  }

  /** Starts signed out and tracks only this run's newly created identities. */
  @Before
  fun setUp() {
    EmulatorTestData.signOut()
    for (account in listOf(patient, caregiver)) ui.redact(account.email, "<test email>")
    FirebaseEmulator.auth.addAuthStateListener(authListener)
  }

  /** Disposes consumers, clears the local session, then removes every account owned by this run. */
  @After
  fun tearDown() {
    val failures = mutableListOf<Throwable>()
    fun attempt(block: () -> Unit) {
      try {
        block()
      } catch (error: Throwable) {
        failures += error
      }
    }
    attempt { compose.runOnUiThread { AppHostActivity.content.value = {} } }
    attempt {
      runBlocking {
        val signOut = SignOutProvider.signOut
        withContext(Dispatchers.Main) { signOut.begin {} }
        val state =
            withTimeout(30_000) {
              signOut.state.first {
                it == SignOutState.COMPLETE ||
                    it == SignOutState.FAILED ||
                    it == SignOutState.NEEDS_DISCARD_CONFIRMATION
              }
            }
        // Test-owned drafts may be discarded during teardown, never during the asserted journey.
        if (state == SignOutState.NEEDS_DISCARD_CONFIRMATION) {
          withContext(Dispatchers.Main) { signOut.discard {} }
          withTimeout(30_000) {
            signOut.state.first { it == SignOutState.COMPLETE || it == SignOutState.FAILED }
          }
        }
        check(signOut.state.value == SignOutState.COMPLETE) { "Local session cleanup failed" }
        // Later navigation tests supply their own start route; don't force them back to Auth.
        withContext(Dispatchers.Main) { signOut.sessionStarted() }
      }
    }
    FirebaseEmulator.auth.removeAuthStateListener(authListener)
    for (account in listOf(patient, caregiver)) {
      account.uid?.let { uid -> attempt { runBlocking { EmulatorTestData.deleteUserData(uid) } } }
    }
    attempt { EmulatorTestData.signOut() }
    if (failures.isNotEmpty()) {
      throw AssertionError("M2 cleanup failed", failures.first()).apply {
        failures.drop(1).forEach(::addSuppressed)
      }
    }
  }

  /**
   * Checks shared symptoms and attribution, then revokes access and checks a fresh caregiver
   * session.
   */
  @Test
  fun patientAndCaregiver_shareLogAndRevokeSymptoms() {
    ui.startApp(credentials)
    signInAndOnboard(patient, C.Tag.onboarding_role_patient, patientGreeting())
    openSymptoms(asPatient = true)
    logSymptom("FATIGUE", patientNotes)
    ui.open(C.Tag.symptom_back_button, patientGreeting())
    inviteCaregiver()
    ui.open(C.Tag.care_circle_back_button, patientGreeting())
    signOutThroughProfile()

    credentials.select(caregiver)
    signInAndOnboard(
        caregiver,
        C.Tag.onboarding_role_caregiver,
        hasTestTag(C.Tag.join_circle_screen),
    )
    assertNotEquals("Each person must have their own account", patient.uid, caregiver.uid)
    joinCareCircle()
    openSymptoms(asPatient = false)
    assertSymptom("FATIGUE", patientNotes)
    assertReadOnly(patientNotes)
    ui.tag(C.Tag.symptom_journal_add).assertIsDisplayed().assertIsEnabled()
    logSymptom("NAUSEA", caregiverNotes)
    assertCaregiverAttribution()
    assertSymptom("FATIGUE", patientNotes)
    ui.open(C.Tag.symptom_back_button, caregiverTitle())
    signOutThroughProfile()

    // Sign-out clears local data: an existing circle must be found again through Whose care.
    ui.open(C.Tag.google_sign_in_button, caregiverTitle())
    assertEquals(caregiver.uid, FirebaseEmulator.auth.currentUser?.uid)
    openSymptoms(asPatient = false)
    assertSymptom("FATIGUE", patientNotes)
    assertSymptom("NAUSEA", caregiverNotes)
    assertReadOnly(patientNotes)
    assertCaregiverAttribution()
    ui.open(C.Tag.symptom_back_button, caregiverTitle())
    signOutThroughProfile()

    credentials.select(patient)
    ui.open(C.Tag.google_sign_in_button, patientGreeting())
    assertEquals(patient.uid, FirebaseEmulator.auth.currentUser?.uid)
    openSymptoms(asPatient = true)
    assertSymptom("FATIGUE", patientNotes)
    val addedBy = compose.activity.getString(R.string.symptom_added_by, circleName)
    val caregiverRow = symptomRow(caregiverNotes)
    ui.tag(C.Tag.symptom_journal_list).performScrollToNode(caregiverRow)
    ui.waitFor(caregiverRow and text(addedBy)).assertIsDisplayed()
    // Detail proves the full notes and attribution belong to this exact saved entry.
    ui.open(caregiverRow, hasTestTag(C.Tag.symptom_detail_content))
    // The restyled detail puts notes and attribution after the recorded symptom fields.
    ui.waitFor(hasText(caregiverNotes)).reveal().assertIsDisplayed()
    ui.waitFor(hasTestTag(C.Tag.symptom_detail_notes) and text(caregiverNotes)).assertIsDisplayed()
    ui.waitFor(hasTestTag(C.Tag.added_by) and hasText(addedBy), unmerged = true)
        .reveal()
        .assertIsDisplayed()
    ui.open(C.Tag.symptom_back_button, hasTestTag(C.Tag.symptom_journal_list))
    ui.open(C.Tag.symptom_back_button, patientGreeting())
    assertAcceptedMember()
    revokeSymptomSharing()
    ui.open(C.Tag.care_circle_member_back_button, hasTestTag(C.Tag.care_circle_member_list))
    ui.open(C.Tag.care_circle_back_button, patientGreeting())
    signOutThroughProfile()

    credentials.select(caregiver)
    ui.open(C.Tag.google_sign_in_button, caregiverTitle())
    assertEquals(caregiver.uid, FirebaseEmulator.auth.currentUser?.uid)
    // Both shortcuts use the same shared-sections snapshot; Planning's spinner is independent.
    ui.tag(C.Tag.overview_shortcut_prescriptions).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.overview_shortcut_symptoms).assertDoesNotExist()
    // Recompose without recreating the activity: its SessionStores retains the session's
    // CaregiverModeViewModel and chosen patient. Inject only now: retaining an external controller
    // across sign-out would interfere with the session boundary's navigation-store cleanup.
    lateinit var navigation: TestNavHostController
    compose.runOnUiThread { AppHostActivity.content.value = {} }
    compose.waitForIdle()
    compose.runOnUiThread {
      navigation =
          TestNavHostController(compose.activity).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
          }
    }
    ui.startApp(credentials, navigation)
    ui.waitFor(caregiverTitle()).assertIsDisplayed()
    // The shortcut is hidden; opening the guarded route must still show no symptom data.
    compose.runOnUiThread { navigation.navigate(Route.SYMPTOMS) }
    val notShared =
        compose.activity.getString(
            R.string.not_shared_title,
            patient.firstName,
            compose.activity.getString(R.string.permission_symptoms),
        )
    ui.waitFor(hasTestTag(C.Tag.not_shared) and text(notShared)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.symptom_journal_list).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.symptom_journal_add).assertDoesNotExist()
  }

  /**
   * Caregiver edits target the patient, keep confirmation pending and delete only after consent.
   */
  @Test
  fun caregiver_editsAndDeletesThePatientsPrescription() {
    ui.startApp(credentials)
    signInAndOnboard(patient, C.Tag.onboarding_role_patient, patientGreeting())
    inviteCaregiver(addPrescriptions = true)
    ui.open(C.Tag.care_circle_back_button, patientGreeting())
    signOutThroughProfile()
    credentials.select(caregiver)
    signInAndOnboard(
        caregiver,
        C.Tag.onboarding_role_caregiver,
        hasTestTag(C.Tag.join_circle_screen),
    )
    joinCareCircle()
    val patientUid = checkNotNull(patient.uid)
    val caregiverUid = checkNotNull(caregiver.uid)
    val prescriptionId = "m2-prescription-$run"
    val confirmedId = "m2-confirmed-$run"
    // Edit/delete permission is granted only after the invitation has been accepted.
    signOutThroughProfile()
    credentials.select(patient)
    ui.open(C.Tag.google_sign_in_button, patientGreeting())
    assertAcceptedMember()
    ui.open(C.Tag.careCircleMember(caregiverUid), hasTestTag(C.Tag.care_circle_member_details))
    val edit = C.Tag.careCircleFormCanEditAndDelete("PRESCRIPTIONS")
    ui.open(C.Tag.care_circle_member_edit_button, hasTestTag(edit))
        .reveal()
        .assertIsOff()
        .performClick()
    ui.tag(edit).assertIsOn()
    ui.open(C.Tag.care_circle_form_save_button, hasTestTag(C.Tag.care_circle_member_details))
    ui.open(C.Tag.care_circle_member_back_button, hasTestTag(C.Tag.care_circle_member_list))
    ui.open(C.Tag.care_circle_back_button, patientGreeting())
    runBlocking {
      MedicationRepositoryFirestore(FirebaseEmulator.firestore)
          .addPrescription(patientUid, prescriptionFixture(confirmedId))
      withTimeout(10_000) { FirebaseEmulator.firestore.waitForPendingWrites().await() }
    }
    signOutThroughProfile()
    credentials.select(caregiver)
    ui.open(C.Tag.google_sign_in_button, caregiverTitle())
    // Seed a pending prescription; edits, account switches and deletion use real screens.
    runBlocking {
      val repository = MedicationRepositoryFirestore(FirebaseEmulator.firestore)
      repository.addPrescription(patientUid, prescriptionFixture(prescriptionId, caregiverUid))
      withTimeout(10_000) { FirebaseEmulator.firestore.waitForPendingWrites().await() }
    }
    ui.open(C.Tag.overview_shortcut_prescriptions, hasTestTag(C.Tag.prescriptions_list))
    ui.open(C.Tag.prescriptionsItem(prescriptionId), hasTestTag(C.Tag.prescription_detail_content))
    ui.waitFor(
        hasTestTag(C.Tag.added_by) and
            hasText(compose.activity.getString(R.string.added_by_not_confirmed, "Julie"))
    )
    ui.open(
            C.Tag.prescription_detail_edit_button,
            hasTestTag(C.Tag.prescription_form_prescribed_by_field),
        )
        .replaceText("Dr Updated")
    // Editing a pending entry never exposes the patient's Confirm or Reject actions.
    compose.onNodeWithTag(C.Tag.prescription_form_reject_button).assertDoesNotExist()
    compose
        .onNodeWithText(compose.activity.getString(R.string.prescription_confirm_save))
        .assertDoesNotExist()
    ui.tag(C.Tag.prescription_form_save_button)
        .assertTextEquals(compose.activity.getString(R.string.prescription_form_save))
    ui.open(C.Tag.prescription_form_save_button, hasTestTag(C.Tag.prescription_detail_content))
    ui.waitFor(hasText("Dr Updated")).assertIsDisplayed()
    ui.waitFor(
            hasTestTag(C.Tag.edited_by) and
                hasText(compose.activity.getString(R.string.edited_by, "Julie"))
        )
        .assertIsDisplayed()
    ui.waitFor(
        hasTestTag(C.Tag.added_by) and
            hasText(compose.activity.getString(R.string.added_by_not_confirmed, "Julie"))
    )
    runBlocking {
      withTimeout(10_000) { FirebaseEmulator.firestore.waitForPendingWrites().await() }
      val stored =
          FirebaseEmulator.firestore
              .collection("users")
              .document(patientUid)
              .collection("prescriptions")
              .document(prescriptionId)
              .get(Source.SERVER)
              .await()
      assertEquals("Dr Updated", stored.getString("prescribedBy"))
      assertEquals("PENDING", stored.getString("confirmation"))
      assertEquals(caregiverUid, stored.getString("editedBy"))
      assertEquals(caregiverUid, stored.getString("addedBy"))
      assertTrue(
          FirebaseEmulator.firestore
              .collection("users")
              .document(caregiverUid)
              .collection("prescriptions")
              .get(Source.SERVER)
              .await()
              .isEmpty
      )
    }
    // A confirmed entry opens its detail for the patient; pending entries open the review form.
    ui.open(C.Tag.prescription_detail_back_button, hasTestTag(C.Tag.prescriptions_list))
    ui.open(C.Tag.prescriptionsItem(confirmedId), hasTestTag(C.Tag.prescription_detail_content))
    ui.open(
            C.Tag.prescription_detail_edit_button,
            hasTestTag(C.Tag.prescription_form_prescribed_by_field),
        )
        .replaceText("Dr Confirmed Updated")
    ui.open(C.Tag.prescription_form_save_button, hasTestTag(C.Tag.prescription_detail_content))
    ui.waitFor(hasText("Dr Confirmed Updated")).assertIsDisplayed()
    runBlocking {
      withTimeout(10_000) { FirebaseEmulator.firestore.waitForPendingWrites().await() }
      val stored =
          FirebaseEmulator.firestore
              .collection("users")
              .document(patientUid)
              .collection("prescriptions")
              .document(confirmedId)
              .get(Source.SERVER)
              .await()
      assertEquals("CONFIRMED", stored.getString("confirmation"))
      assertEquals(caregiverUid, stored.getString("editedBy"))
    }
    // The patient sees their private contact name for the caregiver who edited the entry.
    returnToOverviewFromPrescription()
    signOutThroughProfile()
    credentials.select(patient)
    ui.open(C.Tag.google_sign_in_button, patientGreeting())
    ui.open(C.Tag.overview_shortcut_prescriptions, hasTestTag(C.Tag.prescriptions_list))
    ui.open(C.Tag.prescriptionsItem(confirmedId), hasTestTag(C.Tag.prescription_detail_content))
    ui.waitFor(
            hasTestTag(C.Tag.edited_by) and
                hasText(compose.activity.getString(R.string.edited_by, circleName)),
            unmerged = true,
        )
        .reveal()
        .assertIsDisplayed()
    ui.waitFor(hasText("Dr Confirmed Updated")).assertIsDisplayed()
    ui.open(C.Tag.prescription_detail_back_button, hasTestTag(C.Tag.prescriptions_list))
    ui.open(C.Tag.prescriptionsItem(prescriptionId), hasTestTag(C.Tag.prescription_confirm_notice))
    ui.waitFor(hasText("Dr Updated")).assertIsDisplayed()
    ui.open(C.Tag.prescription_form_close_button, hasTestTag(C.Tag.prescriptions_list))
    ui.open(C.Tag.prescriptions_back_button, patientGreeting())
    signOutThroughProfile()
    credentials.select(caregiver)
    ui.open(C.Tag.google_sign_in_button, caregiverTitle())
    ui.open(C.Tag.overview_shortcut_prescriptions, hasTestTag(C.Tag.prescriptions_list))
    ui.open(C.Tag.prescriptionsItem(prescriptionId), hasTestTag(C.Tag.prescription_detail_content))
    ui.open(C.Tag.prescription_detail_delete_button, hasTestTag(C.Tag.prescription_delete_dialog))
    ui.waitFor(
        hasText(
            compose.activity.getString(R.string.prescription_delete_title_for, patient.firstName)
        )
    )
    ui.open(C.Tag.prescription_delete_cancel, hasTestTag(C.Tag.prescription_detail_content))
    ui.waitFor(hasText("Dr Updated")).assertIsDisplayed()
    ui.open(C.Tag.prescription_detail_delete_button, hasTestTag(C.Tag.prescription_delete_dialog))
    ui.open(C.Tag.prescription_delete_confirm, hasTestTag(C.Tag.prescriptions_list))
    ui.waitFor(hasTestTag(C.Tag.prescriptionsItem(prescriptionId)), gone = true)
    ui.waitFor(hasTestTag(C.Tag.prescriptionsItem(confirmedId))).assertIsDisplayed()
    runBlocking {
      withTimeout(10_000) { FirebaseEmulator.firestore.waitForPendingWrites().await() }
      assertFalse(
          FirebaseEmulator.firestore
              .collection("users")
              .document(patientUid)
              .collection("prescriptions")
              .document(prescriptionId)
              .get(Source.SERVER)
              .await()
              .exists()
      )
    }
  }

  /** Fictional data covers both pending caregiver additions and confirmed patient prescriptions. */
  private fun prescriptionFixture(id: String, addedBy: String? = null) =
      Prescription(
          id = id,
          prescribedBy = "Dr Martin",
          prescribedOn = LocalDate.now(),
          medications =
              listOf(
                  Medication(
                      id = "$id-medication",
                      prescriptionId = id,
                      name = "Fictional medication",
                      startDate = LocalDate.now(),
                  )
              ),
          addedBy = addedBy,
          addedByName = addedBy?.let { caregiver.firstName },
          confirmation =
              if (addedBy == null) PrescriptionConfirmation.CONFIRMED
              else PrescriptionConfirmation.PENDING,
      )

  private fun returnToOverviewFromPrescription() {
    ui.open(C.Tag.prescription_detail_back_button, hasTestTag(C.Tag.prescriptions_list))
    ui.open(C.Tag.prescriptions_back_button, hasTestTag(C.Tag.overview_shortcut_profile))
  }

  private fun signInAndOnboard(account: Account, roleTag: String, destination: SemanticsMatcher) {
    ui.signInAndOnboard(account.firstName, roleTag, destination)
    checkNotNull(account.uid) { "The new account wasn't recorded" }
  }

  /** The patient opens the journal from the check-in's "Journal ›", a caregiver from its tile. */
  private fun openSymptoms(asPatient: Boolean) =
      ui.open(
          if (asPatient) C.Tag.overview_journal_link else C.Tag.overview_shortcut_symptoms,
          hasTestTag(C.Tag.symptom_journal_add),
      )

  private fun logSymptom(type: String, notes: String) {
    ui.open(C.Tag.symptom_journal_add, hasTestTag(C.Tag.symptomAddType(type))).performClick()
    ui.tag(C.Tag.symptom_add_notes).reveal().replaceText(notes)
    ui.open(C.Tag.symptom_add_save, hasTestTag(C.Tag.symptom_journal_list))
    assertSymptom(type, notes)
  }

  private fun assertSymptom(type: String, notes: String) {
    val title =
        compose.activity.getString(
            if (type == "FATIGUE") R.string.symptom_type_fatigue else R.string.symptom_type_nausea
        )
    val row = symptomRow(notes)
    ui.tag(C.Tag.symptom_journal_list).performScrollToNode(row)
    ui.waitFor(row and text(title)).assertIsDisplayed()
  }

  /** A caregiver can read a patient's recorded entry without being offered changes. */
  private fun assertReadOnly(notes: String) {
    ui.open(symptomRow(notes), hasTestTag(C.Tag.symptom_detail_content))
    ui.waitFor(hasTestTag(C.Tag.symptom_detail_notes) and text(notes)).reveal().assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.symptom_detail_edit).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.symptom_detail_delete).assertDoesNotExist()
    ui.open(C.Tag.symptom_back_button, hasTestTag(C.Tag.symptom_journal_list))
  }

  /** The caregiver sees their own name, never the patient's private contact name. */
  private fun assertCaregiverAttribution() {
    val ownName = compose.activity.getString(R.string.symptom_added_by, caregiver.firstName)
    val row = symptomRow(caregiverNotes)
    ui.tag(C.Tag.symptom_journal_list).performScrollToNode(row)
    ui.waitFor(row and text(ownName)).assertIsDisplayed()
    assertPrivateNameAbsent()
    ui.open(row, hasTestTag(C.Tag.symptom_detail_content))
    ui.waitFor(hasTestTag(C.Tag.symptom_detail_notes) and text(caregiverNotes))
        .reveal()
        .assertIsDisplayed()
    ui.waitFor(hasTestTag(C.Tag.added_by) and hasText(ownName), unmerged = true)
        .reveal()
        .assertIsDisplayed()
    assertPrivateNameAbsent()
    ui.open(C.Tag.symptom_back_button, hasTestTag(C.Tag.symptom_journal_list))
  }

  private fun assertPrivateNameAbsent() {
    assertFalse(
        "The caregiver must never see what the patient calls them",
        ui.exists(hasText(circleName, substring = true), unmerged = true),
    )
  }

  private fun inviteCaregiver(addPrescriptions: Boolean = false) {
    ui.open(C.Tag.overview_shortcut_care_circle, hasTestTag(C.Tag.care_circle_empty_add_button))
    ui.open(C.Tag.care_circle_empty_add_button, hasTestTag(C.Tag.care_circle_form_first_name))
        .replaceText(circleName)
    ui.tag(C.Tag.care_circle_form_relationship).reveal().performClick()
    ui.tag(C.Tag.careCircleFormRelationshipOption("DAUGHTER")).reveal().performClick()
    ui.waitFor(
        hasTestTag(C.Tag.care_circle_form_relationship) and
            text(compose.activity.getString(R.string.relationship_daughter))
    )
    val view = C.Tag.careCircleFormPermission("SYMPTOMS")
    val add = C.Tag.careCircleFormCanAdd("SYMPTOMS")
    ui.tag(view).reveal().assertIsOff().performClick()
    ui.tag(view).assertIsOn()
    ui.tag(add).reveal().assertIsEnabled().assertIsOff().performClick()
    ui.tag(add).assertIsOn()
    // Keep one shared shortcut after revocation, proving permissions finished loading at sign-in.
    ui.tag(C.Tag.careCircleFormPermission("PRESCRIPTIONS")).reveal().assertIsOff().performClick()
    ui.tag(C.Tag.careCircleFormPermission("PRESCRIPTIONS")).assertIsOn()
    if (addPrescriptions) {
      ui.tag(C.Tag.careCircleFormCanAdd("PRESCRIPTIONS")).reveal().assertIsOff().performClick()
      ui.tag(C.Tag.careCircleFormCanAdd("PRESCRIPTIONS")).assertIsOn()
    }
    ui.open(C.Tag.care_circle_form_save_button, hasTestTag(C.Tag.care_circle_invite_code))
    val displayed =
        ui.tag(C.Tag.care_circle_invite_code)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()
            .text
    inviteCode = CareCircleInvite.normalizeInviteCode(displayed)
    ui.redact(displayed, "<invite code>")
    ui.redact(inviteCode, "<invite code>")
    assertTrue(
        "The displayed invitation must contain a usable code",
        CareCircleInvite.isWellFormedInviteCode(inviteCode),
    )
    ui.open(C.Tag.care_circle_invite_back_button, hasTestTag(C.Tag.careCircleInvite(inviteCode)))
    ui.waitFor(hasTestTag(C.Tag.care_circle_pending_label), unmerged = true).assertIsDisplayed()
  }

  private fun joinCareCircle() {
    ui.tag(C.Tag.join_circle_code_field).reveal().replaceText(inviteCode)
    ui.open(
        C.Tag.join_circle_join_button,
        hasTestTag(C.Tag.join_circle_joined) and text(patient.firstName),
    )
    ui.open(C.Tag.join_circle_continue, caregiverTitle())
  }

  private fun assertAcceptedMember() {
    val member = C.Tag.careCircleMember(checkNotNull(caregiver.uid))
    ui.open(C.Tag.overview_shortcut_care_circle, hasTestTag(C.Tag.care_circle_member_list))
    ui.tag(C.Tag.care_circle_member_list).performScrollToNode(hasTestTag(member))
    ui.waitFor(hasTestTag(member) and text(circleName)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.careCircleInvite(inviteCode)).assertDoesNotExist()
    compose
        .onNodeWithTag(C.Tag.care_circle_pending_label, useUnmergedTree = true)
        .assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.care_circle_pending_section).assertDoesNotExist()
  }

  /** Revokes the accepted member's symptom access using the same form a patient uses. */
  private fun revokeSymptomSharing() {
    ui.open(
        C.Tag.careCircleMember(checkNotNull(caregiver.uid)),
        hasTestTag(C.Tag.care_circle_member_details),
    )
    val permission = C.Tag.careCircleFormPermission("SYMPTOMS")
    ui.open(C.Tag.care_circle_member_edit_button, hasTestTag(permission))
        .reveal()
        .assertIsOn()
        .performClick()
    ui.tag(permission).assertIsOff()
    ui.tag(C.Tag.careCircleFormCanAdd("SYMPTOMS")).reveal().assertIsOff()
    ui.open(C.Tag.care_circle_form_save_button, hasTestTag(C.Tag.care_circle_member_details))
    ui.waitFor(hasTestTag(C.Tag.careCircleMemberPermission("SYMPTOMS")) and isOff())
        .reveal()
        .assertIsOff()
    ui.waitFor(hasTestTag(C.Tag.careCircleMemberCanAdd("SYMPTOMS")) and isOff())
        .reveal()
        .assertIsOff()
  }

  private fun signOutThroughProfile() {
    ui.open(C.Tag.overview_shortcut_profile, hasTestTag(C.Tag.profile_sign_out))
    ui.open(C.Tag.profile_sign_out, hasTestTag(C.Tag.profile_sign_out_confirm))
    ui.open(C.Tag.profile_sign_out_confirm, hasTestTag(C.Tag.sign_in_screen))
    assertEquals(null, FirebaseEmulator.auth.currentUser)
  }

  private fun patientGreeting() = hasTestTag(C.Tag.overview_greeting) and text(patient.firstName)

  private fun caregiverTitle() =
      hasTestTag(C.Tag.overview_whose_care) and
          text(compose.activity.getString(R.string.overview_care_of, patient.firstName))

  private fun text(value: String) =
      hasText(value, substring = true) or hasAnyDescendant(hasText(value, substring = true))

  private fun symptomRow(notes: String) =
      SemanticsMatcher("a symptom row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("symptom_journal_item_") ==
            true
      } and text(notes)

  // Keep fluent journey steps while sharing all interaction behavior with M1.
  private fun SemanticsNodeInteraction.replaceText(value: String) = ui.replaceText(this, value)

  private fun SemanticsNodeInteraction.reveal() = ui.reveal(this)

  private inner class Account(role: String, val firstName: String) {
    val email = "m2-$role-$run@example.com"
    val provider =
        EmulatorGoogleSignIn(
            FirebaseEmulator.fakeGoogleIdToken(
                sub = "m2-$role-$run",
                email = email,
                name = firstName,
                givenName = firstName,
            )
        )
    var uid: String? = null
  }

  private inner class AccountPicker : GoogleCredentialProvider {
    private var selected = patient

    fun select(account: Account) {
      selected = account
    }

    override suspend fun getGoogleIdToken(context: Context) =
        selected.provider.getGoogleIdToken(context)

    override suspend fun clearCredentialState(context: Context) = Unit
  }
}
