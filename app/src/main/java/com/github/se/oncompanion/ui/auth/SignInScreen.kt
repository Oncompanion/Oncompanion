package com.github.se.oncompanion.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C

/**
 * Sign-in screen (Figma "US-01 / Login"): the only way in is "Continue with Google". The first
 * sign-in creates the account.
 *
 * @param onSignedIn called once after a successful sign-in, with where to go next
 * @param credentialProvider shows Google's dialog; replaceable in tests
 */
@Composable
fun SignInScreen(
    onSignedIn: (AfterSignIn) -> Unit,
    viewModel: SignInViewModel = viewModel { SignInViewModel() },
    credentialProvider: GoogleCredentialProvider = rememberGoogleCredentialProvider(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val signIn = { viewModel.signIn { credentialProvider.getGoogleIdToken(context) } }

  LaunchedEffect(uiState.next) { uiState.next?.let(onSignedIn) }

  SignInContent(
      uiState = uiState,
      onSignInClick = signIn,
      onErrorShown = viewModel::clearError,
  )
}

/** The default [GoogleCredentialProvider], using the Firebase web client ID. */
@Composable
private fun rememberGoogleCredentialProvider(): GoogleCredentialProvider {
  val serverClientId = stringResource(R.string.default_web_client_id)
  return remember(serverClientId) { CredentialManagerGoogleCredentialProvider(serverClientId) }
}

/**
 * Stateless content of the sign-in screen.
 *
 * @param onSignInClick the "Continue with Google" button, also the snackbar's "Retry" action
 * @param onErrorShown called once the error snackbar is gone
 */
@Composable
fun SignInContent(
    uiState: SignInUiState,
    onSignInClick: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val errorMessage = uiState.error?.let { stringResource(it.messageRes()) }
  val retryLabel = stringResource(R.string.sign_in_retry)

  LaunchedEffect(uiState.error) {
    if (errorMessage != null) {
      val result =
          snackbarHostState.showSnackbar(
              message = errorMessage,
              actionLabel = retryLabel,
              duration = SnackbarDuration.Long,
          )
      onErrorShown()
      if (result == SnackbarResult.ActionPerformed) onSignInClick()
    }
  }

  Scaffold(
      modifier = modifier.testTag(C.Tag.sign_in_screen),
      snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    Column(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      AppLogo()
      Spacer(Modifier.height(24.dp))
      Text(
          text = stringResource(R.string.sign_in_welcome),
          style = MaterialTheme.typography.headlineMedium,
          textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(8.dp))
      Text(
          text = stringResource(R.string.sign_in_subtitle),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(32.dp))
      GoogleSignInButton(
          onClick = onSignInClick,
          enabled = !uiState.isLoading,
          modifier = Modifier.testTag(C.Tag.google_sign_in_button),
      )
      Spacer(Modifier.height(24.dp))
      Box(Modifier.size(32.dp)) {
        if (uiState.isLoading) {
          CircularProgressIndicator(Modifier.fillMaxSize().testTag(C.Tag.sign_in_loading))
        }
      }
    }
  }
}

/** The "O" app mark from the mockup, until there is a real logo. */
@Composable
private fun AppLogo() {
  Box(
      modifier =
          Modifier.size(64.dp)
              .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = "O",
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
  }
}

private fun SignInError.messageRes(): Int =
    when (this) {
      SignInError.NO_CONNECTION -> R.string.sign_in_error_no_connection
      SignInError.NO_GOOGLE_ACCOUNT -> R.string.sign_in_error_no_google_account
      SignInError.FAILED -> R.string.sign_in_error_failed
    }
