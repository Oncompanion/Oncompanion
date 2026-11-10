// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.GoogleCredentialProvider
import com.github.se.oncompanion.ui.auth.SignOutDialog
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonStyle

/**
 * Below Continue on both onboarding steps (Figma "US-1 / Onboarding – Role (use another account)"):
 * which account is signed in, and a way to pick another one before any profile is saved for this
 * one.
 *
 * @param email the signed-in account's; not shown when unknown
 */
@Composable
internal fun SignedInAs(email: String?, enabled: Boolean, onUseAnotherAccount: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    if (email != null) {
      Text(
          text = stringResource(R.string.onboarding_signed_in_as, email),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.testTag(C.Tag.onboarding_signed_in_as),
      )
    }
    LakeButton(
        text = stringResource(R.string.onboarding_use_another_account),
        onClick = onUseAnotherAccount,
        style = LakeButtonStyle.Text,
        enabled = enabled,
        modifier = Modifier.testTag(C.Tag.onboarding_use_another_account),
    )
  }
}

/**
 * Drives "Use another account" on an onboarding step: asks before discarding local changes that may
 * not be saved online, blocks Back while signing out, and calls [onSignedOut] once signed out.
 *
 * @return what "Use another account" does
 */
@Composable
internal fun accountSwitch(
    uiState: OnboardingUiState,
    viewModel: OnboardingViewModel,
    credentialProvider: GoogleCredentialProvider,
    onSignedOut: () -> Unit,
): () -> Unit {
  val context = LocalContext.current
  val clearCredentials: suspend () -> Unit = { credentialProvider.clearCredentialState(context) }
  LaunchedEffect(uiState.accountSwitch) {
    if (uiState.accountSwitch == AccountSwitch.DONE) onSignedOut()
  }
  // Back must not leave onboarding for a half-signed-out session
  BackHandler(enabled = uiState.accountSwitch == AccountSwitch.SIGNING_OUT) {}
  if (uiState.accountSwitch == AccountSwitch.CONFIRMING_DISCARD) {
    SignOutDialog(
        pendingChanges = true,
        failed = false,
        signingOut = false,
        onConfirm = { viewModel.discardChangesAndSwitch(clearCredentials) },
        onCancel = viewModel::stayOnThisAccount,
    )
  }
  return { viewModel.useAnotherAccount(clearCredentials) }
}
