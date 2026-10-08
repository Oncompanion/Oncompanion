package com.github.se.oncompanion.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.onboarding.CancerTypeField

/** Owns no navigation controller: save and cancellation return through the host's callbacks. */
@Composable
fun EditProfileScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: EditProfileViewModel = viewModel { EditProfileViewModel() },
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(state.isSaved) {
    if (state.isSaved) {
      viewModel.consumeSaved()
      onSaved()
    }
  }
  EditProfileContent(
      state = state,
      onBack = onBack,
      onRetry = viewModel::retry,
      onFirstNameChange = viewModel::onFirstNameChange,
      onFamilyNameChange = viewModel::onFamilyNameChange,
      onCancerTypeChange = viewModel::onCancerTypeChange,
      onSave = viewModel::saveProfile,
  )
}

/** Stateless form, including loading and failure states, testable without Firebase. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileContent(
    state: EditProfileUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onFirstNameChange: (String) -> Unit,
    onFamilyNameChange: (String) -> Unit,
    onCancerTypeChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
  // Local writes finish immediately; keep toolbar, Cancel and system Back consistent in flight.
  BackHandler { if (!state.isSaving) onBack() }
  Scaffold(
      modifier = modifier.testTag(C.Tag.edit_profile_screen),
      topBar = {
        MediumTopAppBar(
            title = { Text(stringResource(R.string.edit_profile_title)) },
            navigationIcon = {
              IconButton(
                  onClick = onBack,
                  enabled = !state.isSaving,
                  modifier = Modifier.testTag(C.Tag.edit_profile_back),
              ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.profile_back))
              }
            },
        )
      },
  ) { padding ->
    if (state.status == EditProfileStatus.READY) {
      Column(
          modifier =
              Modifier.fillMaxSize()
                  .padding(padding)
                  .verticalScroll(rememberScrollState())
                  .padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        val missing = state.firstName.isBlank()
        OutlinedTextField(
            value = state.firstName,
            onValueChange = onFirstNameChange,
            enabled = state.canEdit,
            label = { Text(stringResource(R.string.onboarding_first_name)) },
            supportingText = {
              Text(
                  stringResource(
                      if (missing) R.string.onboarding_first_name_missing
                      else R.string.onboarding_required
                  )
              )
            },
            isError = missing,
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            modifier = Modifier.fillMaxWidth().testTag(C.Tag.edit_profile_first_name),
        )
        OutlinedTextField(
            value = state.familyName,
            onValueChange = onFamilyNameChange,
            enabled = state.canEdit,
            label = { Text(stringResource(R.string.profile_family_name)) },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            modifier = Modifier.fillMaxWidth().testTag(C.Tag.edit_profile_family_name),
        )
        CancerTypeField(
            value = state.cancerType,
            onValueChange = onCancerTypeChange,
            enabled = state.canEdit,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.saveFailed) {
          Text(
              stringResource(R.string.edit_profile_save_failed),
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.testTag(C.Tag.edit_profile_save_error),
          )
        }
        Button(
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth().testTag(C.Tag.edit_profile_save),
        ) {
          if (state.isSaving)
              CircularProgressIndicator(modifier = Modifier.testTag(C.Tag.edit_profile_saving))
          else Text(stringResource(R.string.edit_profile_save))
        }
        TextButton(
            onClick = onBack,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(C.Tag.edit_profile_cancel),
        ) {
          Text(stringResource(R.string.edit_profile_cancel))
        }
      }
    } else {
      Box(
          Modifier.fillMaxSize().padding(padding).padding(24.dp),
          contentAlignment = Alignment.Center,
      ) {
        when (state.status) {
          EditProfileStatus.LOADING ->
              CircularProgressIndicator(Modifier.testTag(C.Tag.edit_profile_loading))
          EditProfileStatus.SIGNED_OUT ->
              Text(
                  stringResource(R.string.profile_signed_out),
                  Modifier.testTag(C.Tag.edit_profile_unavailable),
              )
          EditProfileStatus.MISSING_PROFILE ->
              Text(
                  stringResource(R.string.profile_missing),
                  Modifier.testTag(C.Tag.edit_profile_unavailable),
              )
          EditProfileStatus.ERROR ->
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.profile_error),
                    Modifier.testTag(C.Tag.edit_profile_load_error),
                )
                Button(onClick = onRetry, modifier = Modifier.testTag(C.Tag.edit_profile_retry)) {
                  Text(stringResource(R.string.profile_retry))
                }
              }
          EditProfileStatus.READY -> Unit
        }
      }
    }
  }
}
