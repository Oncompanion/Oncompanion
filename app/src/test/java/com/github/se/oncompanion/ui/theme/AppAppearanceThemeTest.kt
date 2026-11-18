// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.theme

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.settings.AppearancePreference
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Checks that the root wrapper applies both palettes and tracks Android configuration changes. */
@RunWith(AndroidJUnit4::class)
class AppAppearanceThemeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun allOverridesApplyImmediatelyWithoutDiscardingRememberedState() {
    var choice by mutableStateOf(AppearancePreference.FOLLOW_DEVICE)
    var systemDark by mutableStateOf(false)
    var renders = 0
    var remembered: Any? = null
    var currentSurface = SurfaceLight
    compose.setContent {
      val configuration =
          Configuration(LocalConfiguration.current).apply {
            uiMode =
                (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (systemDark) Configuration.UI_MODE_NIGHT_YES
                    else Configuration.UI_MODE_NIGHT_NO
          }
      CompositionLocalProvider(LocalConfiguration provides configuration) {
        AppAppearanceTheme(choice) {
          val identity = remember { Any() }
          val surface = MaterialTheme.colorScheme.surface
          SideEffect {
            remembered = identity
            currentSurface = surface
            renders++
          }
        }
      }
    }
    val original = remembered
    for (dark in listOf(false, true)) for (preference in AppearancePreference.entries) {
      compose.runOnIdle {
        choice = preference
        systemDark = dark
      }
      compose.waitForIdle()
      assertEquals(
          if (preference.usesDarkTheme(dark)) SurfaceDark else SurfaceLight,
          currentSurface,
      )
      assertSame(original, remembered)
    }
    assertTrue(renders > 1)
  }
}
