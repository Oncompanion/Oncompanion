// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.medication.MedicationDraft
import com.github.se.oncompanion.domain.medication.PrescriptionDraft
import com.github.se.oncompanion.domain.medication.scan.MedicationDetail
import com.github.se.oncompanion.domain.medication.scan.ScannedField
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.warning

// The review of a prescription read from a scan (Figma "US-10 / Review extracted information" and
// "Review / Partly read"): the details to check are amber, not red, since they don't block Save.

/** A field to check, and why: [notFound] when the scan has nothing for it, else it was unsure. */
internal data class FieldCheck(val notFound: Boolean, val testTag: String)

/** Whether [field] of [draft] is empty, so the scan didn't find it rather than read it unsurely. */
internal fun isNotFound(field: ScannedField, draft: PrescriptionDraft): Boolean =
    when (field) {
      ScannedField.PrescribedBy -> draft.prescribedBy.isBlank()
      // A prescription always has a date: the scan read one unsurely, or used today
      ScannedField.PrescribedOn -> false
      is ScannedField.OfMedication ->
          draft.medications.getOrNull(field.position)?.isEmpty(field.detail) ?: false
    }

private fun MedicationDraft.isEmpty(detail: MedicationDetail) =
    when (detail) {
      MedicationDetail.NAME -> name.isBlank()
      MedicationDetail.DOSAGE -> dosage.isBlank()
      MedicationDetail.FREQUENCY -> frequency.isBlank()
      MedicationDetail.DURATION -> durationDays == null
    }

/** How [field] is to check in [uiState], `null` if it isn't. */
internal fun PrescriptionFormUiState.checkOf(field: ScannedField, tagName: String): FieldCheck? =
    if (field in toCheck) {
      FieldCheck(isNotFound(field, draft), C.Tag.prescriptionReviewFieldCheck(tagName))
    } else null

/** The details to check of the medication at [position] in [this]'s draft, and how. */
internal fun PrescriptionFormUiState.checksOf(position: Int): Map<MedicationDetail, FieldCheck> =
    MedicationDetail.entries
        .mapNotNull { detail ->
          checkOf(ScannedField.OfMedication(position, detail), detail.name.lowercase())?.let {
            detail to it
          }
        }
        .toMap()

/**
 * At the top of the review: what was read, and how many details are still to check. "Only part"
 * when the scan didn't find some of them.
 */
@Composable
internal fun ReviewNotice(uiState: PrescriptionFormUiState) {
  val toCheck = uiState.toCheck
  val partly = toCheck.any { isNotFound(it, uiState.draft) }
  val text =
      listOfNotNull(
              stringResource(
                  if (partly) R.string.prescription_review_partly_read
                  else R.string.prescription_review_read
              ),
              if (toCheck.isNotEmpty()) {
                pluralStringResource(
                    R.plurals.prescription_review_to_check,
                    toCheck.size,
                    toCheck.size,
                )
              } else null,
              stringResource(R.string.prescription_review_correct_anything),
          )
          .joinToString(" ")
  Surface(
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp).testTag(C.Tag.prescription_review_notice),
    )
  }
}

/** The colors of a field to check: amber border, label, reason and icon. */
@Composable
internal fun checkColors(): TextFieldColors {
  val warning = MaterialTheme.colorScheme.warning
  return OutlinedTextFieldDefaults.colors(
      focusedBorderColor = warning,
      unfocusedBorderColor = warning,
      focusedLabelColor = warning,
      unfocusedLabelColor = warning,
      focusedSupportingTextColor = warning,
      unfocusedSupportingTextColor = warning,
      focusedTrailingIconColor = warning,
      unfocusedTrailingIconColor = warning,
  )
}

/**
 * Why the field is to check, below it. [whenRead] says it for a field that has a value: read
 * unsurely, converted or cut, the scan doesn't tell which.
 */
@Composable
internal fun CheckReason(
    check: FieldCheck,
    @StringRes whenRead: Int = R.string.prescription_review_may_differ,
) {
  Text(
      stringResource(if (check.notFound) R.string.prescription_review_not_found else whenRead),
      modifier = Modifier.testTag(check.testTag),
  )
}

/** The icon at the end of a field to check, so it doesn't rely on the color alone. */
@Composable
internal fun CheckIcon() {
  Icon(
      imageVector = Icons.Default.Warning,
      contentDescription = stringResource(R.string.prescription_review_needs_checking),
  )
}

/**
 * What to check on a medication's row while it isn't open, in amber above its name: one detail by
 * name ("Please check the frequency", "Dosage not found"), or how many.
 */
@Composable
internal fun RowCheck(position: Int, medication: MedicationDraft, details: List<MedicationDetail>) {
  val text =
      details.singleOrNull()?.let { detail ->
        stringResource(if (medication.isEmpty(detail)) detail.notFoundRes() else detail.checkRes())
      }
          ?: pluralStringResource(
              R.plurals.prescription_review_check_details,
              details.size,
              details.size,
          )
  Text(
      text = text,
      color = MaterialTheme.colorScheme.warning,
      modifier = Modifier.testTag(C.Tag.prescriptionReviewRowCheck(position)),
  )
}

private fun MedicationDetail.checkRes() =
    when (this) {
      MedicationDetail.NAME -> R.string.prescription_review_check_name
      MedicationDetail.DOSAGE -> R.string.prescription_review_check_dosage
      MedicationDetail.FREQUENCY -> R.string.prescription_review_check_frequency
      MedicationDetail.DURATION -> R.string.prescription_review_check_duration
    }

private fun MedicationDetail.notFoundRes() =
    when (this) {
      MedicationDetail.NAME -> R.string.prescription_review_name_not_found
      MedicationDetail.DOSAGE -> R.string.prescription_review_dosage_not_found
      MedicationDetail.FREQUENCY -> R.string.prescription_review_frequency_not_found
      MedicationDetail.DURATION -> R.string.prescription_review_duration_not_found
    }
