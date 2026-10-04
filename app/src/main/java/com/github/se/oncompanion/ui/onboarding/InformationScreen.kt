package com.github.se.oncompanion.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C

/**
 * Second onboarding step (Figma "US-01 / Onboarding – Information"): first name (required), family
 * name and cancer type (optional, with suggestions). Continue saves the profile.
 *
 * @param onSaved called once the profile is saved: onboarding is done
 */
@Composable
fun InformationScreen(viewModel: OnboardingViewModel, onSaved: () -> Unit) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(uiState.isSaved) { if (uiState.isSaved) onSaved() }

  InformationContent(
      uiState = uiState,
      onFirstNameChange = viewModel::onFirstNameChange,
      onFamilyNameChange = viewModel::onFamilyNameChange,
      onCancerTypeChange = viewModel::onCancerTypeChange,
      onContinue = viewModel::saveProfile,
      onErrorShown = viewModel::clearError,
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
) {
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
      modifier = modifier.testTag(C.Tag.onboarding_information_screen),
      snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
    ) {
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
        OutlinedTextField(
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
        OutlinedTextField(
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
        CancerTypeField(
            value = uiState.cancerType,
            onValueChange = onCancerTypeChange,
            modifier = Modifier.fillMaxWidth(),
        )
      }
      Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
      Button(
          onClick = onContinue,
          enabled = uiState.canSave,
          modifier =
              Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(C.Tag.onboarding_save_button),
      ) {
        if (uiState.isSaving) {
          Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.fillMaxSize().testTag(C.Tag.onboarding_saving),
            )
          }
        } else {
          Text(stringResource(R.string.onboarding_continue))
        }
      }
    }
  }
}

private fun OnboardingError.messageRes(): Int =
    when (this) {
      OnboardingError.NOT_SIGNED_IN -> R.string.onboarding_error_not_signed_in
      OnboardingError.SAVE_FAILED -> R.string.onboarding_error_save_failed
    }
