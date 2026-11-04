// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.carecircle.label

/**
 * Shown in caregiver mode in place of a [section] the patient doesn't share, e.g. "Anne doesn't
 * share Planning with you", so the caregiver never sees an empty screen and wonders why.
 *
 * @param patientName the first name of the patient the caregiver follows
 */
@Composable
fun NotSharedMessage(patientName: String, section: CarePermission, modifier: Modifier = Modifier) {
  Column(
      modifier.fillMaxWidth().padding(24.dp).testTag(C.Tag.not_shared),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
        stringResource(R.string.not_shared_title, patientName, stringResource(section.label)),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        stringResource(R.string.not_shared_body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
  }
}
