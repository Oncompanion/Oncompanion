// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClickableReadOnlyFieldTest {

  @get:Rule val composeTestRule = createComposeRule()

  private var clicks = 0

  private fun setContent(
      supportingText: String? = null,
      isError: Boolean = false,
  ) {
    composeTestRule.setContent {
      ClickableReadOnlyField(
          value = "5 Oct 2026",
          label = "Date",
          onClick = { clicks++ },
          onClickLabel = "Pick a date",
          trailingIcon = { Text("icon") },
          supportingText = supportingText?.let { { Text(it) } },
          isError = isError,
          modifier = Modifier.testTag(FIELD),
      )
    }
  }

  private fun field() = composeTestRule.onNodeWithTag(FIELD)

  @Test
  fun showsTheValueLabelAndSupportingText() {
    setContent(supportingText = "Set to now")

    field().assert(hasText("5 Oct 2026")).assert(hasText("Date")).assert(hasText("Set to now"))
  }

  @Test
  fun tapping_callsOnClick() {
    setContent()

    field().performClick()

    assertEquals(1, clicks)
  }

  @Test
  fun screenReaders_seeAButton_thatCallsOnClick() {
    setContent()

    field()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        .assert(
            SemanticsMatcher("click label is 'Pick a date'") {
              it.config.getOrNull(SemanticsActions.OnClick)?.label == "Pick a date"
            }
        )
        .performSemanticsAction(SemanticsActions.OnClick)

    assertEquals(1, clicks)
  }

  @Test
  fun anError_isAnnounced() {
    setContent(supportingText = "Later than now", isError = true)

    field().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
  }

  private companion object {
    const val FIELD = "field"
  }
}
