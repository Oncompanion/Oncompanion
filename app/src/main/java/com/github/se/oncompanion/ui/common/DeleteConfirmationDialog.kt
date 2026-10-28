// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The test tags of a [DeleteConfirmationDialog], declared by each feature in `C.kt`. */
class DeleteDialogTags(
    val dialog: String,
    val confirm: String,
    val cancel: String,
    val error: String,
)

/**
 * Asks to confirm the deletion of an item, which can't be undone (Figma: "US-9 / Delete entry –
 * confirmation dialog"). Shared by the detail screens so that every deletion looks the same. Both
 * buttons, and Back, do nothing while the item is being deleted.
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
  AlertDialog(
      onDismissRequest = { if (!isDeleting) onCancel() },
      modifier = Modifier.testTag(tags.dialog),
      title = { Text(title) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(message)
          if (hasError) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(tags.error),
            )
          }
        }
      },
      confirmButton = {
        TextButton(
            onClick = onConfirm,
            enabled = !isDeleting,
            colors =
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag(tags.confirm),
        ) {
          Text(confirmLabel)
        }
      },
      dismissButton = {
        TextButton(
            onClick = onCancel,
            enabled = !isDeleting,
            modifier = Modifier.testTag(tags.cancel),
        ) {
          Text(cancelLabel)
        }
      },
  )
}
