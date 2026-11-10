// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** [LakeSnackbarHost]: where the snackbar sits on a screen with a pinned action below it. */
@RunWith(AndroidJUnit4::class)
class LakeSnackbarHostTest {

  @get:Rule val composeTestRule = createComposeRule()

  /** A screen whose pinned Save button is its bottom bar, showing a snackbar. */
  private fun showOverAPinnedButton() {
    composeTestRule.setContent {
      OncompanionTheme {
        val hostState = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
          hostState.showSnackbar(MESSAGE, duration = SnackbarDuration.Indefinite)
        }
        Scaffold(
            snackbarHost = { LakeSnackbarHost(hostState) },
            bottomBar = { Box(Modifier.fillMaxWidth().height(56.dp).testTag(SAVE)) },
        ) {
          Box(Modifier.padding(it))
        }
      }
    }
  }

  private fun snackbar(): DpRect =
      composeTestRule
          .onNode(hasAnyChild(hasText(MESSAGE)), useUnmergedTree = true)
          .getUnclippedBoundsInRoot()

  private fun screenWidth() =
      composeTestRule.onRoot().getUnclippedBoundsInRoot().let { it.right - it.left }

  @Test
  fun keeps16dpFromTheSides_and12dpAboveThePinnedButton() {
    showOverAPinnedButton()

    val snackbar = snackbar()
    val save = composeTestRule.onNodeWithTag(SAVE).getUnclippedBoundsInRoot()
    assertEquals(Spacing.lg, snackbar.left)
    assertEquals(Spacing.lg, screenWidth() - snackbar.right)
    assertTrue("$snackbar, Save at ${save.top}", save.top - snackbar.bottom >= Spacing.md)
  }

  // Material's snackbar stops at 600 dp: across a whole phone held sideways, it reads as a bar
  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun inLandscape_isAtMost600dpWide_andCentered() {
    showOverAPinnedButton()

    val snackbar = snackbar()
    assertTrue("${snackbar.right - snackbar.left} wide", snackbar.right - snackbar.left <= 600.dp)
    assertEquals(screenWidth() - snackbar.right, snackbar.left)
  }

  private companion object {
    const val MESSAGE = "Couldn't save"
    const val SAVE = "save"
  }
}
