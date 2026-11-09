// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C

/**
 * Tells a caregiver why they were moved to "Whose care?" or Join (Figma "#204 / Join – Removed from
 * a circle"): "You're no longer in Anne's care circle." Placed as the screen's bottom bar, so it
 * never covers an action, and gone on its own after a few seconds.
 *
 * @param removedFrom the first name of the patient who removed the caregiver; nothing shows when
 *   null
 * @param onShown called once the message went away, so it doesn't show again; not called if the
 *   screen closes first, so the next screen shows it
 */
@Composable
fun RemovedFromCircleSnackbar(removedFrom: String?, onShown: () -> Unit) {
  val hostState = remember { SnackbarHostState() }
  val message = removedFrom?.let {
    if (it.isBlank()) stringResource(R.string.removed_from_circle_unnamed)
    else stringResource(R.string.removed_from_circle, it)
  }
  val currentOnShown by rememberUpdatedState(onShown)
  LaunchedEffect(message) {
    if (message != null) {
      hostState.showSnackbar(message, duration = SnackbarDuration.Short)
      currentOnShown()
    }
  }
  // The app is edge-to-edge and a bottom bar gets no inset: keep it above the navigation bar
  SnackbarHost(
      hostState,
      Modifier.navigationBarsPadding().testTag(C.Tag.removed_from_circle_snackbar),
  )
}
