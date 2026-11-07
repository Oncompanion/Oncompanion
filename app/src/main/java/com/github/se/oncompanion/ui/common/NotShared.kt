// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.carecircle.label
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.full

/**
 * Shown in caregiver mode in place of a [section] the patient doesn't share, e.g. "Anne doesn't
 * share Planning with you", so the caregiver never sees an empty screen and wonders why. Drawn as
 * the Figma "Message state": an info tile, a title and one line.
 *
 * @param patientName the first name of the patient the caregiver follows
 */
@Composable
fun NotSharedMessage(patientName: String, section: CarePermission, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  Column(
      modifier.fillMaxWidth().padding(Spacing.lg).testTag(C.Tag.not_shared),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // The round tile, kept apart from the texts by an 8 dp spacer between the gaps (24 dp in all)
    Surface(
        shape = MaterialTheme.shapes.full,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        modifier = Modifier.size(64.dp).testTag(C.Tag.not_shared_icon),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null)
      }
    }
    Spacer(Modifier.height(Spacing.sm))
    Text(
        stringResource(R.string.not_shared_title, patientName, stringResource(section.label)),
        style = MaterialTheme.typography.titleLarge,
        color = colors.onSurface,
        textAlign = TextAlign.Center,
    )
    Text(
        stringResource(R.string.not_shared_body),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
  }
}
