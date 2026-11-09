// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.carecircle.label

/**
 * Shown in caregiver mode in place of a [section] the patient doesn't share, e.g. "Anne doesn't
 * share Planning with you", so the caregiver never sees an empty screen and wonders why. A
 * [LakeMessageState] without an action: only the patient can share it.
 *
 * @param patientName the first name of the patient the caregiver follows
 */
@Composable
fun NotSharedMessage(patientName: String, section: CarePermission, modifier: Modifier = Modifier) {
  LakeMessageState(
      title = stringResource(R.string.not_shared_title, patientName, stringResource(section.label)),
      message = stringResource(R.string.not_shared_body),
      modifier = modifier.testTag(C.Tag.not_shared),
  )
}
