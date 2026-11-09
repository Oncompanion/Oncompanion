// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.lakeColors

/** The test tags of a [DeleteConfirmationDialog], declared by each feature in `C.kt`. */
class DeleteDialogTags(
    val dialog: String,
    val confirm: String,
    val cancel: String,
    val error: String,
)

/**
 * Asks to confirm the deletion of an item, which can't be undone (Figma: "Dialog", and "US-9 /
 * Delete entry – confirmation dialog"). Shared by the detail screens so that every deletion looks
 * the same. Both buttons, and Back, do nothing while the item is being deleted, and a spinner shows
 * it is on its way.
 *
 * @param error shown under [message] when [hasError], after a failed deletion
 */
@OptIn(ExperimentalMaterial3Api::class)
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
  val colors = MaterialTheme.colorScheme
  BasicAlertDialog(onDismissRequest = { if (!isDeleting) onCancel() }) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainerLowest,
        modifier = Modifier.width(DIALOG_WIDTH).testTag(tags.dialog),
    ) {
      Column(
          Modifier.padding(
              start = Spacing.xl,
              end = Spacing.xl,
              top = Spacing.xl,
              bottom = Spacing.md,
          ),
          verticalArrangement = Arrangement.spacedBy(Spacing.md),
      ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        // The texts scroll on a short window, so the buttons always stay on screen
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
          Text(
              message,
              style = MaterialTheme.typography.bodyMedium,
              color = colors.onSurfaceVariant,
          )
          if (hasError) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
                modifier = Modifier.testTag(tags.error),
            )
          }
        }
        DialogButtons(
            progress = {
              if (isDeleting) {
                SmallSpinner(colors.primary, Modifier.testTag(C.Tag.delete_dialog_progress))
              } else Spacer(Modifier)
            },
            dismiss = {
              LakeTextButton(
                  text = cancelLabel,
                  onClick = onCancel,
                  enabled = !isDeleting,
                  modifier = Modifier.testTag(tags.cancel),
              )
            },
            confirm = {
              LakeTextButton(
                  text = confirmLabel,
                  onClick = onConfirm,
                  enabled = !isDeleting,
                  // Only the destructive action is red, and not while it is under way
                  color = if (isDeleting) MaterialTheme.lakeColors.primaryText else colors.error,
                  modifier = Modifier.testTag(tags.confirm),
              )
            },
        )
      }
    }
  }
}

/**
 * A dialog's buttons at its end, [dismiss] then [confirm], with [progress] before them (Figma:
 * Actions=Inline). When they don't fit on one line, [confirm] goes on top of [dismiss]
 * (Actions=Stacked), so the main action stays first. [progress] is always next to [dismiss], which
 * leaves it room, so it stays in the dialog however long [confirm]'s label is.
 */
@Composable
private fun DialogButtons(
    progress: @Composable () -> Unit,
    dismiss: @Composable () -> Unit,
    confirm: @Composable () -> Unit,
) {
  Layout(
      content = {
        progress()
        dismiss()
        confirm()
      },
      modifier = Modifier.fillMaxWidth(),
  ) { measurables, constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val width = constraints.maxWidth
    val spinner = measurables[0].measure(loose)
    val cancel = measurables[1].measure(loose.copy(maxWidth = width - spinner.width))
    val main = measurables[2].measure(loose)
    if (spinner.width + cancel.width + main.width <= width) {
      val height = maxOf(spinner.height, cancel.height, main.height)
      layout(width, height) {
        main.place(width - main.width, (height - main.height) / 2)
        cancel.place(width - main.width - cancel.width, (height - cancel.height) / 2)
        spinner.place(
            width - main.width - cancel.width - spinner.width,
            (height - spinner.height) / 2,
        )
      }
    } else {
      layout(width, main.height + cancel.height) {
        main.place(width - main.width, 0)
        cancel.place(width - cancel.width, main.height)
        spinner.place(
            width - cancel.width - spinner.width,
            main.height + (cancel.height - spinner.height) / 2,
        )
      }
    }
  }
}

// The width of every dialog in the Figma
private val DIALOG_WIDTH = 312.dp
