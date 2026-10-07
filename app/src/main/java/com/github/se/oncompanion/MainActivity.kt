package com.github.se.oncompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.theme.OncompanionTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
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
          AppNavHost()
        }
      }
    }
  }
}
