// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.ui.SMALL_PHONE
import com.github.se.oncompanion.ui.assertNoWordIsCut
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.textLayout
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.buttonLabel
import com.github.se.oncompanion.ui.theme.lakeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [LakeButton] as the Figma "Button" (Brand & Theme), style by style. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LakeButtonTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography
  private var primaryText = Color.Unspecified
  private val clicks = mutableListOf<LakeButtonStyle>()
  private var dark by mutableStateOf(false)

  /** One button per style, labeled and tagged with its style's name, on the theme's surface. */
  private fun setContent(
      enabled: Boolean = true,
      isDestructive: Boolean = false,
      isWorking: Boolean = false,
      icon: @Composable () -> Painter? = { null },
  ) {
    composeTestRule.setContent {
      OncompanionTheme(darkTheme = dark) {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        primaryText = MaterialTheme.lakeColors.primaryText
        Surface(color = MaterialTheme.colorScheme.surface) {
          Column {
            for (style in LakeButtonStyle.entries) {
              LakeButton(
                  text = style.name,
                  onClick = { clicks += style },
                  style = style,
                  enabled = enabled,
                  isDestructive = isDestructive,
                  isWorking = isWorking,
                  icon = icon(),
                  workingTestTag = "${style.name} working",
                  modifier = Modifier.testTag(style.name),
              )
            }
          }
        }
      }
    }
  }

  private fun button(style: LakeButtonStyle) = composeTestRule.onNodeWithTag(style.name)

  private fun label(style: LakeButtonStyle) =
      composeTestRule.onNodeWithText(style.name, useUnmergedTree = true)

  /** Each style's container and label colors, from the Figma and DESIGN.md. */
  private fun figmaColors(style: LakeButtonStyle): Pair<Color, Color> =
      when (style) {
        LakeButtonStyle.Filled -> colors.primary to colors.onPrimary
        LakeButtonStyle.Tonal -> colors.primaryContainer to colors.onPrimaryContainer
        LakeButtonStyle.Outlined,
        LakeButtonStyle.Text -> colors.surface to primaryText
      }

  /** What the middle of the top edge shows: the outline for an outlined button. */
  private fun figmaEdge(style: LakeButtonStyle) =
      if (style == LakeButtonStyle.Outlined) colors.outline else figmaColors(style).first

  private fun topEdge(style: LakeButtonStyle) =
      button(style).let {
        it.colorAt(it.getUnclippedBoundsInRoot().let { b -> b.right - b.left } / 2, 0.75.dp)
      }

  @Test
  fun eachStyle_isA56dpPill_inItsFigmaColors_inBothThemes_andReportsClicks() {
    setContent()

    for (theme in listOf(false, true)) {
      dark = theme
      composeTestRule.waitForIdle()
      for (style in LakeButtonStyle.entries) {
        val (container, content) = figmaColors(style)
        button(style).assertHeightIsEqualTo(56.dp).assertIsEnabled()
        label(style).assertTextStyle(typography.buttonLabel, content)
        // Left of the label, inside the rounded end
        val where = "$style, dark $theme"
        assertSameColor("$where container", container, button(style).colorAt(10.dp, 28.dp))
        assertSameColor("$where edge", figmaEdge(style), topEdge(style))
      }
    }
    for (style in LakeButtonStyle.entries) button(style).performClick()
    assertEquals(LakeButtonStyle.entries, clicks)
  }

  @Test
  fun disabled_keepsItsColors_at38PercentOpacity_andIgnoresTaps() {
    setContent(enabled = false)

    for (style in LakeButtonStyle.entries) {
      button(style).assertIsNotEnabled().performClick()
      label(style).assertTextStyle(typography.buttonLabel, figmaColors(style).second)
      val faded = lerp(colors.surface, figmaColors(style).first, 0.38f)
      assertSameColor("$style container", faded, button(style).colorAt(10.dp, 28.dp))
      assertSameColor("$style edge", lerp(colors.surface, figmaEdge(style), 0.38f), topEdge(style))
    }
    assertTrue(clicks.isEmpty())
  }

  @Test
  fun working_showsASpinnerInsteadOfTheLabel_atFullColor_andIgnoresTaps() {
    setContent(isWorking = true)
    val filled = LakeButtonStyle.Filled

    composeTestRule.onNodeWithTag("Filled working", useUnmergedTree = true).assertIsDisplayed()
    button(filled).assert(hasText(filled.name).not()).assertIsNotEnabled().performClick()
    assertSameColor("container", colors.primary, button(filled).colorAt(10.dp, 28.dp))
    assertTrue(clicks.isEmpty())
  }

  // Only the destructive action of a dialog is red, and not while it can't be tapped
  @Test
  fun destructive_isRed_onlyWhileItCanBeTapped() {
    setContent(isDestructive = true)
    label(LakeButtonStyle.Text).assertTextStyle(typography.buttonLabel, colors.error)
    label(LakeButtonStyle.Outlined).assertTextStyle(typography.buttonLabel, colors.error)
  }

  @Test
  fun destructive_whileDisabled_isPrimaryText() {
    setContent(isDestructive = true, enabled = false)
    label(LakeButtonStyle.Text).assertTextStyle(typography.buttonLabel, primaryText)
  }

  /** How far the label of [style] starts from its button's left edge. */
  private fun labelInset(style: LakeButtonStyle) =
      label(style).getUnclippedBoundsInRoot().left - button(style).getUnclippedBoundsInRoot().left

  @Test
  fun withoutAnIcon_theLabelIs24dpFromTheEdge() {
    setContent()

    assertEquals(24.dp, labelInset(LakeButtonStyle.Filled))
  }

  @Test
  fun withAnIcon_itComesFirst_24dpFromTheEdge_24dpWide_8dpBeforeTheLabel() {
    setContent(icon = { painterResource(R.drawable.ic_add) })

    assertEquals(24.dp + 24.dp + 8.dp, labelInset(LakeButtonStyle.Filled))
  }

  @Test
  @Config(fontScale = 2f)
  fun at200PercentFont_growsToKeepTheWholeLabel() {
    setContent()

    for (style in LakeButtonStyle.entries) {
      val box = button(style).getUnclippedBoundsInRoot()
      val text = label(style).getUnclippedBoundsInRoot()
      button(style).assertHeightIsAtLeast(56.dp)
      assertTrue("$style label inside", text.top >= box.top && text.bottom <= box.bottom)
    }
  }

  // As a detail's Delete, full width between the screen's margins: the longest label wraps whole
  @Test
  @Config(qualifiers = SMALL_PHONE, fontScale = 2f)
  fun at200PercentFont_aLongLabelOnANarrowPhone_staysWhole() {
    composeTestRule.setContent {
      OncompanionTheme {
        LakeButton(
            text = LONG_LABEL,
            onClick = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).testTag(LONG_LABEL),
        )
      }
    }

    val label = composeTestRule.onNodeWithText(LONG_LABEL, useUnmergedTree = true)
    assertEquals("label lines", 2, label.textLayout().lineCount)
    label.assertNoWordIsCut()
    val text = label.getUnclippedBoundsInRoot()
    val box = composeTestRule.onNodeWithTag(LONG_LABEL).getUnclippedBoundsInRoot()
    assertTrue("label $text in $box", text.left >= box.left && text.right <= box.right)
    assertTrue("label $text in $box", text.top >= box.top && text.bottom <= box.bottom)
  }

  private companion object {
    const val LONG_LABEL = "Delete prescription"
  }
}
