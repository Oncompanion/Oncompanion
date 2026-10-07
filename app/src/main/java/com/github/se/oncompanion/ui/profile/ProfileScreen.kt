package com.github.se.oncompanion.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val memberSinceFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

/** Profile screen backed by the currently signed-in user's profile. */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = viewModel { ProfileViewModel() },
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  ProfileContent(
      uiState = uiState,
      onBack = onBack,
      onRetry = viewModel::retry,
      onEdit = onEdit,
      modifier = modifier,
  )
}

/** Stateless Profile screen content, so every state can be tested without Firebase. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(
    uiState: ProfileUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.profile_screen),
      topBar = {
        MediumTopAppBar(
            title = { Text(stringResource(R.string.profile_screen_title)) },
            navigationIcon = {
              IconButton(
                  onClick = onBack,
                  modifier = Modifier.testTag(C.Tag.profile_back),
              ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.profile_back),
                )
              }
            },
        )
      },
  ) { innerPadding ->
    when (uiState) {
      ProfileUiState.Loading ->
          ProfileLoading(modifier = Modifier.fillMaxSize().padding(innerPadding))
      ProfileUiState.SignedOut ->
          ProfileMessage(
              message = stringResource(R.string.profile_signed_out),
              testTag = C.Tag.profile_signed_out,
              modifier = Modifier.fillMaxSize().padding(innerPadding),
          )
      ProfileUiState.MissingProfile ->
          ProfileMessage(
              message = stringResource(R.string.profile_missing),
              testTag = C.Tag.profile_missing,
              modifier = Modifier.fillMaxSize().padding(innerPadding),
          )
      ProfileUiState.Error ->
          ProfileError(
              modifier = Modifier.fillMaxSize().padding(innerPadding),
              onRetry = onRetry,
          )
      is ProfileUiState.Loaded ->
          ProfileDetailsContent(
              details = uiState.details,
              onEdit = onEdit,
              modifier = Modifier.fillMaxSize().padding(innerPadding),
          )
    }
  }
}

@Composable
private fun ProfileDetailsContent(
    details: ProfileDetails,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier =
          modifier
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 16.dp, vertical = 24.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(C.Tag.profile_header),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
      Row(
          modifier = Modifier.padding(20.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
            modifier =
                Modifier.size(56.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .testTag(C.Tag.profile_initial),
            contentAlignment = Alignment.Center,
        ) {
          Text(
              text = details.firstName.take(1).uppercase(Locale.ENGLISH),
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          val memberLabel =
              details.memberSince?.let {
                stringResource(
                    R.string.profile_member_since,
                    memberSinceFormatter.format(it.atZone(ZoneId.systemDefault())),
                )
              } ?: stringResource(R.string.profile_member)
          Text(
              text =
                  listOfNotNull(details.firstName, details.familyName?.takeIf(String::isNotBlank))
                      .joinToString(" "),
              style = MaterialTheme.typography.titleLarge,
              modifier = Modifier.testTag(C.Tag.profile_name),
          )
          Text(
              text = memberLabel,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.testTag(C.Tag.profile_member_since),
          )
        }
      }
    }

    Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().testTag(C.Tag.profile_edit)) {
      Text(stringResource(R.string.edit_profile_title))
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag(C.Tag.profile_information),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      Text(
          text = stringResource(R.string.profile_personal_information),
          style = MaterialTheme.typography.titleMedium,
      )
      ProfileField(
          label = stringResource(R.string.profile_first_name),
          value = details.firstName,
          testTag = C.Tag.profile_first_name,
      )
      details.familyName?.takeIf(String::isNotBlank)?.let { familyName ->
        ProfileField(
            label = stringResource(R.string.profile_family_name),
            value = familyName,
            testTag = C.Tag.profile_family_name,
        )
      }
      details.email?.takeIf(String::isNotBlank)?.let { email ->
        ProfileField(
            label = stringResource(R.string.profile_email),
            value = email,
            testTag = C.Tag.profile_email,
        )
      }
      details.cancerType?.takeIf(String::isNotBlank)?.let { cancerType ->
        ProfileField(
            label = stringResource(R.string.cancer_type_label),
            value = cancerType,
            testTag = C.Tag.profile_cancer_type,
        )
      }
    }
  }
}

@Composable
private fun ProfileField(label: String, value: String, testTag: String) {
  Column(
      modifier = Modifier.fillMaxWidth().testTag(testTag),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = value, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun ProfileMessage(
    message: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(testTag),
    )
  }
}

@Composable
private fun ProfileLoading(modifier: Modifier = Modifier) {
  Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(Modifier.testTag(C.Tag.profile_loading))
  }
}

@Composable
private fun ProfileError(modifier: Modifier = Modifier, onRetry: () -> Unit) {
  Column(
      modifier = modifier.padding(24.dp).testTag(C.Tag.profile_error),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        text = stringResource(R.string.profile_error),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRetry, modifier = Modifier.testTag(C.Tag.profile_retry)) {
      Text(stringResource(R.string.profile_retry))
    }
  }
}
