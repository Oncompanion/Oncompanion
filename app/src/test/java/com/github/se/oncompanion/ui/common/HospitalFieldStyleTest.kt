// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.hospital.Hospital
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.buttonLabel
import com.github.se.oncompanion.ui.theme.lakeColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The hospital search as the Figma draws it (Brand & Theme, "Edit profile – Hospital results"): a
 * tonal Search, the results as rows of a white card, and the credit as a text button.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HospitalFieldStyleTest {

  @get:Rule val compose = createComposeRule()

  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography
  private var primaryText = Color.Unspecified

  private fun showResults() {
    compose.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        primaryText = MaterialTheme.lakeColors.primaryText
        Surface(color = MaterialTheme.colorScheme.surface) {
          HospitalFieldContent(
              value = "Hôpital",
              onValueChange = {},
              uiState = HospitalFieldUiState.Results(listOf(HUG, LA_TOUR)),
              onSearch = {},
              onHospitalSelected = {},
          )
        }
      }
    }
  }

  @Test
  fun search_isATonalButton() {
    showResults()

    val search = compose.onNodeWithTag(C.Tag.hospital_search)
    search.assertHeightIsAtLeast(56.dp)
    // Left of its label, inside the pill
    assertSameColor("Search", colors.primaryContainer, search.colorAt(12.dp, 28.dp))
  }

  @Test
  fun results_areRowsOfAWhiteCard_eachNameAboveItsAddress() {
    showResults()

    val rows = compose.onAllNodesWithTag(C.Tag.hospital_result)
    for (i in 0..1) {
      rows[i].assertHeightIsAtLeast(64.dp)
      // Before the 16 dp text inset
      assertSameColor("row $i", colors.surfaceContainerLowest, rows[i].colorAt(8.dp, 32.dp))
    }
    for (hospital in listOf(HUG, LA_TOUR)) {
      text(hospital.name).assertTextStyle(typography.titleMedium, colors.onSurface)
      text(hospital.address).assertTextStyle(typography.bodySmall, colors.onSurfaceVariant)
    }
  }

  @Test
  fun credit_isATextButton() {
    showResults()

    compose.onNodeWithTag(C.Tag.hospital_attribution).assertHeightIsAtLeast(56.dp)
    text("© OpenStreetMap contributors").assertTextStyle(typography.buttonLabel, primaryText)
  }

  private fun text(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

  private companion object {
    val HUG =
        Hospital(
            "way/1",
            "HUG – Hôpitaux Universitaires de Genève",
            "Rue Gabrielle-Perret-Gentil 4, 1205 Genève",
            46.19,
            6.15,
        )
    val LA_TOUR =
        Hospital("way/2", "Hôpital de la Tour", "Avenue J.-D. Maillard 3, 1217 Meyrin", 46.23, 6.08)
  }
}
