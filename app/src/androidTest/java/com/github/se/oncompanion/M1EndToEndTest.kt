// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.utils.EmulatorGoogleSignIn
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.EndToEndTestDriver
import com.github.se.oncompanion.utils.FirebaseEmulator
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M1 end-to-end test: a new patient goes through the whole app like a real user, against the
 * Firebase emulators. It signs in, goes through onboarding, then uses every main feature, and
 * checks the links between them: an appointment added in Planning shows on the Overview, a Ligue
 * event the patient confirms in Events shows in Planning, the first name given at onboarding shows
 * on the Overview and the profile, and signing in again finds everything where it was.
 *
 * Only Google's account picker is replaced, as a test can't drive it: [EmulatorGoogleSignIn]
 * returns a token the Auth emulator accepts, for a new account on every run.
 */
@RunWith(AndroidJUnit4::class)
class M1EndToEndTest {

  // Shows the app like MainActivity does, so it survives Android relaunching the screen
  @get:Rule val compose = createAndroidComposeRule<AppHostActivity>()

  private val ui = EndToEndTestDriver(compose)

  // A new account each run, so the test always starts with onboarding and empty data
  private val run = UUID.randomUUID().toString().take(8)
  private val signIn =
      EmulatorGoogleSignIn(
          FirebaseEmulator.fakeGoogleIdToken(
              sub = "m1-$run",
              email = "m1-$run@example.com",
              name = "Sam Rivera",
              givenName = "Sam",
          )
      )

  // A Ligue event for tomorrow. Events are added from the Firebase console, never by the app, and
  // the collection is shared by every test run: its own id, deleted after the test
  private val ligueEvent = "m1-event-$run"

  @Before fun setUp(): Unit = runBlocking { EmulatorTestData.signOut() }

  @After
  fun tearDown(): Unit = runBlocking {
    AppHostActivity.content.value = {}
    EmulatorTestData.signOut()
    runCatching { EmulatorTestData.deleteRawDocument("events/$ligueEvent") }
  }

  @Test
  fun newPatient_usesEveryMainFeature() {
    addLigueEventTomorrow(title = "Gentle yoga")
    ui.startApp(signIn)

    signInAndOnboard(firstName = "Sam")
    addPrescription(doctor = "Dr Martin", medication = "Tamoxifen")
    addAppointmentTomorrow(title = "Oncology check-up")
    checkPlanningLandscape(title = "Oncology check-up")
    openMedicationAndItsPrescription("Tamoxifen")
    seeNextAppointmentOnOverview(title = "Oncology check-up")
    confirmRegistrationForLigueEvent(title = "Gentle yoga", place = "Ligue, Geneva")
    seeConfirmedEventInPlanning(title = "Gentle yoga", appointment = "Oncology check-up")
    logSymptom()
    deleteSymptom()
    seeProfileAndAddHospital(firstName = "Sam", hospital = "HUG Geneva")
    checkProfileLandscape()
    signOut()
    signInAgain(firstName = "Sam", nextAppointment = "Oncology check-up")
  }

  @Test
  fun newPatient_invitesSomeoneToTheirCareCircle() {
    ui.startApp(signIn)

    signInAndOnboard(firstName = "Sam")
    inviteToCareCircle(firstName = "Julie")
  }

  // ---- steps ----

  /** Medication details and their source prescription return to the same selected Planning day. */
  private fun openMedicationAndItsPrescription(name: String) {
    val selectedDay =
        ui.tag(C.Tag.planning_heading).fetchSemanticsNode().config[SemanticsProperties.Text]
    ui.open(hasText(name), hasTestTag(C.Tag.medication_detail_prescription))
    ui.waitFor(hasText("20 mg")).assertIsDisplayed()
    ui.open(C.Tag.medication_detail_prescription, hasTestTag(C.Tag.prescription_detail_screen))
    // The prescription heading may repeat its only medication's name; target the medication row.
    ui.waitFor(
            hasText(name) and
                SemanticsMatcher("is a prescription medication row") {
                  it.config
                      .getOrNull(SemanticsProperties.TestTag)
                      ?.startsWith(C.Tag.prescriptionDetailMedication("")) == true
                }
        )
        .reveal()
        .assertIsDisplayed()
    ui.open(C.Tag.prescription_detail_back_button, hasTestTag(C.Tag.medication_detail_screen))
    ui.open(C.Tag.medication_detail_back, hasTestTag(C.Tag.planning_screen))
    org.junit.Assert.assertEquals(
        selectedDay,
        ui.tag(C.Tag.planning_heading).fetchSemanticsNode().config[SemanticsProperties.Text],
    )
  }

  /** Rotation gives the agenda a full viewport without losing the selected appointment day. */
  private fun checkPlanningLandscape(title: String) {
    rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
    try {
      ui.tag(C.Tag.planning_list).performScrollToNode(hasText(title))
      ui.waitFor(hasText(title)).fullyVisible()
      compose.onNodeWithTag(C.Tag.planning_week).assertIsNotDisplayed()
      ui.tag(C.Tag.bottom_navigation_tab_overview).fullyVisible()
    } finally {
      rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
    }
  }

  /** Profile actions remain reachable after rotating the same signed-in patient's screen. */
  private fun checkProfileLandscape() {
    rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
    try {
      ui.tag(C.Tag.profile_sign_out).reveal().fullyVisible()
      compose.onNodeWithTag(C.Tag.profile_back).assertIsNotDisplayed()
      ui.tag(C.Tag.profile_edit).reveal().fullyVisible()
    } finally {
      rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
    }
  }

  private fun rotate(requested: Int, expected: Int) {
    compose.runOnUiThread { compose.activity.requestedOrientation = requested }
    compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == expected }
    compose.waitForIdle()
  }

  private fun SemanticsNodeInteraction.fullyVisible() {
    val node = fetchSemanticsNode()
    val size = node.size
    assertTrue("The control must have drawable area", size.width > 0 && size.height > 0)
    assertTrue(
        "The whole control must fit in the viewport",
        node.boundsInRoot.width >= size.width && node.boundsInRoot.height >= size.height,
    )
  }

  /** Google sign-in, then onboarding as a patient: the Overview greets them by first name. */
  private fun signInAndOnboard(firstName: String) {
    ui.signInAndOnboard(
        firstName,
        C.Tag.onboarding_role_patient,
        hasTestTag(C.Tag.overview_greeting) and hasText(firstName, substring = true),
    )
  }

  /** From the Overview: a prescription entered manually appears in the prescriptions list. */
  private fun addPrescription(doctor: String, medication: String) {
    ui.open(
        C.Tag.overview_shortcut_prescriptions,
        hasTestTag(C.Tag.prescriptions_enter_manually_button),
    )
    ui.open(
            C.Tag.prescriptions_enter_manually_button,
            hasTestTag(C.Tag.prescription_form_prescribed_by_field),
        )
        .typeText(doctor)
    ui.tag(C.Tag.prescription_form_medication_name_field).reveal().typeText(medication)
    ui.tag(C.Tag.prescription_form_dosage_field).reveal().typeText("20 mg")
    ui.tag(C.Tag.prescription_form_frequency_field).reveal().typeText("Once a day")
    // Saving returns to the list, which shows the new prescription
    ui.open(
        C.Tag.prescription_form_save_button,
        hasTestTag(C.Tag.prescriptions_list) and hasAnyDescendant(hasText(medication)),
    )
    ui.open(C.Tag.prescriptions_back_button, hasTestTag(C.Tag.overview_greeting))
  }

  /** From the Planning tab: an appointment added for tomorrow appears on that day. */
  private fun addAppointmentTomorrow(title: String) {
    ui.open(C.Tag.bottom_navigation_tab_planning, hasTestTag(C.Tag.planning_screen))

    selectTomorrowInPlanning()

    ui.open(C.Tag.planning_add, hasTestTag(C.Tag.appointment_title)).typeText(title)
    ui.tag(C.Tag.appointment_time).performClick()
    ui.tag(C.Tag.appointment_confirm).performClick()
    ui.tag(C.Tag.appointment_location).reveal().typeText("HUG, oncology")
    // Saving returns to the calendar, on the appointment's day
    ui.open(
        C.Tag.appointment_save,
        hasTestTag(C.Tag.planning_screen) and hasAnyDescendant(hasText(title)),
    )
  }

  /** The link between Planning and the Overview: the new appointment is the next one there. */
  private fun seeNextAppointmentOnOverview(title: String) {
    ui.open(
        C.Tag.bottom_navigation_tab_overview,
        hasTestTag(C.Tag.overview_next_appointment) and
            (hasText(title, substring = true) or hasAnyDescendant(hasText(title))),
    )
  }

  /**
   * The Events tab lists the Ligue's event, whose detail says where it takes place; the patient
   * confirms there that they registered with the organizer (US-25).
   */
  private fun confirmRegistrationForLigueEvent(title: String, place: String) {
    // The list only composes the cards on screen: scroll to ours first, in case earlier events
    // (e.g. left by a crashed run) push it down
    ui.open(C.Tag.bottom_navigation_tab_events, hasTestTag(C.Tag.events_list))
        .performScrollToKey(ligueEvent)
    ui.open(
        hasTestTag(C.Tag.eventCard(ligueEvent)),
        hasTestTag(C.Tag.event_detail_event_title) and hasText(title, substring = true),
    )
    ui.waitFor(hasTestTag(C.Tag.event_detail_location) and hasText(place, substring = true))
    ui.waitFor(hasTestTag(C.Tag.event_detail_confirm)).reveal().performClick()
    ui.waitFor(hasTestTag(C.Tag.event_detail_registration_confirmed))
    ui.open(C.Tag.event_detail_back, hasTestTag(C.Tag.events_list))
  }

  /**
   * The link between Events and Planning (US-30): the confirmed event shows on its day, next to the
   * patient's appointment.
   */
  private fun seeConfirmedEventInPlanning(title: String, appointment: String) {
    ui.open(C.Tag.bottom_navigation_tab_planning, hasTestTag(C.Tag.planning_screen))
    selectTomorrowInPlanning()

    ui.waitFor(
        hasTestTag(C.Tag.planning_screen) and
            hasAnyDescendant(hasText(title)) and
            hasAnyDescendant(hasText(appointment))
    )
    ui.open(C.Tag.bottom_navigation_tab_overview, hasTestTag(C.Tag.overview_greeting))
  }

  /** From the Overview: a symptom logged in a few taps appears in the journal. */
  private fun logSymptom() {
    ui.open(C.Tag.overview_shortcut_symptoms, hasTestTag(C.Tag.symptom_journal_add))
    ui.open(C.Tag.symptom_journal_add, hasTestTag(C.Tag.symptomAddType("FATIGUE"))).performClick()

    // Saving returns to the journal, with the new entry
    ui.open(
        C.Tag.symptom_add_save,
        hasTestTag(C.Tag.symptom_journal_list) and
            hasAnyDescendant(hasText(string(R.string.symptom_type_fatigue))),
    )
    ui.open(C.Tag.symptom_back_button, hasTestTag(C.Tag.overview_greeting))
  }

  /** From the journal: the symptom just logged is deleted after confirming, and is gone. */
  private fun deleteSymptom() {
    val fatigue = string(R.string.symptom_type_fatigue)
    ui.open(C.Tag.overview_shortcut_symptoms, hasText(fatigue, substring = true))
    ui.open(hasText(fatigue, substring = true), hasTestTag(C.Tag.symptom_detail_delete))
        .reveal()
        .performClick()
    // Back in the journal, which is empty again
    ui.open(C.Tag.symptom_delete_confirm, hasTestTag(C.Tag.symptom_journal_empty))
    ui.open(C.Tag.symptom_back_button, hasTestTag(C.Tag.overview_greeting))
  }

  /** The profile shows the onboarding first name; a hospital entered there is saved. */
  private fun seeProfileAndAddHospital(firstName: String, hospital: String) {
    ui.open(
        C.Tag.overview_shortcut_profile,
        hasTestTag(C.Tag.profile_name) and hasText(firstName, substring = true),
    )

    ui.open(C.Tag.profile_edit, hasTestTag(C.Tag.hospital_field)).reveal().typeText(hospital)
    // Typing remains manual: only an explicit Search may start a lookup, and Save stays usable.
    compose.onNodeWithTag(C.Tag.hospital_loading).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.hospital_results).assertDoesNotExist()
    ui.tag(C.Tag.edit_profile_save).reveal().performClick()

    ui.waitFor(
        hasTestTag(C.Tag.profile_hospital) and
            (hasText(hospital, substring = true) or hasAnyDescendant(hasText(hospital)))
    )
  }

  /**
   * From the Overview: the patient invites someone to their care circle, who then appears in it as
   * waiting to join. Joining with the invite code on the caregiver's side has no screen yet (#137).
   */
  private fun inviteToCareCircle(firstName: String) {
    // A new patient's circle is empty: its add button is in the page
    ui.open(C.Tag.overview_shortcut_care_circle, hasTestTag(C.Tag.care_circle_empty_add_button))
    ui.open(C.Tag.care_circle_empty_add_button, hasTestTag(C.Tag.care_circle_form_first_name))
        .typeText(firstName)
    ui.tag(C.Tag.care_circle_form_relationship).reveal().performClick()
    // On a short screen the menu scrolls, and an option below its visible part can't be tapped
    ui.waitFor(hasTestTag(C.Tag.careCircleFormRelationshipOption("DAUGHTER")))
        .reveal()
        .performClick()
    // Without a relationship Save does nothing, so a missed pick fails here, not at the invite code
    ui.waitFor(
        hasTestTag(C.Tag.care_circle_form_relationship) and
            hasText(string(R.string.relationship_daughter))
    )
    // Saving shows the invite code to share, in place of the form
    ui.open(C.Tag.care_circle_form_save_button, hasTestTag(C.Tag.care_circle_invite_code))

    // Back returns to the circle, where the invitation is pending
    ui.open(
        C.Tag.care_circle_invite_back_button,
        hasTestTag(C.Tag.care_circle_member_list) and
            hasAnyDescendant(hasTestTag(C.Tag.care_circle_pending_section)) and
            hasAnyDescendant(hasText(firstName, substring = true)),
    )
  }

  /** Signing out, after confirming, goes back to the sign-in screen. */
  private fun signOut() {
    ui.open(C.Tag.profile_sign_out, hasTestTag(C.Tag.profile_sign_out_confirm))
    ui.open(C.Tag.profile_sign_out_confirm, hasTestTag(C.Tag.sign_in_screen))
  }

  /**
   * A returning user: signing in again with the same account goes straight to the Overview, without
   * onboarding, and their data is still there.
   */
  private fun signInAgain(firstName: String, nextAppointment: String) {
    ui.open(
        C.Tag.google_sign_in_button,
        hasTestTag(C.Tag.overview_greeting) and hasText(firstName, substring = true),
    )
    ui.waitFor(
        hasTestTag(C.Tag.overview_next_appointment) and
            (hasText(nextAppointment, substring = true) or
                hasAnyDescendant(hasText(nextAppointment)))
    )
    // The waitFor above is the check: nothing clicks through onboarding, so reaching the greeting
    // means it was skipped
  }

  // ---- helpers ----

  /** In Planning, selects tomorrow, which can be in next week's calendar. */
  private fun selectTomorrowInPlanning() {
    val tomorrow = C.Tag.planningDay(LocalDate.now().plusDays(1).toString())
    if (!ui.exists(hasTestTag(tomorrow))) ui.tag(C.Tag.planning_next).performClick()
    ui.waitFor(hasTestTag(tomorrow)).performClick()
  }

  /** Writes a Ligue event for tomorrow at 15:00, as the Firebase console would. */
  private fun addLigueEventTomorrow(title: String) = runBlocking {
    val startAt =
        LocalDate.now().plusDays(1).atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant()
    EmulatorTestData.createRawDocument(
        "events",
        ligueEvent,
        """{"title": {"stringValue": "$title"}, "place": {"stringValue": "Ligue, Geneva"},
           "summary": {"stringValue": "A gentle class"},
           "description": {"stringValue": "Gentle yoga for patients."},
           "startAt": {"timestampValue": "$startAt"}}""",
    )
  }

  // Keep fluent journey steps while sharing all interaction behavior with M2.
  private fun SemanticsNodeInteraction.typeText(value: String) = ui.typeText(this, value)

  private fun SemanticsNodeInteraction.replaceText(value: String) = ui.replaceText(this, value)

  private fun SemanticsNodeInteraction.reveal() = ui.reveal(this)

  private fun string(id: Int) = compose.activity.getString(id)
}
