// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.DialogTags
import com.github.se.oncompanion.ui.common.LakeDialog

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
  LakeDialog(
      title = stringResource(R.string.profile_sign_out_title),
      message =
          stringResource(
              if (pendingChanges) R.string.profile_sign_out_unsynced
              else R.string.profile_sign_out_message
          ),
      confirmLabel =
          stringResource(
              if (pendingChanges) R.string.profile_sign_out_discard else R.string.profile_sign_out
          ),
      cancelLabel =
          stringResource(
              if (pendingChanges) R.string.profile_sign_out_stay
              else R.string.profile_sign_out_cancel
          ),
      onConfirm = onConfirm,
      onCancel = onCancel,
      tags =
          DialogTags(
              dialog = C.Tag.profile_sign_out_dialog,
              confirm = C.Tag.profile_sign_out_confirm,
              cancel = C.Tag.profile_sign_out_cancel,
              error = C.Tag.profile_sign_out_error,
          ),
      // Discarding changes not yet saved online can't be undone
      isDestructive = pendingChanges,
      isWorking = signingOut,
      error = if (failed) stringResource(R.string.profile_sign_out_failed) else null,
      focusCancel = pendingChanges,
  )
}
