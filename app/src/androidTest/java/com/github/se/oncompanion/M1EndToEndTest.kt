// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.GoogleCredentialProvider
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M1 end-to-end test: a new patient goes through the whole app like a real user, against the
 * Firebase emulators. It signs in, goes through onboarding, then uses every main feature, and
 * checks the links between them: an appointment added in Planning shows on the Overview, a Ligue
 * event shows in Planning and in Events, the first name given at onboarding shows on the Overview
 * and the profile, and signing in again finds everything where it was.
 *
 * Only Google's account picker is replaced, as a test can't drive it: [EmulatorGoogleSignIn]
 * returns a token the Auth emulator accepts, for a new account on every run.
 */
@RunWith(AndroidJUnit4::class)
class M1EndToEndTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
    EmulatorTestData.signOut()
    runCatching { EmulatorTestData.deleteRawDocument("events/$ligueEvent") }
  }

  @Test
  fun newPatient_usesEveryMainFeature() {
    addLigueEventTomorrow(title = "Gentle yoga")
    compose.setContent { OncompanionTheme { AppNavHost(credentialProvider = signIn) } }

    signInAndOnboard(firstName = "Sam")
    addPrescription(doctor = "Dr Martin", medication = "Tamoxifen")
    addAppointmentTomorrow(title = "Oncology check-up")
    seeLigueEventInPlanning(title = "Gentle yoga")
    seeNextAppointmentOnOverview(title = "Oncology check-up")
    seeLigueEventInEvents(title = "Gentle yoga", place = "Ligue, Geneva")
    logSymptom()
    deleteSymptom()
    seeProfileAndAddHospital(firstName = "Sam", hospital = "HUG Geneva")
    signOut()
    signInAgain(firstName = "Sam", nextAppointment = "Oncology check-up")
  }

  @Test
  fun newPatient_invitesSomeoneToTheirCareCircle() {
    compose.setContent { OncompanionTheme { AppNavHost(credentialProvider = signIn) } }

    signInAndOnboard(firstName = "Sam")
    inviteToCareCircle(firstName = "Julie")
  }

  // ---- steps ----

  /** Google sign-in, then onboarding as a patient: the Overview greets them by first name. */
  private fun signInAndOnboard(firstName: String) {
    waitFor(hasTestTag(C.Tag.google_sign_in_button)).performClick()

    // A new account goes to onboarding
    waitFor(hasTestTag(C.Tag.onboarding_role_patient)).performClick()
    tag(C.Tag.onboarding_continue_button).performClick()
    waitFor(hasTestTag(C.Tag.onboarding_first_name_field)).performTextReplacement(firstName)
    tag(C.Tag.onboarding_save_button).reveal().performClick()

    waitFor(hasTestTag(C.Tag.overview_greeting) and hasText(firstName, substring = true))
  }

  /** From the Overview: a prescription entered manually appears in the prescriptions list. */
  private fun addPrescription(doctor: String, medication: String) {
    tag(C.Tag.overview_shortcut_prescriptions).reveal().performClick()
    waitFor(hasTestTag(C.Tag.prescriptions_enter_manually_button)).performClick()

    waitFor(hasTestTag(C.Tag.prescription_form_prescribed_by_field)).performTextInput(doctor)
    tag(C.Tag.prescription_form_medication_name_field).reveal().performTextInput(medication)
    tag(C.Tag.prescription_form_dosage_field).reveal().performTextInput("20 mg")
    tag(C.Tag.prescription_form_frequency_field).reveal().performTextInput("Once a day")
    // Save stays at the bottom of the screen, outside the scrolling fields
    tag(C.Tag.prescription_form_save_button).performClick()

    // Saving returns to the list, which shows the new prescription
    waitFor(hasTestTag(C.Tag.prescriptions_list) and hasAnyDescendant(hasText(medication)))
    tag(C.Tag.prescriptions_back_button).performClick()
    waitFor(hasTestTag(C.Tag.overview_greeting))
  }

  /** From the Planning tab: an appointment added for tomorrow appears on that day. */
  private fun addAppointmentTomorrow(title: String) {
    tag(C.Tag.bottom_navigation_tab_planning).performClick()
    waitFor(hasTestTag(C.Tag.planning_screen))

    // Tomorrow can be in next week's calendar
    val tomorrow = C.Tag.planningDay(LocalDate.now().plusDays(1).toString())
    if (!exists(hasTestTag(tomorrow))) tag(C.Tag.planning_next).performClick()
    waitFor(hasTestTag(tomorrow)).performClick()

    tag(C.Tag.planning_add).performClick()
    waitFor(hasTestTag(C.Tag.appointment_title)).performTextInput(title)
    tag(C.Tag.appointment_time).performClick()
    tag(C.Tag.appointment_confirm).performClick()
    tag(C.Tag.appointment_location).reveal().performTextInput("HUG, oncology")
    tag(C.Tag.appointment_save).reveal().performClick()

    // Saving returns to the calendar, on the appointment's day
    waitFor(hasTestTag(C.Tag.planning_screen) and hasAnyDescendant(hasText(title)))
  }

  /** The link between Events and Planning: the Ligue's event shows on its day, next to ours. */
  private fun seeLigueEventInPlanning(title: String) {
    waitFor(hasTestTag(C.Tag.planning_screen) and hasAnyDescendant(hasText(title)))
  }

  /** The link between Planning and the Overview: the new appointment is the next one there. */
  private fun seeNextAppointmentOnOverview(title: String) {
    tag(C.Tag.bottom_navigation_tab_overview).performClick()
    waitFor(
        hasTestTag(C.Tag.overview_next_appointment) and
            (hasText(title, substring = true) or hasAnyDescendant(hasText(title)))
    )
  }

  /** The Events tab lists the Ligue's event, whose detail says where it takes place. */
  private fun seeLigueEventInEvents(title: String, place: String) {
    tag(C.Tag.bottom_navigation_tab_events).performClick()
    // The list only composes the cards on screen: scroll to ours first, in case earlier events
    // (e.g. left by a crashed run) push it down
    waitFor(hasTestTag(C.Tag.events_list)).performScrollToKey(ligueEvent)
    waitFor(hasTestTag(C.Tag.eventCard(ligueEvent))).performClick()

    waitFor(hasTestTag(C.Tag.event_detail_event_title) and hasText(title, substring = true))
    waitFor(hasTestTag(C.Tag.event_detail_location) and hasText(place, substring = true))
    tag(C.Tag.event_detail_back).performClick()

    tag(C.Tag.bottom_navigation_tab_overview).performClick()
    waitFor(hasTestTag(C.Tag.overview_greeting))
  }

  /** From the Overview: a symptom logged in a few taps appears in the journal. */
  private fun logSymptom() {
    tag(C.Tag.overview_shortcut_symptoms).reveal().performClick()
    waitFor(hasTestTag(C.Tag.symptom_journal_add)).performClick()

    waitFor(hasTestTag(C.Tag.symptomAddType("FATIGUE"))).performClick()
    tag(C.Tag.symptom_add_save).performClick()

    // Saving returns to the journal, with the new entry
    waitFor(
        hasTestTag(C.Tag.symptom_journal_list) and
            hasAnyDescendant(hasText(string(R.string.symptom_type_fatigue)))
    )
    tag(C.Tag.symptom_back_button).performClick()
    waitFor(hasTestTag(C.Tag.overview_greeting))
  }

  /** From the journal: the symptom just logged is deleted after confirming, and is gone. */
  private fun deleteSymptom() {
    tag(C.Tag.overview_shortcut_symptoms).reveal().performClick()
    val fatigue = string(R.string.symptom_type_fatigue)
    waitFor(hasText(fatigue, substring = true)).performClick()

    waitFor(hasTestTag(C.Tag.symptom_detail_delete)).reveal().performClick()
    waitFor(hasTestTag(C.Tag.symptom_delete_confirm)).performClick()

    // Back in the journal, which is empty again
    waitFor(hasTestTag(C.Tag.symptom_journal_empty))
    tag(C.Tag.symptom_back_button).performClick()
    waitFor(hasTestTag(C.Tag.overview_greeting))
  }

  /** The profile shows the onboarding first name; a hospital entered there is saved. */
  private fun seeProfileAndAddHospital(firstName: String, hospital: String) {
    tag(C.Tag.overview_shortcut_profile).reveal().performClick()
    waitFor(hasTestTag(C.Tag.profile_name) and hasText(firstName, substring = true))

    tag(C.Tag.profile_edit).performClick()
    waitFor(hasTestTag(C.Tag.hospital_field)).reveal().performTextInput(hospital)
    tag(C.Tag.edit_profile_save).reveal().performClick()

    waitFor(
        hasTestTag(C.Tag.profile_hospital) and
            (hasText(hospital, substring = true) or hasAnyDescendant(hasText(hospital)))
    )
  }

  /**
   * From the Overview: the patient invites someone to their care circle, who then appears in it as
   * waiting to join. Joining with the invite code on the caregiver's side has no screen yet (#137).
   */
  private fun inviteToCareCircle(firstName: String) {
    tag(C.Tag.overview_shortcut_care_circle).reveal().performClick()
    // A new patient's circle is empty: its add button is in the page
    waitFor(hasTestTag(C.Tag.care_circle_empty_add_button)).performClick()

    waitFor(hasTestTag(C.Tag.care_circle_form_first_name)).performTextInput(firstName)
    tag(C.Tag.care_circle_form_relationship).reveal().performClick()
    waitFor(hasTestTag(C.Tag.careCircleFormRelationshipOption("DAUGHTER"))).performClick()
    tag(C.Tag.care_circle_form_save_button).reveal().performClick()

    // Saving returns to the circle, where the invitation is pending
    waitFor(
        hasTestTag(C.Tag.care_circle_member_list) and
            hasAnyDescendant(hasTestTag(C.Tag.care_circle_pending_section)) and
            hasAnyDescendant(hasText(firstName, substring = true))
    )
  }

  /** Signing out, after confirming, goes back to the sign-in screen. */
  private fun signOut() {
    tag(C.Tag.profile_sign_out).reveal().performClick()
    waitFor(hasTestTag(C.Tag.profile_sign_out_confirm)).performClick()
    waitFor(hasTestTag(C.Tag.sign_in_screen))
  }

  /**
   * A returning user: signing in again with the same account goes straight to the Overview, without
   * onboarding, and their data is still there.
   */
  private fun signInAgain(firstName: String, nextAppointment: String) {
    waitFor(hasTestTag(C.Tag.google_sign_in_button)).performClick()

    waitFor(hasTestTag(C.Tag.overview_greeting) and hasText(firstName, substring = true))
    waitFor(
        hasTestTag(C.Tag.overview_next_appointment) and
            (hasText(nextAppointment, substring = true) or
                hasAnyDescendant(hasText(nextAppointment)))
    )
    // The waitFor above is the check: nothing clicks through onboarding, so reaching the greeting
    // means it was skipped
  }

  // ---- helpers ----

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

  private fun tag(testTag: String) = compose.onNodeWithTag(testTag)

  /**
   * Scrolls to the node when it's in a scrolling area, which depends on the screen's height. Only
   * the "no scrolling parent" failure is ignored; any other scroll failure still fails the test.
   */
  private fun SemanticsNodeInteraction.reveal() = apply {
    try {
      performScrollTo()
    } catch (e: AssertionError) {
      // Not in a scrolling area: the node is already where it can be clicked
    }
  }

  private fun string(id: Int) = compose.activity.getString(id)

  /**
   * Whether a node matches. While the app is covered (a system dialog on a slow emulator), there is
   * no Compose hierarchy to search: that is "not there yet", so [waitFor] keeps waiting.
   */
  private fun exists(matcher: SemanticsMatcher) =
      try {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
      } catch (e: IllegalStateException) {
        if (e.message?.startsWith("No compose hierarchies found") != true) throw e
        false
      }

  /** Waits for the node (data comes from the emulators asynchronously), then returns it. */
  private fun waitFor(matcher: SemanticsMatcher) = compose.run {
    waitUntil(TIMEOUT_MS) { exists(matcher) }
    onNode(matcher)
  }

  /** Signs in against the Auth emulator with [idToken] instead of Google's account picker. */
  private class EmulatorGoogleSignIn(private val idToken: String) : GoogleCredentialProvider {
    override suspend fun getGoogleIdToken(context: Context) = idToken

    override suspend fun clearCredentialState(context: Context) = Unit
  }

  private companion object {
    const val TIMEOUT_MS = 15_000L
  }
}
