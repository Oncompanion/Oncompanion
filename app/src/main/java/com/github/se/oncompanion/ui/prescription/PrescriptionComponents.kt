// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthRepository
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
  /** Nobody is signed in, patient or caregiver: retrying can't help, signing in again does. */
  NOT_SIGNED_IN,
  /**
   * Signed in, but not in the circle of the patient whose prescriptions these are: a caregiver the
   * patient removed. Retrying wouldn't help.
   */
  NO_ACCESS,
}

/**
 * Where the prescription screens put a message in place of their content: centered, and scrolling
 * when the message is taller than the space (a small phone held sideways, a large font), so its
 * button can always be reached (#490). It adds no side padding: the message brings its own, so it
 * wraps the same on every screen.
 */
@Composable
internal fun MessageArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  BoxWithConstraints(modifier.fillMaxSize()) {
    Box(
        Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().heightIn(min = maxHeight),
        contentAlignment = Alignment.Center,
    ) {
      content()
    }
  }
}

/**
 * Why nothing can be read when the access check finds no access: nobody is signed in, or the user
 * is signed in but not in the patient's circle. Asked of the sign-in itself, so a caregiver who
 * signs out is never told they were removed (#490).
 */
internal fun noAccessLoad(auth: AuthRepository): PrescriptionsLoad =
    if (runCatching { auth.currentUser }.getOrNull() == null) PrescriptionsLoad.NOT_SIGNED_IN
    else PrescriptionsLoad.NO_ACCESS

/** What the list and the detail say at [PrescriptionsLoad.NOT_SIGNED_IN], to anyone. */
@Composable
internal fun SignedOutMessage() {
  LakeMessageState(
      title = stringResource(R.string.prescriptions_signed_out_title),
      message = stringResource(R.string.prescriptions_signed_out_hint),
  )
}

/**
 * What the list and the detail say at [PrescriptionsLoad.NO_ACCESS]: the caregiver is no longer in
 * the patient's circle. No Retry, since retrying can't change it.
 *
 * @param caregiverOf the patient's first name, which is already gone once a removed caregiver has
 *   left: the text then names nobody, but still says why
 */
@Composable
internal fun NoAccessMessage(caregiverOf: String?) {
  LakeMessageState(
      title =
          caregiverOf?.let { stringResource(R.string.prescriptions_no_access_title_for, it) }
              ?: stringResource(R.string.prescriptions_no_access_title_unnamed),
      message =
          caregiverOf?.let { stringResource(R.string.prescriptions_no_access_hint_for, it) }
              ?: stringResource(R.string.prescriptions_no_access_hint_unnamed),
  )
}
