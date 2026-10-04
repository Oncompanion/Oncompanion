package com.github.se.oncompanion.ui.auth

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleSignInButtonTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val label = context.getString(R.string.sign_in_with_google)

  private fun setButton(
      enabled: Boolean = true,
      darkTheme: Boolean = false,
      fontScale: Float = 1f,
  ): () -> Int {
    var clicks = 0
    composeTestRule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(
          LocalDensity provides Density(density.density, fontScale = fontScale)
      ) {
        GoogleSignInButton(
            onClick = { clicks++ },
            modifier = Modifier.testTag(TAG),
            enabled = enabled,
            darkTheme = darkTheme,
        )
      }
    }
    return { clicks }
  }

  private fun assertLabelFitsInButton() {
    val button = composeTestRule.onNodeWithTag(TAG).getUnclippedBoundsInRoot()
    val text =
        composeTestRule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
    assertTrue("label top ${text.top} above button top ${button.top}", text.top >= button.top)
    assertTrue(
        "label bottom ${text.bottom} below button bottom ${button.bottom}",
        text.bottom <= button.bottom,
    )
    assertTrue(
        "label left ${text.left} before button left ${button.left}",
        text.left >= button.left,
    )
    assertTrue(
        "label right ${text.right} after button right ${button.right}",
        text.right <= button.right,
    )
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

  @Test
  fun normalFontScale_buttonIs40dpTall() {
    setButton(fontScale = 1f)
    composeTestRule.onNodeWithTag(TAG).assertHeightIsEqualTo(40.dp)
  }

  @Test
  fun normalFontScale_labelIsFullyInsideTheButton() {
    setButton(fontScale = 1f)
    assertLabelFitsInButton()
  }

  // Android applies non-linear font scaling: at the 200% system maximum (fontScale = 2f) the
  // label's line is ~37.5dp, which still fits in 40dp. A larger scale is needed to force the label
  // past 40dp and check that the height is a minimum, not a fixed value.

  @Test
  fun maxSystemFontScale_labelIsFullyDisplayedInsideTheButton() {
    setButton(fontScale = 2f)
    composeTestRule.onNodeWithTag(TAG).assertHeightIsAtLeast(40.dp)
    composeTestRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
    assertLabelFitsInButton()
  }

  @Test
  fun labelTallerThan40dp_buttonGrowsTallerThan40dp() {
    setButton(fontScale = 3f)
    val textHeight =
        composeTestRule
            .onNodeWithText(label, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .height
    assertTrue("Precondition: label should exceed 40dp but was $textHeight", textHeight > 40.dp)

    val height = composeTestRule.onNodeWithTag(TAG).getUnclippedBoundsInRoot().height
    assertTrue("Expected button taller than 40dp but was $height", height > 40.dp)
  }

  @Test
  fun labelTallerThan40dp_labelIsFullyDisplayedInsideTheButton() {
    setButton(fontScale = 3f)
    composeTestRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
    assertLabelFitsInButton()
  }

  @Test
  fun labelTallerThan40dp_darkButton_labelIsFullyDisplayedInsideTheButton() {
    setButton(darkTheme = true, fontScale = 3f)
    composeTestRule.onNodeWithTag(TAG).assertHeightIsAtLeast(40.dp)
    assertLabelFitsInButton()
  }

  private companion object {
    const val TAG = "google_button_under_test"
  }
}
