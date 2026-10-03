package com.github.se.oncompanion.ui.auth

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleSignInButtonTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val label = context.getString(R.string.sign_in_with_google)

  private fun setButton(enabled: Boolean = true, darkTheme: Boolean = false): () -> Int {
    var clicks = 0
    composeTestRule.setContent {
      GoogleSignInButton(
          onClick = { clicks++ },
          modifier = Modifier.testTag(TAG),
          enabled = enabled,
          darkTheme = darkTheme,
      )
    }
    return { clicks }
  }

  @Test
  fun lightButton_showsContinueWithGoogle() {
    setButton(darkTheme = false)
    composeTestRule.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
  }

  @Test
  fun darkButton_showsContinueWithGoogle() {
    setButton(darkTheme = true)
    composeTestRule.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
  }

  @Test
  fun googleLogo_isDecorative() {
    setButton()
    composeTestRule
        .onNodeWithTag(TAG)
        .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
  }

  @Test
  fun enabledButton_callsOnClick() {
    val clicks = setButton(enabled = true)
    composeTestRule.onNodeWithTag(TAG).assertIsEnabled().performClick()
    composeTestRule.runOnIdle { assertEquals(1, clicks()) }
  }

  @Test
  fun enabledDarkButton_callsOnClick() {
    val clicks = setButton(enabled = true, darkTheme = true)
    composeTestRule.onNodeWithTag(TAG).performClick()
    composeTestRule.runOnIdle { assertEquals(1, clicks()) }
  }

  @Test
  fun disabledButton_isNotEnabledAndIgnoresClicks() {
    val clicks = setButton(enabled = false)
    composeTestRule.onNodeWithTag(TAG).assertIsNotEnabled().performClick()
    composeTestRule.runOnIdle { assertEquals(0, clicks()) }
  }

  private companion object {
    const val TAG = "google_button_under_test"
  }
}
