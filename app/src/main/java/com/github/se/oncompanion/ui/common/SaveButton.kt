// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.buttonLabel

/**
 * The Save button at the bottom of a form, as the form's bottom bar (Figma: "Button", Style=Filled,
 * pinned 16 dp from the sides and 24 dp from the bottom). While [isSaving], a spinner tagged
 * [savingTestTag] replaces [text] on the full teal button, so the user sees the tap was taken into
 * account; otherwise a disabled button fades.
 */
@Composable
fun SaveButton(
    text: String,
    enabled: Boolean,
    isSaving: Boolean,
    onClick: () -> Unit,
    testTag: String,
    savingTestTag: String,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Button(
      onClick = onClick,
      enabled = enabled,
      // Disabled keeps the filled colors: the whole button fades instead
      colors =
          ButtonDefaults.buttonColors(
              disabledContainerColor = colors.primary,
              disabledContentColor = colors.onPrimary,
          ),
      contentPadding = LakeButtonDefaults.ContentPadding,
      modifier =
          modifier
              .fillMaxWidth()
              // Keeps the button above the system navigation bar (the app draws edge to edge)
              .navigationBarsPadding()
              .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xl)
              .lakeButton(enabled || isSaving)
              .testTag(testTag),
  ) {
    if (isSaving) {
      SmallSpinner(color = LocalContentColor.current, modifier = Modifier.testTag(savingTestTag))
    } else {
      Text(text, style = MaterialTheme.typography.buttonLabel)
    }
  }
}
