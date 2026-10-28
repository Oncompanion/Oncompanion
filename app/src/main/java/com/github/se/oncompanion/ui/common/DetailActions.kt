// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

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

/** Available item actions, Edit before Delete, shared across detail screens. */
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
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (onEdit != null)
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().testTag(tags.edit)) {
          Icon(
              Icons.Default.Edit,
              contentDescription = null,
              Modifier.size(ButtonDefaults.IconSize),
          )
          Spacer(Modifier.size(ButtonDefaults.IconSpacing))
          Text(editLabel)
        }
    if (onDelete != null)
        TextButton(onClick = onDelete, modifier = Modifier.testTag(tags.delete)) {
          Text(deleteLabel)
        }
  }
}
