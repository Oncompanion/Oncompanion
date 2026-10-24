// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C

/**
 * "Join a care circle" (US-15): a caregiver enters the invite code the patient shared. Opened at
 * the end of a caregiver's onboarding. Joining itself comes next, so Join is disabled for now.
 *
 * @param onNoCode called when the caregiver leaves without a code
 */
@Composable
fun JoinCareCircleScreen(onNoCode: () -> Unit) {
  // Kept here until the screen has a ViewModel that joins with it
  var code by rememberSaveable { mutableStateOf("") }
  JoinCareCircleContent(code = code, onCodeChange = { code = it }, onNoCode = onNoCode)
}

/** Stateless content of the "Join a care circle" screen. */
@Composable
fun JoinCareCircleContent(
    code: String,
    onCodeChange: (String) -> Unit,
    onNoCode: () -> Unit,
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
      // The invite code, with an example below it
      OutlinedTextField(
          value = code,
          onValueChange = onCodeChange,
          label = { Text(stringResource(R.string.join_circle_code)) },
          supportingText = { Text(stringResource(R.string.join_circle_code_hint)) },
          singleLine = true,
          keyboardOptions =
              KeyboardOptions(
                  capitalization = KeyboardCapitalization.Characters,
                  autoCorrectEnabled = false,
                  keyboardType = KeyboardType.Ascii,
                  imeAction = ImeAction.Done,
              ),
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.join_circle_code_field),
      )
      Spacer(Modifier.height(24.dp))
      // Join is disabled until joining is built
      Button(
          onClick = {},
          enabled = false,
          modifier =
              Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(C.Tag.join_circle_join_button),
      ) {
        Text(stringResource(R.string.join_circle_join_coming_soon))
      }
      TextButton(
          onClick = onNoCode,
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.join_circle_no_code),
      ) {
        Text(stringResource(R.string.join_circle_no_code))
      }
    }
  }
}
