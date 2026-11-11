// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeTest {

  @get:Rule val composeTestRule = createComposeRule()

  /** The colors one theme gives its content. */
  private class ThemeColors(val scheme: ColorScheme, val lake: LakeColors)

  /** One color role: how to read it, then its Light and Dark values in the Figma variables. */
  private class Role(
      val name: String,
      val read: (ThemeColors) -> Color,
      val light: Long,
      val dark: Long,
  )

  // The "Oncompanion" variable collection of the Figma (Brand & Theme), modes Light and Dark.
  private val roles =
      listOf(
          Role("Primary", { it.scheme.primary }, 0xFF2F6F7E, 0xFF9AD0DC),
          Role("On Primary", { it.scheme.onPrimary }, 0xFFFFFFFF, 0xFF00363F),
          Role("Primary Container", { it.scheme.primaryContainer }, 0xFFD7E7EA, 0xFF134F5B),
          Role("On Primary Container", { it.scheme.onPrimaryContainer }, 0xFF0E3640, 0xFFC8EAF1),
          Role("Secondary", { it.scheme.secondary }, 0xFF8A4F2E, 0xFFF2B994),
          Role("On Secondary", { it.scheme.onSecondary }, 0xFFFFFFFF, 0xFF4A2611),
          Role("Secondary Container", { it.scheme.secondaryContainer }, 0xFFFADBC8, 0xFF6B3B1F),
          Role(
              "On Secondary Container",
              { it.scheme.onSecondaryContainer },
              0xFF3A1E0E,
              0xFFFCDCC8,
          ),
          Role("Tertiary", { it.scheme.tertiary }, 0xFF6A5A8C, 0xFFCFC0F2),
          Role("On Tertiary", { it.scheme.onTertiary }, 0xFFFFFFFF, 0xFF382B57),
          Role("Tertiary Container", { it.scheme.tertiaryContainer }, 0xFFE7DEF8, 0xFF514272),
          Role("On Tertiary Container", { it.scheme.onTertiaryContainer }, 0xFF251A3F, 0xFFEADDFF),
          Role("Error", { it.scheme.error }, 0xFFB3261E, 0xFFF2B8B5),
          Role("On Error", { it.scheme.onError }, 0xFFFFFFFF, 0xFF601410),
          Role("Error Container", { it.scheme.errorContainer }, 0xFFF9DEDC, 0xFF8C1D18),
          Role("On Error Container", { it.scheme.onErrorContainer }, 0xFF410E0B, 0xFFF9DEDC),
          Role("Surface", { it.scheme.surface }, 0xFFFBF8F4, 0xFF151B1E),
          Role("Background", { it.scheme.background }, 0xFFFBF8F4, 0xFF151B1E),
          Role("On Surface", { it.scheme.onSurface }, 0xFF1F2A30, 0xFFE3E6E7),
          Role("On Background", { it.scheme.onBackground }, 0xFF1F2A30, 0xFFE3E6E7),
          Role("On Surface Variant", { it.scheme.onSurfaceVariant }, 0xFF52606A, 0xFFBCC6CB),
          Role("Outline", { it.scheme.outline }, 0xFF7A858C, 0xFF879196),
          Role("Outline Variant", { it.scheme.outlineVariant }, 0xFFE0DAD2, 0xFF3B4448),
          Role(
              "Surface Container Lowest",
              { it.scheme.surfaceContainerLowest },
              0xFFFFFFFF,
              0xFF0F1416,
          ),
          Role("Surface Container Low", { it.scheme.surfaceContainerLow }, 0xFFF7F3EE, 0xFF1B2225),
          Role("Surface Container", { it.scheme.surfaceContainer }, 0xFFF2EDE7, 0xFF1F2629),
          Role(
              "Surface Container High",
              { it.scheme.surfaceContainerHigh },
              0xFFECE6DF,
              0xFF293134,
          ),
          Role(
              "Surface Container Highest",
              { it.scheme.surfaceContainerHighest },
              0xFFE6E0D8,
              0xFF343C3F,
          ),
          Role("Inverse Surface", { it.scheme.inverseSurface }, 0xFF2E363B, 0xFFE3E6E7),
          Role("Inverse On Surface", { it.scheme.inverseOnSurface }, 0xFFF2EEE9, 0xFF2A3134),
          Role("Inverse Primary", { it.scheme.inversePrimary }, 0xFF9AD0DC, 0xFF2F6F7E),
          Role("Primary Text", { it.lake.primaryText }, 0xFF2F6F7E, 0xFF9AD0DC),
          Role("Horizon/Dawn Top", { it.lake.dawn.top }, 0xFFF6D2B8, 0xFF3A3346),
          Role("Horizon/Dawn Bottom", { it.lake.dawn.bottom }, 0xFFC9D7DE, 0xFF34495A),
          Role("Horizon/Day Top", { it.lake.day.top }, 0xFFCFE5EA, 0xFF1F3B47),
          Role("Horizon/Day Bottom", { it.lake.day.bottom }, 0xFFA9CFD8, 0xFF2D5866),
          Role("Horizon/Dusk Top", { it.lake.dusk.top }, 0xFFE3D9F0, 0xFF2E2745),
          Role("Horizon/Dusk Bottom", { it.lake.dusk.bottom }, 0xFFC3B5E0, 0xFF1D1A2E),
          Role("Horizon/On Horizon", { it.lake.onHorizon }, 0xFF1F2A30, 0xFFE3E6E7),
      )

  @Test
  fun everyRole_matchesTheFigmaVariables_inLightAndDark() {
    lateinit var light: ThemeColors
    lateinit var dark: ThemeColors
    composeTestRule.setContent {
      OncompanionTheme(darkTheme = false) {
        light = ThemeColors(MaterialTheme.colorScheme, MaterialTheme.lakeColors)
      }
      OncompanionTheme(darkTheme = true) {
        dark = ThemeColors(MaterialTheme.colorScheme, MaterialTheme.lakeColors)
      }
    }
    composeTestRule.waitForIdle()

    for (role in roles) {
      assertEquals("${role.name} (Light)", Color(role.light), role.read(light))
      assertEquals("${role.name} (Dark)", Color(role.dark), role.read(dark))
    }
  }

  @Test
  fun lakeColors_withoutTheTheme_areTheLightOnes() {
    lateinit var lake: LakeColors
    composeTestRule.setContent { lake = MaterialTheme.lakeColors }
    composeTestRule.waitForIdle()

    assertEquals(Color(0xFF2F6F7E), lake.primaryText)
    assertEquals(Color(0xFFCFE5EA), lake.day.top)
  }
}
