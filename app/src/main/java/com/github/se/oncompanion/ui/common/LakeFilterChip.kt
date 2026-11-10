// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing

/**
 * The Figma "Filter chip": 48 dp tall, a full touch target (Material's chip is 32 dp), with Medium
 * corners. White with an Outline border, or dawn with a check before its [label] once [selected].
 *
 * @param role what screen readers call it: a checkbox when several chips can be picked, as on
 *   Material's chip, or [Role.RadioButton] when only one can
 */
@Composable
fun LakeFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.Checkbox,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
      selected = selected,
      onClick = onClick,
      enabled = enabled,
      shape = MaterialTheme.shapes.medium,
      color = if (selected) colors.secondaryContainer else colors.surfaceContainerLowest,
      contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
      border = if (selected) null else BorderStroke(1.dp, colors.outline),
      modifier =
          modifier
              .semantics { this.role = role }
              .alpha(if (enabled) 1f else LakeButtonDefaults.DISABLED_ALPHA),
  ) {
    // The check takes some of the start padding, so the label moves less when it appears
    Row(
        Modifier.heightIn(min = 48.dp)
            .padding(start = if (selected) Spacing.md else Spacing.lg, end = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (selected) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = null,
            modifier = Modifier.size(20.dp).testTag(C.Tag.filter_chip_check),
        )
      }
      Text(label, style = MaterialTheme.typography.labelLarge)
    }
  }
}
