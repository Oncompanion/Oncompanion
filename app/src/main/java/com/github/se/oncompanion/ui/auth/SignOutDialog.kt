// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C

/**
 * Asks before signing out, shared by Profile and onboarding ("Use another account"). Every way of
 * dismissing it keeps the session: signing out only happens through [onConfirm].
 *
 * @param pendingChanges local changes may not be saved online: signing out would discard them, so
 *   staying signed in is the focused choice
 * @param failed the last attempt failed
 * @param signingOut signing out is in progress: nothing can be chosen
 */
@Composable
fun SignOutDialog(
    pendingChanges: Boolean,
    failed: Boolean,
    signingOut: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
  val stayFocus = remember { FocusRequester() }
  LaunchedEffect(pendingChanges) { if (pendingChanges) stayFocus.requestFocus() }
  AlertDialog(
      onDismissRequest = { if (!signingOut) onCancel() },
      modifier = Modifier.testTag(C.Tag.profile_sign_out_dialog),
      title = { Text(stringResource(R.string.profile_sign_out_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
              stringResource(
                  if (pendingChanges) R.string.profile_sign_out_unsynced
                  else R.string.profile_sign_out_message
              )
          )
          if (failed) {
            Text(
                stringResource(R.string.profile_sign_out_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(C.Tag.profile_sign_out_error),
            )
          }
          if (signingOut) CircularProgressIndicator(Modifier.testTag(C.Tag.profile_signing_out))
        }
      },
      confirmButton = {
        TextButton(
            onClick = onConfirm,
            enabled = !signingOut,
            modifier = Modifier.testTag(C.Tag.profile_sign_out_confirm),
        ) {
          Text(
              stringResource(
                  if (pendingChanges) R.string.profile_sign_out_discard
                  else R.string.profile_sign_out
              )
          )
        }
      },
      dismissButton = {
        TextButton(
            onClick = onCancel,
            enabled = !signingOut,
            modifier = Modifier.testTag(C.Tag.profile_sign_out_cancel).focusRequester(stayFocus),
        ) {
          Text(
              stringResource(
                  if (pendingChanges) R.string.profile_sign_out_stay
                  else R.string.profile_sign_out_cancel
              )
          )
        }
      },
  )
}
