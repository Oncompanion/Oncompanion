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
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.navigation.Tab
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
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

  @Test
  fun mixedAgendaIsReachableScrollableAndShowsMedicationFrequency() {
    val day = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val medication =
        PlanningItem(
            PlanningSource.Medication("sample-prescription", "sample-medication"),
            PlanningTiming.DateOnly(day),
            "Medication A",
            frequency = "Twice a day",
        )
    val appointment =
        PlanningItem(
            PlanningSource.Appointment("sample-appointment"),
            PlanningTiming.Timed(day.atTime(10, 0).atZone(zone).toInstant()),
            "Oncology appointment",
            "Hospital, Room 3",
        )
    val event =
        PlanningItem(
            PlanningSource.Event("sample-event"),
            PlanningTiming.Timed(day.atTime(14, 0).atZone(zone).toInstant()),
            "Support workshop",
            "Ligue · Geneva",
        )
    val source = PlanningRepository { flowOf(listOf(event, appointment, medication)) }
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
        OncompanionTheme { AppNavHost(startRoute = Route.PLANNING, planningRepository = source) }
      }
    }
    compose.onNodeWithText("Frequency: Twice a day").assertIsDisplayed()
    compose.onNodeWithContentDescription("Medication", useUnmergedTree = true).assertExists()
    compose.onNodeWithTag(C.Tag.planning_add).assertDoesNotExist()
    val med =
        compose.onNodeWithTag(C.Tag.planningItem(medication.key)).fetchSemanticsNode().boundsInRoot
    val scheduled =
        compose.onNodeWithTag(C.Tag.planning_scheduled).fetchSemanticsNode().boundsInRoot
    assertTrue("Medication precedes timed entries", med.bottom <= scheduled.top)
    compose
        .onNodeWithTag(C.Tag.planning_list)
        .performScrollToNode(hasTestTag(C.Tag.planningItem(event.key)))
    val eventBounds =
        compose.onNodeWithTag(C.Tag.planningItem(event.key)).fetchSemanticsNode().boundsInRoot
    val bar = compose.onNodeWithTag(C.Tag.bottom_navigation_bar).fetchSemanticsNode().boundsInRoot
    assertTrue("Event must be visible above navigation", eventBounds.bottom <= bar.top)
    compose.onNodeWithTag(C.Tag.planning_list).performScrollToIndex(0)
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.cacheDir, "planning-mixed.png").outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
