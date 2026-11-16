// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import android.provider.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.SMALL_LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.textLayout
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.buttonLabel
import com.github.se.oncompanion.ui.theme.lakeColors
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class TimePickerDialogTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val tags = PickerTestTags(picker = "picker", confirm = "confirm", cancel = "cancel")

  /** What the dialog reported, e.g. `confirm=08:05` or `dismiss`. */
  private val events = mutableListOf<String>()

  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography
  private var primaryText = Color.Unspecified

  private fun setContent(time: LocalTime = LocalTime.of(8, 5), is24Hour: Boolean = true) {
    composeTestRule.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        primaryText = MaterialTheme.lakeColors.primaryText
        TimePickerDialog(
            time = time,
            onConfirm = { events += "confirm=$it" },
            onDismiss = { events += "dismiss" },
            confirmLabel = "OK",
            cancelLabel = "Cancel",
            testTags = tags,
            is24Hour = is24Hour,
        )
      }
    }
  }

  @Test
  fun confirming_reportsTheShownTime() {
    setContent()

    composeTestRule.onNodeWithTag(tags.picker).assertExists()
    composeTestRule.onNodeWithTag(tags.confirm).performClick()

    assertEquals(listOf("confirm=08:05"), events)
  }

  @Test
  fun cancelling_dismisses() {
    setContent()

    composeTestRule.onNodeWithTag(tags.cancel).performClick()

    assertEquals(listOf("dismiss"), events)
  }

  @Test
  fun in24HourFormat_theClockShowsTheHoursUpTo23() {
    setContent(is24Hour = true)

    composeTestRule.onNodeWithContentDescription("23 hours").assertExists()
  }

  /** Shows the dialog without `is24Hour`, on a phone set to [phoneSetting] ("12" or "24"). */
  private fun setContentWithPhoneFormat(phoneSetting: String) {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, phoneSetting)
    composeTestRule.setContent {
      OncompanionTheme {
        TimePickerDialog(
            time = LocalTime.of(8, 5),
            onConfirm = {},
            onDismiss = {},
            confirmLabel = "OK",
            cancelLabel = "Cancel",
            testTags = tags,
        )
      }
    }
  }

  @Test
  fun byDefault_onA12HourPhone_theClockStopsAt12() {
    setContentWithPhoneFormat("12")

    composeTestRule.onNodeWithTag(tags.picker).assertExists()
    composeTestRule.onNodeWithContentDescription("23 hours").assertDoesNotExist()
  }

  @Test
  fun byDefault_onA24HourPhone_theClockShowsTheHoursUpTo23() {
    setContentWithPhoneFormat("24")

    composeTestRule.onNodeWithContentDescription("23 hours").assertExists()
  }

  // ----- Short windows -----

  /** The time is typed, entirely above the buttons, and the typed time is what gets confirmed. */
  private fun assertTimeIsTypedAboveTheButtons() {
    setContent()

    composeTestRule.onNodeWithContentDescription("23 hours").assertDoesNotExist()
    val picker = composeTestRule.onNodeWithTag(tags.picker).assertIsDisplayed()
    picker.assertIsFullyVisible()
    val confirm = composeTestRule.onNodeWithTag(tags.confirm).assertIsDisplayed()
    assertTrue(
        picker.fetchSemanticsNode().boundsInRoot.bottom <=
            confirm.fetchSemanticsNode().boundsInRoot.top
    )
    composeTestRule
        .onNode(hasSetTextAction() and hasContentDescription("for minutes"), useUnmergedTree = true)
        .performTextReplacement("30")
    confirm.performClick()

    assertEquals(listOf("confirm=08:30"), events)
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscape_timeIsTyped_aboveTheButtons() = assertTimeIsTypedAboveTheButtons()

  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun smallLandscape_timeIsTyped_aboveTheButtons() = assertTimeIsTypedAboveTheButtons()

  // ----- Same look as the day picker -----

  /** Fails unless [text] was laid out in [expected], in [color]. */
  private fun assertStyle(
      text: String,
      expected: androidx.compose.ui.text.TextStyle,
      color: Color,
  ) = composeTestRule.onNodeWithText(text, useUnmergedTree = true).assertTextStyle(expected, color)

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun dialog_isWhite() {
    setContent()

    // The top right of the picker, away from the clock: only the dialog's background shows there
    val picker = composeTestRule.onNodeWithTag(tags.picker)
    val width = picker.getUnclippedBoundsInRoot().let { it.right - it.left }
    assertSameColor("container", colors.surfaceContainerLowest, picker.colorAt(width - 4.dp, 4.dp))
  }

  @Test
  fun title_usesTheDayPickersStyle() {
    setContent()
    val title =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.time_picker_title)

    assertStyle(title, typography.labelMedium, colors.onSurfaceVariant)
  }

  /** Fails unless the [hour] and [minutes] of 08:05 are laid out in Display Small. */
  private fun assertTimeIsDisplaySmall(hour: SemanticsMatcher, minutes: SemanticsMatcher) {
    setContent()

    for (part in listOf(hour, minutes)) {
      val style =
          composeTestRule
              .onNode(part and hasAnyAncestor(hasTestTag(tags.picker)), useUnmergedTree = true)
              .textLayout()
              .layoutInput
              .style
      assertEquals("${part.description} size", typography.displaySmall.fontSize, style.fontSize)
      assertEquals(
          "${part.description} weight",
          typography.displaySmall.fontWeight,
          style.fontWeight,
      )
    }
  }

  @Test
  fun onTheClock_theTimeIsDisplaySmall() =
      assertTimeIsDisplaySmall(hour = hasText("08"), minutes = hasText("05"))

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun typed_theTimeIsDisplaySmall() =
      assertTimeIsDisplaySmall(
          hour = hasSetTextAction() and hasContentDescription("for hour"),
          minutes = hasSetTextAction() and hasContentDescription("for minutes"),
      )

  @Test
  fun buttons_are56dpTall_withTheButtonLabelStyle() {
    setContent()

    composeTestRule.onNodeWithTag(tags.confirm).assertHeightIsEqualTo(56.dp)
    composeTestRule.onNodeWithTag(tags.cancel).assertHeightIsEqualTo(56.dp)
    assertStyle("OK", typography.buttonLabel, primaryText)
    assertStyle("Cancel", typography.buttonLabel, primaryText)
  }
}
