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
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.navigation.StartViewModel
import com.github.se.oncompanion.ui.theme.OncompanionTheme

class MainActivity : ComponentActivity() {

  private val startViewModel: StartViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    // Android's splash screen stays until we know where to start (sign-in, onboarding or Overview)
    val splashScreen = installSplashScreen()
    super.onCreate(savedInstanceState)
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
