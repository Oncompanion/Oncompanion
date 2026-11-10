// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextStyle
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
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.buttonLabel
import com.github.se.oncompanion.ui.theme.lakeColors
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class DayPickerDialogTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val tags = PickerTestTags(picker = "picker", confirm = "confirm", cancel = "cancel")

  /** What the dialog reported, e.g. `confirm=2026-10-03` or `dismiss`. */
  private val events = mutableListOf<String>()

  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography
  private var primaryText = Color.Unspecified

  private fun setContent(date: LocalDate) {
    composeTestRule.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        primaryText = MaterialTheme.lakeColors.primaryText
        DayPickerDialog(
            date = date,
            onConfirm = { events += "confirm=$it" },
            onDismiss = { events += "dismiss" },
            confirmLabel = "OK",
            cancelLabel = "Cancel",
            testTags = tags,
            latestDate = LocalDate.of(2026, 10, 5),
        )
      }
    }
  }

  @Test
  fun confirming_reportsTheShownDay() {
    setContent(LocalDate.of(2026, 10, 3))

    composeTestRule.onNodeWithTag(tags.picker).assertExists()
    composeTestRule.onNodeWithTag(tags.confirm).performClick()

    assertEquals(listOf("confirm=2026-10-03"), events)
  }

  @Test
  fun cancelling_dismisses() {
    setContent(LocalDate.of(2026, 10, 3))

    composeTestRule.onNodeWithTag(tags.cancel).performClick()

    assertEquals(listOf("dismiss"), events)
  }

  // ----- Short windows -----

  /**
   * A swipe up brings the last day of the month entirely above the buttons: on a short window the
   * picker scrolls rather than squeezing its rows onto each other and the buttons.
   */
  private fun assertLastDayCanBeSwipedToAboveTheButtons() {
    setContent(LocalDate.of(2026, 10, 3))

    composeTestRule.onNodeWithTag(tags.picker).performTouchInput { swipeUp() }

    val lastDay = composeTestRule.onNodeWithText("October 31, 2026", substring = true)
    lastDay.assertIsFullyVisible()
    val buttonsTop =
        composeTestRule.onNodeWithTag(tags.confirm).fetchSemanticsNode().boundsInRoot.top
    assertTrue(lastDay.fetchSemanticsNode().boundsInRoot.bottom <= buttonsTop)
    composeTestRule.onNodeWithTag(tags.cancel).assertIsDisplayed()
  }

  @Test fun portrait_lastDayIsAboveTheButtons() = assertLastDayCanBeSwipedToAboveTheButtons()

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscape_lastDayCanBeSwipedTo_aboveTheButtons() = assertLastDayCanBeSwipedToAboveTheButtons()

  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun smallLandscape_lastDayCanBeSwipedTo_aboveTheButtons() =
      assertLastDayCanBeSwipedToAboveTheButtons()

  // ----- Figma: "Log symptoms – Date picker" -----

  /** Fails unless [text] was laid out in [expected], in [color]. */
  private fun assertStyle(text: String, expected: TextStyle, color: Color) =
      composeTestRule.onNodeWithText(text, useUnmergedTree = true).assertTextStyle(expected, color)

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun dialog_isWhite() {
    setContent(LocalDate.of(2026, 10, 3))

    // The top right of the picker, right of its title: only the dialog's background shows there
    val picker = composeTestRule.onNodeWithTag(tags.picker)
    val width = picker.getUnclippedBoundsInRoot().let { it.right - it.left }
    assertSameColor("container", colors.surfaceContainerLowest, picker.colorAt(width - 8.dp, 8.dp))
  }

  // The Figma writes the day in British English
  @Test
  @Config(qualifiers = "en-rGB")
  fun titleAndSelectedDay_useTheFigmaStyles() {
    setContent(LocalDate.of(2026, 10, 3))
    val title =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.day_picker_title)

    assertStyle(title, typography.labelMedium, colors.onSurfaceVariant)
    assertStyle("Sat, 3 Oct 2026", typography.headlineSmall, colors.onSurface)
  }

  // Material's calendar follows the phone's language, so the selected day above it does too
  @Test
  @Config(qualifiers = "fr-rFR")
  fun selectedDay_isInThePhonesLanguage() {
    setContent(LocalDate.of(2026, 10, 3))

    composeTestRule.onNodeWithText("sam. 3 oct. 2026", useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun offersNoSwitchToTyping_asInTheFigma() {
    setContent(LocalDate.of(2026, 10, 3))

    composeTestRule
        .onNodeWithContentDescription("text input mode", substring = true, ignoreCase = true)
        .assertDoesNotExist()
  }

  @Test
  fun buttons_are56dpTall_withTheFigmaLabel() {
    setContent(LocalDate.of(2026, 10, 3))

    composeTestRule.onNodeWithTag(tags.confirm).assertHeightIsEqualTo(56.dp)
    composeTestRule.onNodeWithTag(tags.cancel).assertHeightIsEqualTo(56.dp)
    assertStyle("OK", typography.buttonLabel, primaryText)
    assertStyle("Cancel", typography.buttonLabel, primaryText)
  }

  // ----- Days that can be picked -----

  @OptIn(ExperimentalMaterial3Api::class)
  private val upToOctober5 = selectableUpTo(LocalDate.of(2026, 10, 5))

  /** The picker's millis for [date], at midnight UTC. */
  private fun millis(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

  @OptIn(ExperimentalMaterial3Api::class)
  @Test
  fun daysUpToTheLatestOne_canBePicked_laterOnesCant() {
    assertTrue(upToOctober5.isSelectableDate(millis(LocalDate.of(2026, 10, 4))))
    assertTrue(upToOctober5.isSelectableDate(millis(LocalDate.of(2026, 10, 5))))
    assertFalse(upToOctober5.isSelectableDate(millis(LocalDate.of(2026, 10, 6))))
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Test
  fun yearsAfterTheLatestDay_cantBePicked() {
    assertTrue(upToOctober5.isSelectableYear(2025))
    assertTrue(upToOctober5.isSelectableYear(2026))
    assertFalse(upToOctober5.isSelectableYear(2027))
  }
}
