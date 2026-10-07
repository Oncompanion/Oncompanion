package com.github.se.oncompanion.ui.planning

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.navigation.Tab
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanningNavigationTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun planningIsReachableAndFitsAboveBottomBarWithLargeText() {
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
        OncompanionTheme { AppNavHost(startRoute = Route.OVERVIEW) }
      }
    }
    compose.onNodeWithTag(Tab.PLANNING.testTag).performClick()
    compose.onNodeWithTag(C.Tag.planning_week).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_empty).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_add).assertDoesNotExist()
    compose.onNodeWithTag(Tab.PLANNING.testTag).assertIsSelected()
    val heading = compose.onNodeWithTag(C.Tag.planning_heading).fetchSemanticsNode().boundsInRoot
    val bar = compose.onNodeWithTag(C.Tag.bottom_navigation_bar).fetchSemanticsNode().boundsInRoot
    assertTrue("Calendar heading must fit above bottom navigation", heading.bottom <= bar.top)
    // Keep a device-rendered artifact for visual review of this integration.
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.cacheDir, "planning-navigation.png").outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    compose.onNodeWithTag(C.Tag.planning_next).performClick()
    val selected =
        compose
            .onNodeWithTag(C.Tag.planning_heading)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .single()
            .text
    compose.onNodeWithTag(Tab.EVENTS.testTag).performClick()
    compose.onNodeWithTag(Tab.PLANNING.testTag).performClick()
    compose.onNodeWithTag(C.Tag.planning_heading).assertTextEquals(selected)
  }
}
