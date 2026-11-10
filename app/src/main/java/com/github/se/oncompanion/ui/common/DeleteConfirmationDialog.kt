// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.runtime.Composable

/** The test tags of a [DeleteConfirmationDialog], declared by each feature in `C.kt`. */
typealias DeleteDialogTags = DialogTags

/**
 * Asks to confirm the deletion of an item, which can't be undone (Figma: "US-9 / Delete entry –
 * confirmation dialog"). Shared by the detail screens so that every deletion looks the same: a
 * [LakeDialog] whose confirm button is red, working while [isDeleting].
 *
 * @param error shown under [message] when [hasError], after a failed deletion
 */
@Composable
fun DeleteConfirmationDialog(
    title: String,
    message: String,
    error: String,
    confirmLabel: String,
    cancelLabel: String,
    isDeleting: Boolean,
    hasError: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    tags: DeleteDialogTags,
) {
  LakeDialog(
      title = title,
      message = message,
      confirmLabel = confirmLabel,
      cancelLabel = cancelLabel,
      onConfirm = onConfirm,
      onCancel = onCancel,
      tags = tags,
      isDestructive = true,
      isWorking = isDeleting,
      error = if (hasError) error else null,
  )
}
