// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Typography
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.ui.SMALL_PHONE
import com.github.se.oncompanion.ui.assertNoWordIsCut
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [LakeSnackbar]'s action, as the Figma "Snackbar" (Shared patterns) draws it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LakeSnackbarTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography
  private var result: SnackbarResult? = null
  private var dark by mutableStateOf(false)

  /** A snackbar with a [action] such as Retry, and a close button when [withDismissAction]. */
  private fun showWithRetry(withDismissAction: Boolean = false, action: String = "Retry") {
    composeTestRule.setContent {
      OncompanionTheme(darkTheme = dark) {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        val hostState = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
          result =
              hostState.showSnackbar(
                  MESSAGE,
                  actionLabel = action,
                  withDismissAction = withDismissAction,
                  duration = SnackbarDuration.Long,
              )
        }
        SnackbarHost(hostState) { LakeSnackbar(it, Modifier.testTag(SNACKBAR)) }
      }
    }
  }

  private fun retry() = composeTestRule.onNode(hasText("Retry") and hasClickAction())

  @Test
  fun theAction_isLabelLarge_inInversePrimary_inBothThemes_8dpFromTheEnd_atLeast48dpTall() {
    showWithRetry()

    for (theme in listOf(false, true)) {
      dark = theme
      composeTestRule.waitForIdle()
      composeTestRule
          .onNodeWithText("Retry", useUnmergedTree = true)
          .assertTextStyle(typography.labelLarge, colors.inversePrimary)
    }
    retry().assertHeightIsAtLeast(48.dp)
    val snackbar = composeTestRule.onNodeWithTag(SNACKBAR).getUnclippedBoundsInRoot()
    assertEquals(8.dp, snackbar.right - retry().getUnclippedBoundsInRoot().right)
  }

  @Test
  fun tappingTheAction_reportsIt_andHidesTheSnackbar() {
    showWithRetry()

    retry().performClick()
    composeTestRule.waitForIdle()

    assertEquals(SnackbarResult.ActionPerformed, result)
    composeTestRule.onNodeWithText(MESSAGE).assertDoesNotExist()
  }

  @Test
  fun withACloseButtonToo_theActionComesBeforeIt() {
    showWithRetry(withDismissAction = true)

    val close = composeTestRule.onNodeWithContentDescription("Dismiss")
    assertTrue(retry().getUnclippedBoundsInRoot().right <= close.getUnclippedBoundsInRoot().left)
  }

  // On a narrow phone at 200%, a long action beside the message would leave it a few letters a line
  @Test
  @Config(qualifiers = SMALL_PHONE, fontScale = 2f)
  fun aLongAction_atLargeFont_leavesTheMessageAndTheActionReadable() {
    showWithRetry(withDismissAction = true, action = LONG_ACTION)

    for (text in listOf(MESSAGE, LONG_ACTION)) {
      composeTestRule.onNodeWithText(text, useUnmergedTree = true).assertNoWordIsCut()
    }
    val snackbar = composeTestRule.onNodeWithTag(SNACKBAR).getUnclippedBoundsInRoot()
    val action = composeTestRule.onNode(hasText(LONG_ACTION) and hasClickAction())
    val bounds = action.getUnclippedBoundsInRoot()
    assertTrue(
        "the action is inside",
        bounds.left >= snackbar.left && bounds.right <= snackbar.right,
    )
  }

  private companion object {
    const val SNACKBAR = "snackbar"
    const val MESSAGE = "Couldn't sign in with Google. Please try again."
    const val LONG_ACTION = "Try again later"
  }
}
