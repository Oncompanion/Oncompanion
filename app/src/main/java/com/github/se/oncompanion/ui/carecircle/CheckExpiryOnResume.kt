// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Calls [refresh] each time the screen resumes, so the invitations it shows are checked again
 * against the current time: the [repeatAtEachExpiry] timer doesn't run while the phone sleeps, and
 * the user may change the clock. The Compose side of [ExpiryChecks], kept in its own file like the
 * other UI code.
 *
 * @param refresh usually the ViewModel's `refreshExpiry`, which calls [ExpiryChecks.checkAgain]
 */
@Composable
fun CheckExpiryOnResume(refresh: () -> Unit) {
  LifecycleResumeEffect(refresh) {
    refresh()
    onPauseOrDispose {}
  }
}
