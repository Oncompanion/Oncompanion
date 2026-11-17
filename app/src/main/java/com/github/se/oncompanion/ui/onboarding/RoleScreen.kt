// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.GoogleCredentialProvider
import com.github.se.oncompanion.ui.auth.rememberGoogleCredentialProvider
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.theme.Spacing

/**
 * First onboarding step (Figma "US-1 / Onboarding – Role"): patient or caregiver. The role decides
 * what the next steps ask (a caregiver has no cancer type) and can't change afterwards.
 *
 * @param onContinue called to go to the next step
 * @param onSignedOut called once signed out to use another account
 */
@Composable
fun RoleScreen(
    viewModel: OnboardingViewModel,
    onContinue: () -> Unit,
    onSignedOut: () -> Unit,
    credentialProvider: GoogleCredentialProvider = rememberGoogleCredentialProvider(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val useAnotherAccount = accountSwitch(uiState, viewModel, credentialProvider, onSignedOut)
  RoleContent(
      selectedRole = uiState.role,
      onRoleSelected = viewModel::selectRole,
      onContinue = onContinue,
      email = uiState.email,
      canSwitchAccount = uiState.canSwitchAccount,
      onUseAnotherAccount = useAnotherAccount,
  )
}

/**
 * Stateless content of the role step.
 *
 * @param email the signed-in account's, shown above "Use another account" when known; both sit just
 *   above Continue, which stays at the bottom of the screen
 */
@Composable
fun RoleContent(
    selectedRole: Role?,
    onRoleSelected: (Role) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    email: String? = null,
    canSwitchAccount: Boolean = true,
    onUseAnotherAccount: () -> Unit = {},
) {
  Scaffold(modifier = modifier.testTag(C.Tag.onboarding_role_screen)) { innerPadding ->
    OnboardingStep(
        title = stringResource(R.string.onboarding_role_title),
        subtitle = stringResource(R.string.onboarding_role_subtitle),
        innerPadding = innerPadding,
        actions = {
          SignedInAs(email, canSwitchAccount, onUseAnotherAccount)
          Spacer(Modifier.height(Spacing.xs))
          LakeButton(
              text = stringResource(R.string.onboarding_continue),
              onClick = onContinue,
              enabled = selectedRole != null,
              modifier = Modifier.fillMaxWidth().testTag(C.Tag.onboarding_continue_button),
          )
        },
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        RoleCard(
            title = stringResource(R.string.onboarding_role_patient),
            description = stringResource(R.string.onboarding_role_patient_description),
            selected = selectedRole == Role.PATIENT,
            onClick = { onRoleSelected(Role.PATIENT) },
            modifier = Modifier.testTag(C.Tag.onboarding_role_patient),
        )
        RoleCard(
            title = stringResource(R.string.onboarding_role_caregiver),
            description = stringResource(R.string.onboarding_role_caregiver_description),
            selected = selectedRole == Role.CAREGIVER,
            onClick = { onRoleSelected(Role.CAREGIVER) },
            modifier = Modifier.testTag(C.Tag.onboarding_role_caregiver),
        )
      }
    }
  }
}

/**
 * A selectable card for one role (Figma "I'm a patient"): a radio, then the role and what it means.
 * Chosen, it takes the warm secondary container with a secondary outline; otherwise a plain card.
 */
@Composable
private fun RoleCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
      shape = MaterialTheme.shapes.large,
      color = if (selected) colors.secondaryContainer else colors.surfaceContainerLowest,
      border = if (selected) BorderStroke(SELECTED_BORDER, colors.secondary) else null,
      modifier =
          modifier
              .fillMaxWidth()
              .selectable(
                  selected = selected,
                  role = SemanticsRole.RadioButton,
                  onClick = onClick,
              ),
  ) {
    Row(
        modifier = Modifier.padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
      // Only drawn: the whole card is the radio button for TalkBack and taps
      RadioButton(
          selected = selected,
          onClick = null,
          colors =
              RadioButtonDefaults.colors(
                  selectedColor = colors.secondary,
                  unselectedColor = colors.outline,
              ),
      )
      Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(Spacing.xs),
      ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        )
      }
    }
  }
}

// The Figma's outline of the chosen role
private val SELECTED_BORDER = 1.5.dp
