package com.github.se.oncompanion.ui.navigation

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
  fun clickingEachTab_reportsIt() {
    val clicked = mutableListOf<Tab>()
    composeTestRule.setContent {
      BottomNavigationBar(selectedTab = Tab.OVERVIEW, onTabSelected = { clicked += it })
    }

    Tab.entries.forEach { tab -> composeTestRule.onNodeWithTag(tab.testTag).performClick() }

    composeTestRule.runOnIdle { assertEquals(Tab.entries.toList(), clicked) }
  }
}
