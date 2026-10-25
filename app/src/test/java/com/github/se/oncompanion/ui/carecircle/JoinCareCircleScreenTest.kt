// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsAboveKeyboard
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.showKeyboard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class JoinCareCircleScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun string(id: Int) = context.getString(id)

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun assertText(id: Int) {
    composeTestRule.onNodeWithText(string(id), useUnmergedTree = true).assertExists()
  }

  private val typed = mutableListOf<String>()
  private var noCodeClicks = 0

  // --- JoinCareCircleContent ---

  private fun setContent(code: String = "") {
    composeTestRule.setContent {
      JoinCareCircleContent(
          code = code,
          onCodeChange = { typed += it },
          onNoCode = { noCodeClicks++ },
      )
    }
  }

  @Test
  fun content_showsTitleSubtitleFieldAndButtons() {
    setContent()
    node(C.Tag.join_circle_screen).assertIsDisplayed()
    assertText(R.string.join_circle_title)
    assertText(R.string.join_circle_subtitle)
    assertText(R.string.join_circle_code)
    assertText(R.string.join_circle_code_hint)
    node(C.Tag.join_circle_code_field).assertExists()
    node(C.Tag.join_circle_join_button).assertExists()
    node(C.Tag.join_circle_no_code).assertExists()
    assertText(R.string.join_circle_no_code)
  }

  @Test
  fun content_showsTheCode() {
    setContent(code = "ABCDE")
    node(C.Tag.join_circle_code_field).assertTextContains("ABCDE")
  }

  @Test
  fun content_typingCallsOnCodeChange() {
    setContent()
    node(C.Tag.join_circle_code_field).performTextInput("abcde")
    composeTestRule.runOnIdle { assertEquals(listOf("abcde"), typed) }
  }

  @Test
  fun content_joinIsDisabled_andSaysComingSoon() {
    setContent(code = "ABCDE-FGHJK")
    node(C.Tag.join_circle_join_button).assertIsNotEnabled()
    assertText(R.string.join_circle_join_coming_soon)
  }

  @Test
  fun content_noCode_isEnabled_andCallsOnNoCode() {
    setContent()
    node(C.Tag.join_circle_no_code).assertIsEnabled().performScrollTo().performClick()
    composeTestRule.runOnIdle { assertEquals(1, noCodeClicks) }
  }

  @Test
  fun content_keyboardOpen_joinCanBeScrolledAboveIt() {
    setContent()
    val keyboardHeight = 600

    node(C.Tag.join_circle_code_field).requestFocus()
    composeTestRule.showKeyboard(keyboardHeight)

    node(C.Tag.join_circle_join_button).performScrollTo().assertIsAboveKeyboard(keyboardHeight)
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun content_landscapeKeyboardOpen_keepsTheCodeFieldVisible() {
    setContent()
    val keyboardHeight = 500

    // Focus without a tap: a tap can open the text handle, a window of its own
    node(C.Tag.join_circle_code_field).performScrollTo().requestFocus()
    composeTestRule.showKeyboard(keyboardHeight)

    node(C.Tag.join_circle_code_field).performScrollTo().assertIsFullyVisible()
    node(C.Tag.join_circle_code_field).assertIsAboveKeyboard(keyboardHeight)
  }

  // --- JoinCareCircleScreen ---

  @Test
  fun screen_keepsWhatIsTyped_andNoCodeCallsOnNoCode() {
    composeTestRule.setContent { JoinCareCircleScreen(onNoCode = { noCodeClicks++ }) }

    node(C.Tag.join_circle_code_field).performTextInput("abcde-fghjk")
    node(C.Tag.join_circle_code_field).assertTextContains("abcde-fghjk")
    node(C.Tag.join_circle_join_button).assertIsNotEnabled()

    node(C.Tag.join_circle_no_code).performScrollTo().performClick()
    composeTestRule.runOnIdle { assertEquals(1, noCodeClicks) }
  }
}
