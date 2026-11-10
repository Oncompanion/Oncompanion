// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.GoogleCredentialProvider
import com.github.se.oncompanion.ui.auth.rememberGoogleCredentialProvider
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeSnackbarHost
import com.github.se.oncompanion.ui.common.LakeTextField
import com.github.se.oncompanion.ui.common.SavePhase

/**
 * Second onboarding step (Figma "US-01 / Onboarding – Information"): first name (required), family
 * name and, for a patient, cancer type (optional, with suggestions). Continue saves the profile.
 *
 * @param onSaved called with the chosen role once the profile is saved: onboarding is done, and
 *   what comes next depends on the role
 * @param onSignedOut called once signed out to use another account
 */
@Composable
fun InformationScreen(
    viewModel: OnboardingViewModel,
    onSaved: (Role) -> Unit,
    onSignedOut: () -> Unit,
    credentialProvider: GoogleCredentialProvider = rememberGoogleCredentialProvider(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val useAnotherAccount = accountSwitch(uiState, viewModel, credentialProvider, onSignedOut)

  // Leaves onboarding once the profile is saved
  LaunchedEffect(uiState.save) { if (uiState.save == SavePhase.SAVED) onSaved(uiState.role) }

  InformationContent(
      uiState = uiState,
      onFirstNameChange = viewModel::onFirstNameChange,
      onFamilyNameChange = viewModel::onFamilyNameChange,
      onCancerTypeChange = viewModel::onCancerTypeChange,
      onContinue = viewModel::saveProfile,
      onErrorShown = viewModel::clearError,
      onUseAnotherAccount = useAnotherAccount,
  )
}

/**
 * Stateless content of the information step.
 *
 * @param onErrorShown called once the error snackbar is gone
 */
@Composable
fun InformationContent(
    uiState: OnboardingUiState,
    onFirstNameChange: (String) -> Unit,
    onFamilyNameChange: (String) -> Unit,
    onCancerTypeChange: (String) -> Unit,
    onContinue: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    onUseAnotherAccount: () -> Unit = {},
) {
  // Shows each saving error once in a snackbar, then tells the view model it was shown
  val snackbarHostState = remember { SnackbarHostState() }
  val errorMessage = uiState.error?.let { stringResource(it.messageRes()) }
  LaunchedEffect(uiState.error) {
    if (errorMessage != null) {
      snackbarHostState.showSnackbar(errorMessage)
      onErrorShown()
    }
  }

  // The "required" error only appears once the user has edited the first name, not on arrival
  var firstNameEdited by rememberSaveable { mutableStateOf(false) }
  val firstNameMissing = firstNameEdited && uiState.firstName.isBlank()

  Scaffold(
      // Ends the screen at the keyboard (the activity is adjustResize): Continue can be scrolled to
      modifier = modifier.testTag(C.Tag.onboarding_information_screen).imePadding(),
      snackbarHost = { LakeSnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(innerPadding)
                // The margins scroll with the content: fixed ones would take half of the height
                // left above the keyboard on a small phone held sideways
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
    ) {
      // Title and subtitle
      Text(
          text = stringResource(R.string.onboarding_information_title),
          style = MaterialTheme.typography.headlineMedium,
      )
      Spacer(Modifier.height(8.dp))
      Text(
          text = stringResource(R.string.onboarding_information_subtitle),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(32.dp))
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // First name (required): "*Required" below it, or an error once it's been cleared
        LakeTextField(
            value = uiState.firstName,
            onValueChange = {
              firstNameEdited = true
              onFirstNameChange(it)
            },
            label = { Text(stringResource(R.string.onboarding_first_name)) },
            supportingText = {
              Text(
                  stringResource(
                      if (firstNameMissing) R.string.onboarding_first_name_missing
                      else R.string.onboarding_required
                  )
              )
            },
            isError = firstNameMissing,
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            modifier = Modifier.fillMaxWidth().testTag(C.Tag.onboarding_first_name_field),
        )
        // Family name (optional)
        LakeTextField(
            value = uiState.familyName,
            onValueChange = onFamilyNameChange,
            label = { Text(stringResource(R.string.onboarding_family_name)) },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            modifier = Modifier.fillMaxWidth().testTag(C.Tag.onboarding_family_name_field),
        )
        // Cancer type (optional, patients only): free text with suggestions
        if (uiState.asksCancerType) {
          CancerTypeField(
              value = uiState.cancerType,
              onValueChange = onCancerTypeChange,
              modifier = Modifier.fillMaxWidth(),
          )
        }
      }
      // Pushes Continue to the bottom of the screen when there's room
      Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
      // Continue saves the profile; enabled when a first name is filled in and nothing is saving
      LakeButton(
          text = stringResource(R.string.onboarding_continue),
          onClick = onContinue,
          enabled = uiState.canSave,
          isWorking = uiState.save == SavePhase.SAVING,
          workingTestTag = C.Tag.onboarding_saving,
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.onboarding_save_button),
      )
      Spacer(Modifier.height(8.dp))
      SignedInAs(uiState.email, uiState.canSwitchAccount, onUseAnotherAccount)
    }
  }
}

/** The message shown in the snackbar for each saving error. */
private fun OnboardingError.messageRes(): Int =
    when (this) {
      OnboardingError.NOT_SIGNED_IN -> R.string.onboarding_error_not_signed_in
      OnboardingError.SAVE_FAILED -> R.string.onboarding_error_save_failed
    }
