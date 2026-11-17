// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeSnackbarHost
import com.github.se.oncompanion.ui.common.PaperLakeScene
import com.github.se.oncompanion.ui.common.RibbonLogo
import com.github.se.oncompanion.ui.common.isCompactHeight
import com.github.se.oncompanion.ui.theme.Spacing
import java.time.LocalTime

/**
 * Sign-in screen (Figma "US-1 / Login"): the only way in is "Continue with Google". The first
 * sign-in creates the account. Under the Paper lake scene in portrait, next to it in landscape.
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
internal fun rememberGoogleCredentialProvider(): GoogleCredentialProvider {
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

  // The light of when the screen opened: the sky doesn't change while the user signs in
  val openedAt = remember { LocalTime.now() }
  val landscape = isCompactHeight()
  Scaffold(
      modifier = modifier.testTag(C.Tag.sign_in_screen),
      snackbarHost = { LakeSnackbarHost(snackbarHostState) },
      // The button is the bottom bar, so the error snackbar shows above it and never covers it
      bottomBar = {
        val bar = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
        if (landscape) {
          // Under the welcome, on the right half: the lake shows through the left half
          Row(bar) {
            Spacer(Modifier.weight(1f))
            SignInAction(uiState, onSignInClick, Modifier.weight(1f).padding(Spacing.lg))
          }
        } else {
          SignInAction(
              uiState,
              onSignInClick,
              bar.padding(horizontal = Spacing.lg).padding(top = Spacing.lg, bottom = Spacing.xl),
          )
        }
      },
  ) { innerPadding ->
    val layoutDirection = LocalLayoutDirection.current
    // The lake goes under the status bar, as in the Figma; the rest keeps clear of the system bars
    // and of the button
    val clear =
        Modifier.padding(
            end = innerPadding.calculateEndPadding(layoutDirection),
            bottom = innerPadding.calculateBottomPadding(),
        )
    if (landscape) {
      // A phone held sideways: the lake on the left half, the welcome on the right, above the
      // button
      Row(Modifier.fillMaxSize()) {
        PaperLakeScene(openedAt, Modifier.weight(1f).fillMaxHeight().testTag(C.Tag.sign_in_scene))
        Column(
            modifier =
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .then(clear)
                    .padding(top = innerPadding.calculateTopPadding())
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.lg),
            verticalArrangement = Arrangement.Center,
        ) {
          // The welcome scrolls, so it never pushes the button off the screen
          // A smaller title than in portrait, so the welcome fits on a phone held sideways
          Welcome(
              Modifier.verticalScroll(rememberScrollState()),
              titleStyle = MaterialTheme.typography.headlineMedium,
          )
        }
      }
    } else {
      Column(Modifier.fillMaxSize().then(clear)) {
        // The lake takes the space left: a short phone or a large font shrinks it, not the text
        PaperLakeScene(openedAt, Modifier.weight(1f).testTag(C.Tag.sign_in_scene))
        Welcome(
            Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.xl),
            titleStyle = MaterialTheme.typography.headlineLarge,
        )
      }
    }
  }
}

/**
 * The ribbon, the welcome and what signing in does (Figma "Welcome").
 *
 * @param titleStyle the welcome's: Headline Large as in the Figma, Medium in landscape
 */
@Composable
private fun Welcome(modifier: Modifier, titleStyle: TextStyle) {
  Column(modifier) {
    RibbonLogo(Modifier.testTag(C.Tag.sign_in_logo), size = LOGO_SIZE)
    Spacer(Modifier.height(Spacing.sm))
    // The text sits a little further in than the ribbon, whose drawing has its own margin
    Column(
        modifier = Modifier.padding(horizontal = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      Text(
          text = stringResource(R.string.sign_in_welcome),
          style = titleStyle,
      )
      Text(
          text = stringResource(R.string.sign_in_subtitle),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** "Continue with Google", with the spinner above it while signing in. */
@Composable
private fun SignInAction(
    uiState: SignInUiState,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.padding(bottom = Spacing.lg).size(SPINNER_SIZE)) {
      if (uiState.isLoading) {
        CircularProgressIndicator(Modifier.fillMaxSize().testTag(C.Tag.sign_in_loading))
      }
    }
    GoogleSignInButton(
        onClick = onSignInClick,
        enabled = !uiState.isLoading,
        modifier = Modifier.testTag(C.Tag.google_sign_in_button),
    )
  }
}

// The Figma's ribbon on Sign in, and the spinner's room above the button
private val LOGO_SIZE = 64.dp
private val SPINNER_SIZE = 32.dp

private fun SignInError.messageRes(): Int =
    when (this) {
      SignInError.NO_CONNECTION -> R.string.sign_in_error_no_connection
      SignInError.NO_GOOGLE_ACCOUNT -> R.string.sign_in_error_no_google_account
      SignInError.FAILED -> R.string.sign_in_error_failed
    }
