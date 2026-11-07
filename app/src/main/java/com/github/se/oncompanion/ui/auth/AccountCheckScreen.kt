// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.navigation.StartViewModel

/**
 * Shown at startup when the signed-in user's account couldn't be checked in time (nothing saved on
 * the phone and no server yet), so the app doesn't guess whether they are a patient or a caregiver.
 * Retry decides the start again.
 *
 * @param viewModel started from [Route.ACCOUNT_CHECK], so it only decides on retry
 * @param onChecked called with the route to start on once the account is known
 */
@Composable
fun AccountCheckScreen(viewModel: StartViewModel, onChecked: (String) -> Unit) {
  val route by viewModel.startRoute.collectAsStateWithLifecycle()
  LaunchedEffect(route) { route?.takeIf { it != Route.ACCOUNT_CHECK }?.let(onChecked) }
  AccountCheckContent(isChecking = route != Route.ACCOUNT_CHECK, onRetry = viewModel::retry)
}

/** Stateless content of the account check: the message and Retry, or a spinner while checking. */
@Composable
fun AccountCheckContent(isChecking: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
  Scaffold(modifier = modifier.testTag(C.Tag.account_check_screen)) { innerPadding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (isChecking) {
        CircularProgressIndicator(Modifier.testTag(C.Tag.account_check_loading))
      } else {
        Text(
            text = stringResource(R.string.account_check_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.account_check_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.testTag(C.Tag.account_check_retry)) {
          Text(stringResource(R.string.sign_in_retry))
        }
      }
    }
  }
}
