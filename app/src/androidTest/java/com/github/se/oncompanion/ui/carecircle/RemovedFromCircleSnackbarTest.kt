// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.resources.C
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The removal message on a real device, edge-to-edge like the app: Robolectric draws no navigation
 * bar, so only a device shows whether the message sits under it.
 */
@RunWith(AndroidJUnit4::class)
class RemovedFromCircleSnackbarTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun message_staysAboveTheNavigationBar() {
    compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
    compose.setContent {
      WhoseCareContent(
          uiState =
              WhoseCareUiState(
                  patients =
                      listOf(FollowedPatient("p-paul", "Paul", setOf(CarePermission.PLANNING))),
                  isLoading = false,
                  isConfirmed = true,
              ),
          onBack = null,
          onPatientClick = {},
          onJoinAnother = {},
          onRetry = {},
          removedFrom = "Anne",
      )
    }
    compose.waitUntil(5_000) {
      compose.onAllNodes(hasTestTag(C.Tag.removed_from_circle_snackbar)).fetchSemanticsNodes().any {
        it.size.height > 0
      }
    }
    val navigationBar =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())
            ?.bottom ?: 0
    assumeTrue("no navigation bar at the bottom on this device", navigationBar > 0)

    val screenBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
    val messageBottom =
        compose
            .onNodeWithTag(C.Tag.removed_from_circle_snackbar)
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom
    assertTrue(
        "the message ends at $messageBottom px, under the navigation bar starting at " +
            "${screenBottom - navigationBar} px",
        messageBottom <= screenBottom - navigationBar,
    )
  }
}
