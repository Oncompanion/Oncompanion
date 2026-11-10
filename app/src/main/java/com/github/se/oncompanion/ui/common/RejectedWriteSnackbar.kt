// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C

/**
 * App-wide snackbar that tells the user a change couldn't be saved and was undone, because the
 * server rejected it after it was saved on the device. Place it once, over the navigation graph.
 */
@Composable
fun RejectedWriteSnackbar(
    modifier: Modifier = Modifier,
    viewModel: RejectedWriteViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  RejectedWriteSnackbarContent(uiState, onShown = viewModel::onShown, modifier = modifier)
}

/**
 * Stateless [RejectedWriteSnackbar]: shows the message while [uiState] has unseen rejections, then
 * calls [onShown] with how many it covered once the user dismissed it or it timed out.
 */
@Composable
fun RejectedWriteSnackbarContent(
    uiState: RejectedWriteUiState,
    onShown: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
  val hostState = remember { SnackbarHostState() }
  val message = stringResource(R.string.rejected_write_message)
  val currentOnShown by rememberUpdatedState(onShown)
  val unseen = uiState.unseenRejections
  // A new rejection while the message shows restarts it, then acknowledges all of them at once
  LaunchedEffect(unseen) {
    if (unseen > 0) {
      hostState.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
      currentOnShown(unseen)
    }
  }
  // Raised by the height of the bottom navigation bar, so the message never covers the tabs
  LakeSnackbarHost(
      hostState,
      modifier
          .navigationBarsPadding()
          .imePadding()
          .padding(bottom = BOTTOM_BAR_HEIGHT)
          .testTag(C.Tag.rejected_write_snackbar),
  )
}

private val BOTTOM_BAR_HEIGHT = 80.dp
