// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.profile

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.SMALL_LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsAboveKeyboard
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.SavePhase
import com.github.se.oncompanion.ui.showKeyboard
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The date of birth of Edit profile (#218), typed or picked. In British English: DD/MM/YYYY. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rGB")
class EditProfileDateOfBirthTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val today = LocalDate.of(2026, 11, 1)
  private val born = LocalDate.of(1968, 3, 12)
  private var state by
      mutableStateOf(EditProfileUiState(EditProfileStatus.READY, "Alex", today = today))

  /** What the field reported: the typed or picked days, and `unreadable` for unreadable text. */
  private val events = mutableListOf<String>()

  private fun content(initial: EditProfileUiState = state) {
    state = initial
    compose.setContent {
      OncompanionTheme {
        EditProfileContent(
            state,
            onBack = {},
            onRetry = {},
            onFirstNameChange = {},
            onFamilyNameChange = {},
            onDateOfBirthChange = {
              events += "$it"
              state = state.copy(dateOfBirth = it, dateOfBirthTyped = null)
            },
            onDateOfBirthTyped = { digits, day ->
              events += if (digits.isNotEmpty() && day == null) "unreadable" else "$day"
              state = state.copy(dateOfBirth = day, dateOfBirthTyped = digits)
            },
            onCancerTypeChange = {},
            onHospitalChange = {},
            onSave = {},
        )
      }
    }
  }

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  private fun field() = node(C.Tag.edit_profile_date_of_birth).performScrollTo()

  @Test
  fun empty_showsTheLabelAndOptional() {
    content()
    field().assertTextContains("Date of birth").assertTextContains("Optional")
    node(C.Tag.edit_profile_date_of_birth_pick).assertIsEnabled()
    node(C.Tag.edit_profile_save).assertIsEnabled()
  }

  @Test
  fun set_showsTheDayInThePhonesFormat() {
    content(state.copy(dateOfBirth = born))
    field().assertTextContains("12/03/1968")
  }

  /** Typing the 8 digits sets the day; the separators appear on their own. */
  @Test
  fun typing_setsTheDay_andEmptyingClearsIt() {
    content()
    field().performTextInput("12031968")
    field().assertTextContains("12/03/1968")
    compose.runOnIdle { assertEquals("$born", events.last()) }

    field().performTextClearance()
    compose.runOnIdle { assertEquals("null", events.last()) }
    field().assertTextContains("Optional")
  }

  /**
   * What isn't a whole, real day is reported as unreadable: while still typed, the helper shows the
   * format to follow; once all the digits are there (31/02 is no day), the error says it.
   */
  @Test
  fun unreadable_showsTheErrorOnceComplete_andDisablesSave() {
    content()
    field().performTextInput("3102")
    compose.runOnIdle { assertEquals("unreadable", events.last()) }
    field().assertTextContains("DD/MM/YYYY")
    node(C.Tag.edit_profile_save).performScrollTo().assertIsNotEnabled()

    field().performTextInput("1980")
    field().assertTextContains("31/02/1980").assertTextContains("Enter the date as DD/MM/YYYY")
  }

  /** An unfinished date shows the error once the user moves to another field. */
  @Test
  fun unfinished_showsTheErrorWhenLeavingTheField() {
    content()
    field().performTextInput("31")
    node(C.Tag.edit_profile_first_name).performScrollTo().requestFocus()
    field().assertTextContains("Enter the date as DD/MM/YYYY")
  }

  /** The calendar button opens the picker on the date or on today; OK sets it, Cancel keeps it. */
  @Test
  fun calendarButton_opensThePickerOnTheDateOrToday_okSetsIt_cancelKeepsIt() {
    content()
    for ((saved, expected) in listOf(born to born, null to today)) {
      compose.runOnIdle {
        events.clear()
        state = state.copy(dateOfBirth = saved)
      }
      node(C.Tag.edit_profile_date_of_birth_pick).performScrollTo().performClick()
      node(C.Tag.edit_profile_date_of_birth_cancel).performClick()
      node(C.Tag.edit_profile_date_of_birth_picker).assertDoesNotExist()
      compose.runOnIdle { assertEquals(emptyList<String>(), events) }

      node(C.Tag.edit_profile_date_of_birth_pick).performScrollTo().performClick()
      node(C.Tag.edit_profile_date_of_birth_confirm).performClick()
      compose.runOnIdle { assertEquals(listOf("$expected"), events) }
      // The picked day replaces what was typed
      field().assertTextContains(if (expected == born) "12/03/1968" else "01/11/2026")
    }
  }

  @Test
  fun future_showsTheErrorAndDisablesSave_andThePickerOpensOnToday() {
    content()
    field().performTextInput("02112026")
    field().assertTextContains("Date of birth can't be in the future")
    node(C.Tag.edit_profile_save).performScrollTo().assertIsNotEnabled()
    node(C.Tag.edit_profile_date_of_birth_pick).performScrollTo().performClick()
    node(C.Tag.edit_profile_date_of_birth_confirm).performClick()
    compose.runOnIdle { assertEquals("$today", events.last()) }
  }

  @Test
  fun whileSaving_theFieldAndTheCalendarButtonAreDisabled() {
    content(state.copy(dateOfBirth = born, save = SavePhase.SAVING))
    field().assertIsNotEnabled()
    node(C.Tag.edit_profile_date_of_birth_pick).assertIsNotEnabled()
  }

  // ----- Landscape and keyboard -----

  @Test
  @Config(qualifiers = "+$LANDSCAPE_PHONE")
  fun landscape_theFieldAndSaveAreFullyVisible() {
    content(state.copy(dateOfBirth = born))
    field().assertIsFullyVisible()
    node(C.Tag.edit_profile_date_of_birth_pick).assertIsFullyVisible()
    node(C.Tag.edit_profile_save).performScrollTo().assertIsFullyVisible()
  }

  /** Typing it with the keyboard open: the field stays above the keyboard, upright and sideways. */
  private fun assertTypedFieldIsAboveTheKeyboard(keyboardPx: Int) {
    content()
    field().performClick()
    compose.showKeyboard(keyboardPx)
    field().assertIsAboveKeyboard(keyboardPx)
    node(C.Tag.edit_profile_save).performScrollTo().assertIsAboveKeyboard(keyboardPx)
  }

  @Test fun portrait_typing_staysAboveTheKeyboard() = assertTypedFieldIsAboveTheKeyboard(300)

  @Test
  @Config(qualifiers = "+$LANDSCAPE_PHONE")
  fun landscape_typing_staysAboveTheKeyboard() = assertTypedFieldIsAboveTheKeyboard(500)

  @Test
  @Config(qualifiers = "+$SMALL_LANDSCAPE_PHONE")
  fun smallLandscape_thePickerButtonsAreFullyVisible() {
    content()
    node(C.Tag.edit_profile_date_of_birth_pick).performScrollTo().performClick()
    node(C.Tag.edit_profile_date_of_birth_confirm).assertIsFullyVisible()
    node(C.Tag.edit_profile_date_of_birth_cancel).assertIsFullyVisible()
  }

  // ----- With the ViewModel -----

  private fun model(): Pair<EditProfileViewModel, FakeUserProfileRepository> {
    val auth = FakeAuthRepository { AuthUser("uid-1") }
    runBlocking { auth.signInWithGoogle("token") }
    val profiles =
        FakeUserProfileRepository().apply { seed(UserProfile("uid-1", Role.PATIENT, "Alex")) }
    // 1 Nov 2026 on the phone
    val clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneId.of("Europe/Zurich"))
    return EditProfileViewModel(auth, profiles, clock) to profiles
  }

  @Test
  fun screen_typingAndSaving_storesTheDate() {
    val (vm, profiles) = model()
    var saved = 0
    compose.setContent { EditProfileScreen(onBack = {}, onSaved = { saved++ }, viewModel = vm) }
    compose.waitUntil(5000) { vm.uiState.value.status == EditProfileStatus.READY }
    field().performTextInput("12031968")
    node(C.Tag.edit_profile_save).performScrollTo().performClick()
    compose.waitUntil(5000) { saved == 1 }
    compose.runOnIdle { assertEquals(born, profiles.profiles["uid-1"]!!.dateOfBirth) }
  }

  /** A future date saved by another phone shows the error, and the picker opens on today. */
  @Test
  fun screen_aFutureDateFromTheProfile_showsTheError_andThePickerOpensOnToday() {
    val (vm, profiles) = model()
    profiles.seed(UserProfile("uid-1", Role.PATIENT, "Alex", dateOfBirth = today.plusDays(1)))
    compose.setContent { EditProfileScreen(onBack = {}, onSaved = {}, viewModel = vm) }
    compose.waitUntil(5000) { vm.uiState.value.status == EditProfileStatus.READY }
    field()
        .assertTextContains("02/11/2026")
        .assertTextContains("Date of birth can't be in the future")
    node(C.Tag.edit_profile_save).performScrollTo().assertIsNotEnabled()
    node(C.Tag.edit_profile_date_of_birth_pick).performScrollTo().performClick()
    node(C.Tag.edit_profile_date_of_birth_confirm).performClick()
    compose.runOnIdle { assertEquals(today, vm.uiState.value.dateOfBirth) }
    node(C.Tag.edit_profile_save).performScrollTo().assertIsEnabled()
  }

  /** What is half typed, and the open picker, survive the activity being recreated. */
  @Test
  fun rotation_keepsTheTypedDigitsAndTheOpenPicker() {
    val (vm, _) = model()
    val screen = @Composable { EditProfileScreen(onBack = {}, onSaved = {}, viewModel = vm) }
    compose.setContent(screen)
    compose.waitUntil(5000) { vm.uiState.value.status == EditProfileStatus.READY }
    field().performTextInput("1203")

    compose.activityRule.scenario.recreate()
    compose.activity.setContent(content = screen)
    field().assertTextContains("12/03")
    compose.runOnIdle { assertEquals(true, vm.uiState.value.dateOfBirthUnreadable) }
    // After the recreation the cursor is at the start, so the date is typed again whole
    field().performTextReplacement("12031968")
    compose.runOnIdle { assertEquals(born, vm.uiState.value.dateOfBirth) }

    node(C.Tag.edit_profile_date_of_birth_pick).performScrollTo().performClick()
    compose.activityRule.scenario.recreate()
    compose.activity.setContent(content = screen)
    node(C.Tag.edit_profile_date_of_birth_confirm).performClick()
    compose.runOnIdle { assertEquals(born, vm.uiState.value.dateOfBirth) }
    node(C.Tag.edit_profile_date_of_birth_picker).assertDoesNotExist()
  }
}
