// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TypeTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var typography: Typography

  /** One role of DESIGN.md's type scale: size and line height in sp, letter spacing in em. */
  private class Role(
      val name: String,
      val read: (Typography) -> TextStyle,
      val size: Int,
      val weight: FontWeight,
      val lineHeight: Int,
      val letterSpacing: Double = 0.0,
  )

  // The "typography" table of DESIGN.md (Figma text styles "Oncompanion/*")
  private val roles =
      listOf(
          Role("Display Small", { it.displaySmall }, 36, FontWeight.Bold, 44, -0.042),
          Role("Headline Large", { it.headlineLarge }, 32, FontWeight.Bold, 40, -0.0375),
          Role("Headline Medium", { it.headlineMedium }, 28, FontWeight.Bold, 36, -0.036),
          Role("Headline Small", { it.headlineSmall }, 24, FontWeight.SemiBold, 32, -0.021),
          Role("Title Large", { it.titleLarge }, 22, FontWeight.SemiBold, 28, -0.014),
          Role("Title Medium", { it.titleMedium }, 17, FontWeight.SemiBold, 24),
          Role("Title Small", { it.titleSmall }, 15, FontWeight.SemiBold, 20),
          Role("Body Large", { it.bodyLarge }, 17, FontWeight.Normal, 26),
          Role("Body Medium", { it.bodyMedium }, 15, FontWeight.Normal, 22),
          Role("Body Small", { it.bodySmall }, 13, FontWeight.Normal, 18),
          Role("Label Large", { it.labelLarge }, 15, FontWeight.SemiBold, 20),
          Role("Label Medium", { it.labelMedium }, 13, FontWeight.SemiBold, 16, 0.015),
          Role("Label Small", { it.labelSmall }, 12, FontWeight.Medium, 16, 0.025),
      )

  @Before
  fun readTheThemeTypography() {
    composeTestRule.setContent { OncompanionTheme { typography = MaterialTheme.typography } }
    composeTestRule.waitForIdle()
  }

  @Test
  fun everyRole_matchesDesignMd() {
    for (role in roles) {
      val style = role.read(typography)
      assertEquals("${role.name} size", role.size.sp, style.fontSize)
      assertEquals("${role.name} weight", role.weight, style.fontWeight)
      assertEquals("${role.name} line height", role.lineHeight.sp, style.lineHeight)
      assertEquals("${role.name} letter spacing", role.letterSpacing.em, style.letterSpacing)
    }
  }

  @Test
  fun everyMaterialRole_usesFigtree() {
    val all =
        with(typography) {
          listOf(
              displayLarge,
              displayMedium,
              displaySmall,
              headlineLarge,
              headlineMedium,
              headlineSmall,
              titleLarge,
              titleMedium,
              titleSmall,
              bodyLarge,
              bodyMedium,
              bodySmall,
              labelLarge,
              labelMedium,
              labelSmall,
          )
        }
    for (style in all) assertEquals(Figtree, style.fontFamily)
  }
}
