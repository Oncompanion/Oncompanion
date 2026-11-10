// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.model.session.FakeCleanupJournal
import com.github.se.oncompanion.model.session.FakeLocalSessionRepository
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class RejectedWriteSnackbarTest {
  @get:Rule val compose = createComposeRule()

  private val message =
      ApplicationProvider.getApplicationContext<android.content.Context>()
          .getString(R.string.rejected_write_message)

  private val shown = mutableListOf<Int>()

  /** Shows the snackbar for [state], recording what [RejectedWriteSnackbarContent] acknowledges. */
  private fun showContent(state: () -> RejectedWriteUiState) {
    compose.setContent { RejectedWriteSnackbarContent(state(), onShown = { shown += it }) }
  }

  @Test
  fun nothingIsShownWithoutARejection() {
    showContent { RejectedWriteUiState(0) }

    compose.onNodeWithText(message).assertDoesNotExist()
    assertTrue(shown.isEmpty())
  }

  @Test
  fun aRejectionShowsTheMessageUntilTheUserDismissesIt() {
    showContent { RejectedWriteUiState(1) }

    compose.onNodeWithText(message).assertIsDisplayed()
    assertTrue(shown.isEmpty())

    compose.onNodeWithContentDescription("Dismiss").performClick()
    compose.waitForIdle()
    assertEquals(listOf(1), shown)
  }

  @Test
  fun theMessageGoesAwayOnItsOwnAfterAWhile() {
    showContent { RejectedWriteUiState(1) }
    compose.onNodeWithText(message).assertIsDisplayed()

    compose.mainClock.advanceTimeBy(15_000)
    compose.waitForIdle()

    compose.onNodeWithText(message).assertDoesNotExist()
    assertEquals(listOf(1), shown)
  }

  @Test
  fun aRejectionWhileTheMessageShowsIsAcknowledgedWithIt() {
    var state by mutableStateOf(RejectedWriteUiState(1))
    showContent { state }
    compose.onNodeWithText(message).assertIsDisplayed()

    state = RejectedWriteUiState(2)
    compose.waitForIdle()
    compose.onNodeWithText(message).assertIsDisplayed()
    compose.onNodeWithContentDescription("Dismiss").performClick()
    compose.waitForIdle()

    assertEquals(listOf(2), shown)
  }

  @Test
  fun aReportedRejectionIsShownOnceThroughTheViewModel() {
    val rejectedWrites = RejectedWrites()
    val signOut =
        SignOut(FakeAuthRepository(), FakeLocalSessionRepository()).apply {
          initialize(FakeCleanupJournal())
        }
    val viewModel = RejectedWriteViewModel(rejectedWrites, signOut)
    compose.setContent { RejectedWriteSnackbar(viewModel = viewModel) }
    compose.onNodeWithText(message).assertDoesNotExist()

    rejectedWrites.report()
    compose.onNodeWithText(message).assertIsDisplayed()
    compose.onNodeWithContentDescription("Dismiss").performClick()
    compose.waitForIdle()

    compose.onNodeWithText(message).assertDoesNotExist()
    assertEquals(0, rejectedWrites.unseen.value)
  }

  // ----- Figma: "Snackbar" (Brand & Theme) -----

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun snackbar_is16dpFromTheSides_withMediumCorners() {
    lateinit var colors: ColorScheme
    compose.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        Surface(color = MaterialTheme.colorScheme.surface) {
          RejectedWriteSnackbarContent(RejectedWriteUiState(1), onShown = {})
        }
      }
    }
    val host = compose.onNodeWithTag(C.Tag.rejected_write_snackbar)
    val cases =
        listOf(
            // Left margin, then the snackbar's straight left edge
            Triple("margin", 14.dp to 24.dp, colors.surface),
            Triple("left edge", 18.dp to 24.dp, colors.inverseSurface),
            // Its top left corner is rounded by 12dp: the corner is cut, its top edge isn't
            Triple("cut corner", 18.5.dp to 2.5.dp, colors.surface),
            Triple("top edge", 28.dp to 2.dp, colors.inverseSurface),
        )
    for ((name, point, expected) in cases) {
      assertSameColor(name, expected, host.colorAt(point.first, point.second))
    }
  }
}
