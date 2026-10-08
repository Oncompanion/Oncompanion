package com.github.se.oncompanion.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.cancer.CancerTypes
import com.github.se.oncompanion.resources.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CancerTypeFieldTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private val values = mutableListOf<String>()
  private var current by mutableStateOf("")

  private fun setField(initial: String = "", suggestions: ((String) -> List<String>)? = null) {
    current = initial
    composeTestRule.setContent {
      if (suggestions == null) {
        CancerTypeField(
            value = current,
            onValueChange = {
              values.add(it)
              current = it
            },
        )
      } else {
        CancerTypeField(
            value = current,
            onValueChange = {
              values.add(it)
              current = it
            },
            suggestions = suggestions(current),
        )
      }
    }
  }

  private fun suggestionNodes() =
      composeTestRule.onAllNodesWithTag(C.Tag.cancer_type_suggestion, useUnmergedTree = true)

  private fun suggestionTexts(): List<String> =
      composeTestRule.onAllNodesWithTag(C.Tag.cancer_type_suggestion).fetchSemanticsNodes().map {
          node ->
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""
      }

  private fun assertListClosed() {
    composeTestRule.waitForIdle()
    composeTestRule
        .onAllNodesWithTag(C.Tag.cancer_type_suggestions, useUnmergedTree = true)
        .assertCountEquals(0)
    suggestionNodes().assertCountEquals(0)
  }

  @Test
  fun field_isDisplayedWithLabelAndHint() {
    setField()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.cancer_type_label), useUnmergedTree = true)
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.cancer_type_hint), useUnmergedTree = true)
        .assertIsDisplayed()
    assertEquals("Cancer type", context.getString(R.string.cancer_type_label))
    assertEquals("Start typing to see suggestions", context.getString(R.string.cancer_type_hint))
  }

  @Test
  fun initially_listIsClosed() {
    setField()
    assertListClosed()
  }

  @Test
  fun typing_callsOnValueChange() {
    setField()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("leuk")
    composeTestRule.waitForIdle()
    assertEquals("leuk", values.last())
    assertEquals("leuk", current)
  }

  @Test
  fun typing_showsSuggestionsInOrder() {
    setField()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("leuk")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_suggestions).assertExists()
    val expected = CancerTypes.suggest("leuk")
    assertTrue(expected.isNotEmpty())
    suggestionNodes().assertCountEquals(expected.size)
    assertEquals(expected, suggestionTexts())
  }

  @Test
  fun typing_wordStartQuery_showsAllMatches() {
    setField()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("myel")
    composeTestRule.waitForIdle()
    assertEquals(CancerTypes.suggest("myel"), suggestionTexts())
  }

  @Test
  fun suggestion_matchingPartIsBold() {
    setField()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("myel")
    composeTestRule.waitForIdle()
    val texts: List<AnnotatedString> =
        composeTestRule
            .onAllNodesWithTag(C.Tag.cancer_type_suggestion)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull() }
    assertTrue(texts.isNotEmpty())
    texts.forEach { text ->
      val range = CancerTypes.matchRange(text.text, "myel")!!
      val boldSpans = text.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
      for (i in range) {
        assertTrue(
            "char $i of '${text.text}' should be bold",
            boldSpans.any { i >= it.start && i < it.end },
        )
      }
      for (i in text.text.indices.filterNot { it in range }) {
        assertTrue(
            "char $i of '${text.text}' should not be bold",
            boldSpans.none { i >= it.start && i < it.end },
        )
      }
    }
  }

  @Test
  fun noMatch_listNotShown() {
    setField()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("qqqq")
    composeTestRule.waitForIdle()
    assertEquals("qqqq", current)
    assertListClosed()
  }

  @Test
  fun shortQuery_listNotShown() {
    setField()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("x")
    assertListClosed()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextReplacement("l")
    assertListClosed()
  }

  @Test
  fun emptyCustomSuggestions_listNotShown() {
    setField(suggestions = { emptyList() })
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("leuk")
    assertListClosed()
  }

  @Test
  fun clickingSuggestion_fillsFieldAndClosesList() {
    setField()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("hodg")
    composeTestRule.waitForIdle()
    val first = CancerTypes.suggest("hodg").first()
    composeTestRule.onAllNodesWithTag(C.Tag.cancer_type_suggestion)[0].performClick()
    composeTestRule.waitForIdle()
    assertEquals(first, values.last())
    assertEquals(first, current)
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).assertTextContains(first)
    assertListClosed()
  }

  @Test
  fun clickingSecondSuggestion_selectsThatOne() {
    setField(suggestions = { if (it.isBlank()) emptyList() else listOf("Alpha", "Beta") })
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("zz")
    composeTestRule.waitForIdle()
    composeTestRule.onAllNodesWithTag(C.Tag.cancer_type_suggestion)[1].performClick()
    composeTestRule.waitForIdle()
    assertEquals("Beta", current)
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).assertTextContains("Beta")
    assertListClosed()
  }

  @Test
  fun freeText_isKeptAsTyped() {
    setField()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("Rare cancer XYZ")
    composeTestRule.waitForIdle()
    assertEquals("Rare cancer XYZ", current)
    assertEquals("Rare cancer XYZ", values.last())
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).assertTextContains("Rare cancer XYZ")
  }

  @Test
  fun freeText_withSuggestionsShownButNotClicked_isKept() {
    setField()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("leuk")
    composeTestRule.waitForIdle()
    assertEquals("leuk", current)
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).assertTextContains("leuk")
  }

  @Test
  fun customSuggestions_areDisplayedInGivenOrder() {
    val custom = listOf("Zeta tumor", "Alpha tumor", "Mid tumor")
    setField(suggestions = { if (it.isEmpty()) emptyList() else custom })
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("tu")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_suggestions).assertExists()
    suggestionNodes().assertCountEquals(3)
    assertEquals(custom, suggestionTexts())
  }

  @Test
  fun initialValue_isShownInField() {
    setField(initial = "Melanoma")
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).assertTextContains("Melanoma")
    assertListClosed()
  }

  @Test
  fun suggestionsTag_isPresentOnlyWhenOpen() {
    setField()
    composeTestRule.onAllNodes(hasTestTag(C.Tag.cancer_type_suggestions)).assertCountEquals(0)
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).performTextInput("breast")
    composeTestRule.waitForIdle()
    composeTestRule
        .onAllNodes(hasTestTag(C.Tag.cancer_type_suggestions), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .let { assertTrue(it.isNotEmpty()) }
  }

  @Test
  fun disabledField_doesNotOfferSuggestions() {
    composeTestRule.setContent {
      CancerTypeField(
          value = "bre",
          onValueChange = { throw AssertionError("disabled") },
          enabled = false,
      )
    }
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_suggestions).assertDoesNotExist()
  }
}
