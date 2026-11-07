// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.buttonLabel
import com.github.se.oncompanion.ui.theme.lakeColors

/** The test tags of [DetailActions], declared by each feature in `C.kt`. */
class DetailActionTags(val edit: String, val delete: String)

/**
 * A detail screen's item, with its [actions] under it. The actions stay on screen while the item
 * scrolls, except on short windows (e.g. a phone held sideways): there they come after the item and
 * scroll with it, so the item keeps the whole height instead of a thin strip.
 *
 * @param showActions whether the item can be acted on now (e.g. not while it is being deleted)
 * @param contentTestTag the tag of the scrolling area, to scroll it in tests
 */
@Composable
fun DetailWithActions(
    showActions: Boolean,
    actions: @Composable () -> Unit,
    contentTestTag: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
  // Kept across rotations, so turning the phone doesn't jump back to the top
  val scrollState = rememberScrollState()
  if (isCompactHeight()) {
    Column(modifier.fillMaxSize().verticalScroll(scrollState).testTag(contentTestTag)) {
      content()
      if (showActions) actions()
    }
  } else {
    Column(modifier.fillMaxSize()) {
      Column(Modifier.weight(1f).verticalScroll(scrollState).testTag(contentTestTag)) { content() }
      if (showActions) actions()
    }
  }
}

/**
 * Available item actions, Edit before Delete, shared across detail screens (Figma: "Bottom actions"
 * of a detail): full-width outlined Edit and text Delete, 16 dp from the sides and 24 dp from the
 * bottom.
 */
@Composable
fun DetailActions(
    editLabel: String,
    deleteLabel: String,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    tags: DetailActionTags,
) {
  if (onEdit == null && onDelete == null) return
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.xl),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    if (onEdit != null)
        OutlinedButton(
            onClick = onEdit,
            border =
                BorderStroke(LakeButtonDefaults.OutlineWidth, MaterialTheme.colorScheme.outline),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.lakeColors.primaryText
                ),
            contentPadding = LakeButtonDefaults.ContentPadding,
            modifier = Modifier.fillMaxWidth().lakeButton(enabled = true).testTag(tags.edit),
        ) {
          Text(editLabel, style = MaterialTheme.typography.buttonLabel)
        }
    if (onDelete != null)
        LakeTextButton(
            text = deleteLabel,
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth().testTag(tags.delete),
        )
  }
}
