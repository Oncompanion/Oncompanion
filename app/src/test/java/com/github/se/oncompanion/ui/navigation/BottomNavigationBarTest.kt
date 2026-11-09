// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.navigation

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class BottomNavigationBarTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun assertOnlySelected(selectedTab: Tab) {
    Tab.entries.forEach { tab ->
      val node = composeTestRule.onNodeWithTag(tab.testTag)
      if (tab == selectedTab) node.assertIsSelected() else node.assertIsNotSelected()
    }
  }

  @Test
  fun bar_showsEveryTabWithItsLabel() {
    composeTestRule.setContent {
      BottomNavigationBar(selectedTab = Tab.OVERVIEW, onTabSelected = {})
    }

    composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar).assertIsDisplayed()
    Tab.entries.forEach { tab ->
      composeTestRule
          .onNode(hasTestTag(tab.testTag) and hasText(context.getString(tab.label)))
          .assertIsDisplayed()
    }
  }

  @Test
  fun bar_highlightsOnlyTheSelectedTab() {
    var selectedTab by mutableStateOf(Tab.OVERVIEW)
    composeTestRule.setContent {
      BottomNavigationBar(selectedTab = selectedTab, onTabSelected = {})
    }

    Tab.entries.forEach { tab ->
      composeTestRule.runOnIdle { selectedTab = tab }
      assertOnlySelected(tab)
    }
  }

  @Test
  fun everyTab_hasADifferentIconWhenSelected() {
    Tab.entries.forEach { tab ->
      assertNotEquals("${tab.name} uses the same icon when selected", tab.icon, tab.selectedIcon)
    }
  }

  @Test
  fun clickingEachTab_reportsIt() {
    val clicked = mutableListOf<Tab>()
    composeTestRule.setContent {
      BottomNavigationBar(selectedTab = Tab.OVERVIEW, onTabSelected = { clicked += it })
    }

    Tab.entries.forEach { tab -> composeTestRule.onNodeWithTag(tab.testTag).performClick() }

    composeTestRule.runOnIdle { assertEquals(Tab.entries.toList(), clicked) }
  }

  // ----- Figma: "Navigation bar" (Brand & Theme) -----

  private lateinit var colors: ColorScheme
  private lateinit var labelMedium: TextStyle

  private fun setThemedContent() {
    composeTestRule.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        labelMedium = MaterialTheme.typography.labelMedium
        BottomNavigationBar(selectedTab = Tab.OVERVIEW, onTabSelected = {})
      }
    }
  }

  // Real text drawing: Robolectric otherwise gives every label the same made-up height
  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun bar_is80dpTall() {
    setThemedContent()

    composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar).assertHeightIsEqualTo(80.dp)
  }

  @Test
  fun labels_areLabelMedium_selectedInInk_theOthersInSlate() {
    setThemedContent()

    for (tab in Tab.entries) {
      val color = if (tab == Tab.OVERVIEW) colors.onSurface else colors.onSurfaceVariant
      composeTestRule
          .onNodeWithText(context.getString(tab.label), useUnmergedTree = true)
          .assertTextStyle(labelMedium, color)
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun selectedTab_hasA64dpWideDawnIndicator() {
    setThemedContent()

    // The first of three 120dp tabs: its indicator is centered, from x = 28 to 92
    val bar = composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar)
    val cases =
        listOf(
            Triple("left of the indicator", 26.dp, colors.surfaceContainer),
            Triple("inside its left end", 30.dp, colors.secondaryContainer),
            Triple("unselected tab", 150.dp, colors.surfaceContainer),
        )
    for ((name, x, expected) in cases) assertSameColor(name, expected, bar.colorAt(x, 28.dp))
  }
}
