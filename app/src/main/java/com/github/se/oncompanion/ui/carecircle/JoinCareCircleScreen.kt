// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C

/**
 * "Join a care circle" (US-15): a caregiver enters the invite code the patient shared, then sees
 * whose circle they joined. It is also where a caregiver without a circle waits for a code: opened
 * at the end of their onboarding, and each time they open the app until they join one.
 *
 * @param onDone called with Continue, once joined
 * @param onOpenProfile opens the profile, from which the caregiver can also sign out
 */
@Composable
fun JoinCareCircleScreen(
    viewModel: JoinCareCircleViewModel,
    onDone: () -> Unit,
    onOpenProfile: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  JoinCareCircleContent(
      uiState = uiState,
      onCodeChange = viewModel::onCodeChange,
      onJoin = viewModel::join,
      onDone = onDone,
      onOpenProfile = onOpenProfile,
  )
}

/** Stateless content of the "Join a care circle" screen. */
@Composable
fun JoinCareCircleContent(
    uiState: JoinCareCircleUiState,
    onCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
    onDone: () -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Scaffold(
      // Ends the screen at the keyboard (the activity is adjustResize): Join can be scrolled to
      modifier = modifier.testTag(C.Tag.join_circle_screen).imePadding()
  ) { innerPadding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(innerPadding)
                // The margins scroll with the content, so a phone held sideways keeps the field
                // visible above the keyboard
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
    ) {
      // The profile, so a caregiver waiting for a code can still sign out. It scrolls with the
      // content instead of a fixed top bar, which would take the room left above the keyboard
      IconButton(
          onClick = onOpenProfile,
          modifier = Modifier.align(Alignment.End).testTag(C.Tag.join_circle_profile),
      ) {
        Icon(
            painter = painterResource(R.drawable.ic_profile),
            contentDescription = stringResource(R.string.profile_title),
        )
      }
      val joinedName = uiState.joinedPatientFirstName
      if (joinedName != null) Joined(joinedName, uiState.wasAlreadyMember, onDone)
      else CodeForm(uiState, onCodeChange, onJoin)
    }
  }
}

/** The title, the invite code field, Join, and what to do without a code yet. */
@Composable
private fun CodeForm(
    uiState: JoinCareCircleUiState,
    onCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
) {
  Text(
      text = stringResource(R.string.join_circle_title),
      style = MaterialTheme.typography.headlineMedium,
  )
  Spacer(Modifier.height(8.dp))
  Text(
      text = stringResource(R.string.join_circle_subtitle),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Spacer(Modifier.height(32.dp))
  // The invite code: an example below it, or why the last try failed
  val error = uiState.error
  OutlinedTextField(
      value = uiState.code,
      onValueChange = onCodeChange,
      label = { Text(stringResource(R.string.join_circle_code)) },
      supportingText = {
        Text(stringResource(error?.messageRes() ?: R.string.join_circle_code_hint))
      },
      isError = error != null,
      enabled = !uiState.isJoining,
      singleLine = true,
      keyboardOptions =
          KeyboardOptions(
              capitalization = KeyboardCapitalization.Characters,
              autoCorrectEnabled = false,
              keyboardType = KeyboardType.Ascii,
              imeAction = ImeAction.Done,
          ),
      keyboardActions = KeyboardActions(onDone = { onJoin() }),
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.join_circle_code_field),
  )
  Spacer(Modifier.height(24.dp))
  // Join: a spinner while the server checks the code, same button size
  Button(
      onClick = onJoin,
      enabled = uiState.canJoin,
      modifier =
          Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(C.Tag.join_circle_join_button),
  ) {
    if (uiState.isJoining) {
      Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.fillMaxSize().testTag(C.Tag.join_circle_joining),
        )
      }
    } else {
      Text(stringResource(R.string.join_circle_join))
    }
  }
  Spacer(Modifier.height(24.dp))
  // Without a code yet, the caregiver waits here: the app opens this screen until they join
  Text(
      text = stringResource(R.string.join_circle_no_code_hint),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.testTag(C.Tag.join_circle_no_code),
  )
}

/** Whose circle the caregiver is now in, and Continue. */
@Composable
private fun Joined(patientFirstName: String, wasAlreadyMember: Boolean, onContinue: () -> Unit) {
  Text(
      text =
          stringResource(
              if (wasAlreadyMember) R.string.join_circle_already_member
              else R.string.join_circle_joined,
              patientFirstName,
          ),
      style = MaterialTheme.typography.headlineMedium,
      modifier = Modifier.testTag(C.Tag.join_circle_joined),
  )
  Spacer(Modifier.height(8.dp))
  Text(
      text = stringResource(R.string.join_circle_joined_hint),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Spacer(Modifier.height(32.dp))
  Button(
      onClick = onContinue,
      modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(C.Tag.join_circle_continue),
  ) {
    Text(stringResource(R.string.join_circle_continue))
  }
}

/** The message shown under the field for each error. */
private fun JoinCareCircleError.messageRes(): Int =
    when (this) {
      JoinCareCircleError.INVALID_CODE -> R.string.join_circle_error_invalid
      JoinCareCircleError.OFFLINE -> R.string.join_circle_error_offline
      JoinCareCircleError.NOT_SIGNED_IN -> R.string.join_circle_error_not_signed_in
      JoinCareCircleError.FAILED -> R.string.join_circle_error_failed
    }
