// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing

/** The test tags of a [LakeDialog], declared by each feature in `C.kt`. */
class DialogTags(
    val dialog: String,
    val confirm: String,
    val cancel: String,
    val error: String = "",
)

/**
 * A dialog of the Figma ("Dialog" in Shared patterns), for a decision: a title, a [message], then
 * [cancelLabel] and [confirmLabel] at its end, so every decision looks the same. While [isWorking],
 * both buttons and Back do nothing, and a spinner next to Cancel shows the action is on its way.
 *
 * @param cancelLabel null for a dialog with [confirmLabel] alone, e.g. a tip to acknowledge; Back
 *   and a tap outside still call [onCancel]
 * @param error shown in red under [message] when not null, e.g. after a failed deletion
 * @param isDestructive makes [confirmLabel] red, for an action that can't be undone
 * @param focusCancel puts the focus on [cancelLabel] first, when it is the safer choice (e.g.
 *   staying signed in with changes not yet saved online)
 * @param content shown under [message], scrolling with it, e.g. a "Don't show this again" checkbox
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LakeDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    tags: DialogTags,
    isDestructive: Boolean = false,
    isWorking: Boolean = false,
    error: String? = null,
    focusCancel: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val colors = MaterialTheme.colorScheme
  val cancelFocus = remember { FocusRequester() }
  LaunchedEffect(focusCancel) { if (focusCancel && cancelLabel != null) cancelFocus.requestFocus() }
  BasicAlertDialog(onDismissRequest = { if (!isWorking) onCancel() }) {
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
          if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
                modifier = Modifier.testTag(tags.error),
            )
          }
          content?.invoke(this)
        }
        DialogButtons(
            progress = {
              if (isWorking) {
                SmallSpinner(colors.primary, Modifier.testTag(C.Tag.dialog_progress))
              } else Spacer(Modifier)
            },
            dismiss = {
              if (cancelLabel != null) {
                LakeButton(
                    text = cancelLabel,
                    onClick = onCancel,
                    style = LakeButtonStyle.Text,
                    enabled = !isWorking,
                    modifier = Modifier.testTag(tags.cancel).focusRequester(cancelFocus),
                )
              } else Spacer(Modifier)
            },
            confirm = {
              LakeButton(
                  text = confirmLabel,
                  onClick = onConfirm,
                  style = LakeButtonStyle.Text,
                  enabled = !isWorking,
                  isDestructive = isDestructive,
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
