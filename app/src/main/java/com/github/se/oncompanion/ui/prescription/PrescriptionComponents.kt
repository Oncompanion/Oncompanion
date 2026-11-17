// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.ui.common.LakeMessageState
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the prescription screens write a date: "29 Sep 2026". One formatter, so the list, the detail,
 * the form and the notices always agree. The app's UI is in English.
 */
internal val prescriptionDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * Where reading the prescriptions of the list or the detail is at, as one value, so two states can
 * never be true at once. What was read is kept apart: an empty list, or a prescription that no
 * longer exists, is still [SHOWN].
 */
enum class PrescriptionsLoad {
  /** The access or the prescriptions are still being read. */
  LOADING,
  /** Read: the list, maybe empty, or the prescription, null if it no longer exists. */
  SHOWN,
  /**
   * Reading failed, and retrying may help. Not a connection problem: offline, the cache answers.
   */
  FAILED,
  /** In caregiver mode, the patient doesn't share their prescriptions. */
  NOT_SHARED,
  /**
   * Nothing can be read, and retrying wouldn't help: nobody is signed in, or in caregiver mode the
   * caregiver is no longer in the patient's circle.
   */
  NO_ACCESS,
}

/**
 * The one message the prescription screens show in place of their content (#277): a load error, a
 * prescription that no longer exists, no access. A [title], an optional [hint] and an optional
 * [actionLabel] such as "Retry". It draws the app's message state today; when the M3 State card
 * replaces it, only this function changes.
 *
 * @param actionTestTag the test tag of the button, shown when [actionLabel] isn't null
 */
@Composable
internal fun PrescriptionMessage(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    actionTestTag: String = "",
) {
  LakeMessageState(
      title = title,
      modifier = modifier,
      message = hint,
      actionLabel = actionLabel,
      onAction = onAction,
      actionTestTag = actionTestTag,
  )
}

/**
 * Why the list or the detail can't show any prescription when there is
 * [PrescriptionsLoad.NO_ACCESS]: the patient ([caregiverOf] null) is signed out, or the caregiver
 * is no longer in the patient's circle. No Retry, since retrying can't change either.
 */
@Composable
internal fun NoAccessMessage(caregiverOf: String?, modifier: Modifier = Modifier) {
  PrescriptionMessage(
      title =
          caregiverOf?.let { stringResource(R.string.prescriptions_no_access_title_for, it) }
              ?: stringResource(R.string.prescriptions_signed_out_title),
      hint =
          caregiverOf?.let { stringResource(R.string.prescriptions_no_access_hint_for, it) }
              ?: stringResource(R.string.prescriptions_signed_out_hint),
      modifier = modifier,
  )
}
