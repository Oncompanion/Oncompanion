// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The Save button at the bottom of a form, as the form's bottom bar. While [isSaving], a spinner
 * tagged [savingTestTag] replaces [text], so the user sees the tap was taken into account.
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
  Button(
      onClick = onClick,
      enabled = enabled,
      modifier =
          modifier
              .fillMaxWidth()
              // Keeps the button above the system navigation bar (the app draws edge to edge)
              .navigationBarsPadding()
              .padding(horizontal = 24.dp, vertical = 16.dp)
              .heightIn(min = 56.dp)
              .testTag(testTag),
  ) {
    if (isSaving) {
      Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.fillMaxSize().testTag(savingTestTag),
        )
      }
    } else {
      Text(text)
    }
  }
}
