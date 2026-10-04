package com.github.se.oncompanion.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.resources.C

/**
 * First onboarding step (Figma "US-01 / Onboarding – Role"): patient or caregiver. The caregiver
 * side isn't available yet, so its card is shown disabled with "Coming soon".
 *
 * @param onContinue called to go to the next step
 */
@Composable
fun RoleScreen(viewModel: OnboardingViewModel, onContinue: () -> Unit) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  RoleContent(
      selectedRole = uiState.role,
      onRoleSelected = viewModel::selectRole,
      onContinue = onContinue,
  )
}

/** Stateless content of the role step. */
@Composable
fun RoleContent(
    selectedRole: Role?,
    onRoleSelected: (Role) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Scaffold(modifier = modifier.testTag(C.Tag.onboarding_role_screen)) { innerPadding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
    ) {
      Text(
          text = stringResource(R.string.onboarding_role_title),
          style = MaterialTheme.typography.headlineMedium,
      )
      Spacer(Modifier.height(8.dp))
      Text(
          text = stringResource(R.string.onboarding_role_subtitle),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(32.dp))
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RoleCard(
            title = stringResource(R.string.onboarding_role_patient),
            description = stringResource(R.string.onboarding_role_patient_description),
            selected = selectedRole == Role.PATIENT,
            enabled = true,
            onClick = { onRoleSelected(Role.PATIENT) },
            modifier = Modifier.testTag(C.Tag.onboarding_role_patient),
        )
        RoleCard(
            title = stringResource(R.string.onboarding_role_caregiver),
            description = stringResource(R.string.onboarding_role_caregiver_description),
            selected = false,
            enabled = false,
            onClick = {},
            modifier = Modifier.testTag(C.Tag.onboarding_role_caregiver),
        )
      }
      Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
      Button(
          onClick = onContinue,
          enabled = selectedRole != null,
          modifier =
              Modifier.fillMaxWidth()
                  .heightIn(min = 56.dp)
                  .testTag(C.Tag.onboarding_continue_button),
      ) {
        Text(stringResource(R.string.onboarding_continue))
      }
    }
  }
}

/** A selectable card for one role; a disabled card shows a "Coming soon" chip. */
@Composable
private fun RoleCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
      shape = RoundedCornerShape(16.dp),
      color = if (selected) colors.primaryContainer else colors.surface,
      border =
          if (selected) BorderStroke(2.dp, colors.primary)
          else BorderStroke(1.dp, colors.outlineVariant),
      modifier =
          modifier
              .fillMaxWidth()
              .selectable(
                  selected = selected,
                  enabled = enabled,
                  role = SemanticsRole.RadioButton,
                  onClick = onClick,
              ),
  ) {
    Row(
        modifier = Modifier.padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Column(
          modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.38f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
      }
      if (!enabled) {
        SuggestionChip(
            onClick = {},
            enabled = false,
            label = { Text(stringResource(R.string.onboarding_coming_soon)) },
        )
      }
    }
  }
}
