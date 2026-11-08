// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.domain.auth.SignOutState
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.GoogleCredentialProvider
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.utils.EmulatorGoogleSignIn
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2's two-account journey through real screens and Firebase emulators. Only Google's account
 * picker is replaced. #360 extends this same journey with revocation, reusing its accounts, sign-in
 * helpers and cleanup rather than relying on another test running first.
 */
@RunWith(AndroidJUnit4::class)
class M2EndToEndTest {
  @get:Rule val compose = createAndroidComposeRule<AppHostActivity>()

  private val run = UUID.randomUUID().toString()
  private val patient = Account("patient", "Sam")
  private val caregiver = Account("caregiver", "Julie")
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
   * Checks invitation, joining, shared reads, caregiver creation and patient-visible attribution.
   */
  @Test
  fun patientAndCaregiver_shareAndLogSymptoms() {
    startApp()
    signInAndOnboard(patient, C.Tag.onboarding_role_patient, patientGreeting())
    openSymptoms()
    logSymptom("FATIGUE", patientNotes)
    open(C.Tag.symptom_back_button, patientGreeting())
    inviteCaregiver()
    open(C.Tag.care_circle_back_button, patientGreeting())
    signOutThroughProfile()

    credentials.select(caregiver)
    signInAndOnboard(
        caregiver,
        C.Tag.onboarding_role_caregiver,
        hasTestTag(C.Tag.join_circle_screen),
    )
    assertNotEquals("Each person must have their own account", patient.uid, caregiver.uid)
    joinCareCircle()
    openSymptoms()
    assertSymptom("FATIGUE", patientNotes)
    tag(C.Tag.symptom_journal_add).assertIsDisplayed().assertIsEnabled()
    logSymptom("NAUSEA", caregiverNotes)
    assertSymptom("FATIGUE", patientNotes)
    open(C.Tag.symptom_back_button, caregiverTitle())
    signOutThroughProfile()

    credentials.select(patient)
    open(C.Tag.google_sign_in_button, patientGreeting())
    assertEquals(patient.uid, FirebaseEmulator.auth.currentUser?.uid)
    openSymptoms()
    assertSymptom("FATIGUE", patientNotes)
    val addedBy = compose.activity.getString(R.string.symptom_added_by, caregiver.firstName)
    val caregiverRow = symptomRow(caregiverNotes)
    tag(C.Tag.symptom_journal_list).performScrollToNode(caregiverRow)
    waitFor(caregiverRow and text(addedBy)).assertIsDisplayed()
    // Detail proves the full notes and attribution belong to this exact saved entry.
    open(caregiverRow, hasTestTag(C.Tag.symptom_detail_content))
    waitFor(hasTestTag(C.Tag.symptom_detail_notes) and text(caregiverNotes)).assertIsDisplayed()
    waitFor(hasTestTag(C.Tag.added_by) and hasText(addedBy), unmerged = true).assertIsDisplayed()
    open(C.Tag.symptom_back_button, hasTestTag(C.Tag.symptom_journal_list))
    open(C.Tag.symptom_back_button, patientGreeting())
    assertAcceptedMember()
    // #360 appends revocation here: patient signed in, same circle and cleanup still active.
  }

  private fun startApp() {
    compose.runOnUiThread {
      AppHostActivity.content.value = {
        OncompanionTheme { AppNavHost(credentialProvider = credentials) }
      }
    }
  }

  private fun signInAndOnboard(account: Account, roleTag: String, destination: SemanticsMatcher) {
    open(C.Tag.google_sign_in_button, hasTestTag(roleTag)).performClick()
    checkNotNull(account.uid) { "The new account wasn't recorded" }
    open(C.Tag.onboarding_continue_button, hasTestTag(C.Tag.onboarding_first_name_field))
        .replaceText(account.firstName)
    open(C.Tag.onboarding_save_button, destination)
  }

  private fun openSymptoms() =
      open(C.Tag.overview_shortcut_symptoms, hasTestTag(C.Tag.symptom_journal_add))

  private fun logSymptom(type: String, notes: String) {
    open(C.Tag.symptom_journal_add, hasTestTag(C.Tag.symptomAddType(type))).performClick()
    tag(C.Tag.symptom_add_notes).reveal().replaceText(notes)
    open(C.Tag.symptom_add_save, hasTestTag(C.Tag.symptom_journal_list))
    assertSymptom(type, notes)
  }

  private fun assertSymptom(type: String, notes: String) {
    val title =
        compose.activity.getString(
            if (type == "FATIGUE") R.string.symptom_type_fatigue else R.string.symptom_type_nausea
        )
    val row = symptomRow(notes)
    tag(C.Tag.symptom_journal_list).performScrollToNode(row)
    waitFor(row and text(title)).assertIsDisplayed()
  }

  private fun inviteCaregiver() {
    open(C.Tag.overview_shortcut_care_circle, hasTestTag(C.Tag.care_circle_empty_add_button))
    open(C.Tag.care_circle_empty_add_button, hasTestTag(C.Tag.care_circle_form_first_name))
        .replaceText(caregiver.firstName)
    tag(C.Tag.care_circle_form_relationship).reveal().performClick()
    tag(C.Tag.careCircleFormRelationshipOption("DAUGHTER")).reveal().performClick()
    waitFor(
        hasTestTag(C.Tag.care_circle_form_relationship) and
            text(compose.activity.getString(R.string.relationship_daughter))
    )
    val view = C.Tag.careCircleFormPermission("SYMPTOMS")
    val add = C.Tag.careCircleFormCanAdd("SYMPTOMS")
    tag(view).reveal().assertIsOff().performClick()
    tag(view).assertIsOn()
    tag(add).reveal().assertIsEnabled().assertIsOff().performClick()
    tag(add).assertIsOn()
    open(C.Tag.care_circle_form_save_button, hasTestTag(C.Tag.care_circle_invite_code))
    val displayed =
        tag(C.Tag.care_circle_invite_code)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()
            .text
    inviteCode = CareCircleInvite.normalizeInviteCode(displayed)
    assertTrue(
        "The displayed invitation must contain a usable code",
        CareCircleInvite.isWellFormedInviteCode(inviteCode),
    )
    open(C.Tag.care_circle_invite_back_button, hasTestTag(C.Tag.careCircleInvite(inviteCode)))
    waitFor(hasTestTag(C.Tag.care_circle_pending_label), unmerged = true).assertIsDisplayed()
  }

  private fun joinCareCircle() {
    tag(C.Tag.join_circle_code_field).reveal().replaceText(inviteCode)
    open(
        C.Tag.join_circle_join_button,
        hasTestTag(C.Tag.join_circle_joined) and text(patient.firstName),
    )
    open(C.Tag.join_circle_continue, caregiverTitle())
  }

  private fun assertAcceptedMember() {
    val member = C.Tag.careCircleMember(checkNotNull(caregiver.uid))
    open(C.Tag.overview_shortcut_care_circle, hasTestTag(C.Tag.care_circle_member_list))
    tag(C.Tag.care_circle_member_list).performScrollToNode(hasTestTag(member))
    waitFor(hasTestTag(member) and text(caregiver.firstName)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.careCircleInvite(inviteCode)).assertDoesNotExist()
    compose
        .onNodeWithTag(C.Tag.care_circle_pending_label, useUnmergedTree = true)
        .assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.care_circle_pending_section).assertDoesNotExist()
  }

  private fun signOutThroughProfile() {
    open(C.Tag.overview_shortcut_profile, hasTestTag(C.Tag.profile_sign_out))
    open(C.Tag.profile_sign_out, hasTestTag(C.Tag.profile_sign_out_confirm))
    open(C.Tag.profile_sign_out_confirm, hasTestTag(C.Tag.sign_in_screen))
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

  private fun tag(value: String) = waitFor(hasTestTag(value))

  private fun open(from: String, destination: SemanticsMatcher) =
      open(hasTestTag(from), destination)

  private fun open(
      from: SemanticsMatcher,
      destination: SemanticsMatcher,
  ): SemanticsNodeInteraction {
    waitFor(from)
    compose.waitUntil(TIMEOUT_MS) { !exists(loading) }
    compose.waitForIdle()
    waitFor(from).reveal().assertIsDisplayed().assertIsEnabled().performClick()
    return waitFor(destination)
  }

  private fun SemanticsNodeInteraction.replaceText(value: String) {
    performTextReplacement(value)
    Espresso.closeSoftKeyboard()
    compose.waitForIdle()
  }

  private fun SemanticsNodeInteraction.reveal() = apply {
    try {
      performScrollTo()
    } catch (error: AssertionError) {
      if (error.message?.contains("has no parent layout with a Scroll SemanticsAction") != true)
          throw error
    }
  }

  private fun exists(matcher: SemanticsMatcher, unmerged: Boolean = false) =
      try {
        compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
      } catch (error: IllegalStateException) {
        if (error.message?.startsWith("No compose hierarchies found") != true) throw error
        false
      }

  private fun waitFor(
      matcher: SemanticsMatcher,
      unmerged: Boolean = false,
  ): SemanticsNodeInteraction {
    try {
      compose.waitUntil(TIMEOUT_MS) { exists(matcher, unmerged) }
    } catch (error: ComposeTimeoutException) {
      val screen =
          try {
            compose
                .onAllNodes(isRoot(), useUnmergedTree = true)
                .printToString(maxDepth = Int.MAX_VALUE)
          } catch (_: IllegalStateException) {
            "No Compose hierarchy available."
          }
      // Diagnostics must not put invitation codes or account emails into CI logs.
      var diagnostic = "M2 didn't reach ${matcher.description}.\n$screen"
      for (account in listOf(patient, caregiver)) diagnostic =
          diagnostic.replace(account.email, "<test email>")
      if (::inviteCode.isInitialized) {
        diagnostic =
            diagnostic
                .replace(inviteCode, "<invite code>")
                .replace(inviteCode.chunked(5).joinToString("-"), "<invite code>")
      }
      throw AssertionError(diagnostic, error)
    }
    return compose.onNode(matcher, useUnmergedTree = unmerged)
  }

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

  private companion object {
    const val TIMEOUT_MS = 15_000L
    val loading =
        SemanticsMatcher("a loading screen") {
          it.config.getOrNull(SemanticsProperties.TestTag)?.endsWith("_loading") == true
        }
  }
}
