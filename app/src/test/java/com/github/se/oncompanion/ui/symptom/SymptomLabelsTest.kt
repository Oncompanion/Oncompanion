package com.github.se.oncompanion.ui.symptom

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.symptom.Severity
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SymptomLabelsTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private val fatigue =
      SymptomEntry(type = SymptomType.FATIGUE, intensity = 3, occurredAt = Instant.EPOCH)

  @Test
  fun everyTypeHasItsOwnLabel() {
    val labels = SymptomType.entries.map { context.getString(it.label) }

    assertEquals(
        listOf(
            "Fatigue",
            "Nausea",
            "Pain",
            "Loss of appetite",
            "Sleep problems",
            "Breathlessness",
            "Other",
        ),
        labels,
    )
  }

  @Test
  fun everySeverityHasItsOwnLabel() {
    assertEquals(
        listOf("Mild", "Moderate", "Severe"),
        Severity.entries.map { context.getString(it.label) },
    )
  }

  @Test
  fun title_ofAListedType_isItsLabel() {
    composeTestRule.setContent { Text(fatigue.title()) }
    composeTestRule.onNodeWithText("Fatigue").assertExists()
  }

  @Test
  fun title_ofOther_isTheLabelTheUserTyped() {
    composeTestRule.setContent {
      Text(fatigue.copy(type = SymptomType.OTHER, otherLabel = "Hiccups").title())
    }
    composeTestRule.onNodeWithText("Hiccups").assertExists()
  }

  @Test
  fun title_ofOtherWithoutLabel_fallsBackToOther() {
    composeTestRule.setContent { Text(fatigue.copy(type = SymptomType.OTHER).title()) }
    composeTestRule.onNodeWithText("Other").assertExists()
  }
}
