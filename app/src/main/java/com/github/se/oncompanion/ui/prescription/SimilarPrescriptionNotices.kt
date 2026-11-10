// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.DialogTags
import com.github.se.oncompanion.ui.common.LakeDialog
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * Asks before saving a prescription that is the same as [saved] (Figma "US-39 / Save – Same
 * prescription already saved"). Keep editing, like tapping outside, goes back to the form; to
 * discard it, the patient closes the form.
 */
@Composable
internal fun AlreadySavedDialog(
    saved: Prescription,
    onKeepEditing: () -> Unit,
    onSaveAnyway: () -> Unit,
) {
  val date = saved.prescribedOn.format(dateFormatter)
  val count = saved.medications.size
  val message =
      saved.prescribedBy?.let {
        pluralStringResource(R.plurals.prescription_already_saved_by, count, it, date, count)
      } ?: pluralStringResource(R.plurals.prescription_already_saved, count, date, count)
  LakeDialog(
      title = stringResource(R.string.prescription_already_saved_title),
      message = message,
      confirmLabel = stringResource(R.string.prescription_already_saved_save_anyway),
      cancelLabel = stringResource(R.string.prescription_already_saved_keep_editing),
      onConfirm = onSaveAnyway,
      onCancel = onKeepEditing,
      tags =
          DialogTags(
              dialog = C.Tag.prescription_already_saved_dialog,
              confirm = C.Tag.prescription_already_saved_save_anyway,
              cancel = C.Tag.prescription_already_saved_keep_editing,
          ),
  )
}

/**
 * Says that the prescription looks like a renewal of [previous] (Figma "US-39 / Review – Looks like
 * a renewal"), in another color than the scan's notice: it's information, nothing to check.
 */
@Composable
internal fun RenewalNotice(previous: Prescription) {
  val date = previous.prescribedOn.format(dateFormatter)
  Surface(
      color = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.prescription_renewal_notice),
  ) {
    Text(
        text =
            previous.prescribedBy?.let {
              stringResource(R.string.prescription_renewal_by, date, it)
            } ?: stringResource(R.string.prescription_renewal, date),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
    )
  }
}
