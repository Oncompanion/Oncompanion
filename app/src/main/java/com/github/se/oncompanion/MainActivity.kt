// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.session.PreferencesCleanupJournal
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.navigation.StartViewModel
import com.github.se.oncompanion.ui.theme.OncompanionTheme

/** Installs session recovery before authenticated navigation or Firestore operations start. */
class MainActivity : ComponentActivity() {

  private val startViewModel: StartViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    // Android's splash screen stays until we know where to start (sign-in, onboarding or Overview)
    val splashScreen = installSplashScreen()
    val journal = PreferencesCleanupJournal(getSharedPreferences("session_cleanup", MODE_PRIVATE))
    // Capture legacy queue owners before any ViewModel can start Firestore operations.
    SignOutProvider.signOut.initialize(journal)
    // Never restore an authenticated navigation stack after interrupted or completed sign-out.
    val restore =
        !journal.pending &&
            runCatching { AuthRepositoryProvider.repository.currentUser != null }
                .getOrDefault(false)
    super.onCreate(if (restore) savedInstanceState else null)
    splashScreen.setKeepOnScreenCondition { startViewModel.startRoute.value == null }
    // Transparent status and navigation bars: the app's background shows behind them, instead of
    // the old XML theme's purple status bar. Screens use Scaffold, which keeps content clear of
    // them.
    enableEdgeToEdge()
    setContent {
      OncompanionTheme {
        Surface(
            modifier = Modifier.fillMaxSize().testTag(C.Tag.main_screen_container),
            color = MaterialTheme.colorScheme.background,
        ) {
          val startRoute by startViewModel.startRoute.collectAsStateWithLifecycle()
          startRoute?.let { AppNavHost(startRoute = it) }
        }
      }
    }
  }
}
