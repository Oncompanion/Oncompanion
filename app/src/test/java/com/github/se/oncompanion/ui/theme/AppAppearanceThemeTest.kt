// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.settings.AppearancePreference
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.common.LakeTopAppBar
import java.time.LocalTime
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Checks that the root wrapper applies both palettes and tracks Android configuration changes. */
@RunWith(AndroidJUnit4::class)
class AppAppearanceThemeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  @Config(qualifiers = "notnight")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun darkChoiceOnLightPhoneDrawsDarkLake() {
    assertLake(AppearancePreference.DARK, Color(0xFF463C66))
  }

  @Test
  @Config(qualifiers = "night")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun lightChoiceOnDarkPhoneDrawsLightLake() {
    assertLake(AppearancePreference.LIGHT, Color(0xFFC3B5E0))
  }

  private fun assertLake(choice: AppearancePreference, expected: Color) {
    var activity: Activity? = null
    compose.setContent {
      AppAppearanceTheme(choice) {
        var context = LocalContext.current
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        activity = context as? Activity
        LakeTopAppBar(
            "Settings",
            navigationIcon = {},
            modifier = Modifier.width(390.dp).testTag("bar"),
            time = LocalTime.NOON,
        )
      }
    }
    val bar = compose.onNodeWithTag("bar").captureToImage().toPixelMap()
    assertSameColor("Salève", expected, bar[bar.width - 40, bar.height - 20])
    assertNotNull(activity)
  }

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
